package org.nexus.framework.registry

import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.NexusCapability
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveProviderLease(
    val leaseToken: String,
    val verifiedPackage: String,
    val verifiedUid: Int,
    val capabilities: Map<String, NexusCapability>,
    val providerBinder: INexusProvider,
    val deathRecipient: IBinder.DeathRecipient,
    val registeredAtEpochMs: Long,
    var expiresAtEpochMs: Long
)

sealed class RegistryEvent {
    data class ProviderRegistered(val leaseToken: String, val packageName: String, val capabilityCount: Int) : RegistryEvent()
    data class ProviderUnregistered(val leaseToken: String, val packageName: String, val reason: String) : RegistryEvent()
    data class ProviderDied(val leaseToken: String, val packageName: String) : RegistryEvent()
    data class LeaseExpired(val leaseToken: String, val packageName: String) : RegistryEvent()
}

class CapabilityRegistry {

    private val activeLeases = ConcurrentHashMap<String, ActiveProviderLease>()
    private val capabilityRoutingTable = ConcurrentHashMap<String, String>() // capabilityId -> leaseToken

    private val _registryEvents = MutableStateFlow<List<RegistryEvent>>(emptyList())
    val registryEvents: StateFlow<List<RegistryEvent>> = _registryEvents.asStateFlow()

    private val _activeCapabilitiesFlow = MutableStateFlow<List<NexusCapability>>(emptyList())
    val activeCapabilitiesFlow: StateFlow<List<NexusCapability>> = _activeCapabilitiesFlow.asStateFlow()

    private val _activeLeasesFlow = MutableStateFlow<List<ActiveProviderLease>>(emptyList())
    val activeLeasesFlow: StateFlow<List<ActiveProviderLease>> get() = _activeLeasesFlow

    fun registerProvider(
        caller: VerifiedCaller,
        capabilities: List<NexusCapability>,
        providerBinder: INexusProvider,
        leaseDurationMs: Long,
        onDeathCallback: (leaseToken: String) -> Unit
    ): String {
        val leaseToken = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val duration = leaseDurationMs.coerceIn(10000L, 300000L) // Bounded 10s to 5min

        // Stamping verified caller identity from kernel credentials
        val stampedCapabilities = capabilities.associate { cap ->
            val stamped = cap.copy(
                providerPackage = caller.packageName,
                providerUid = caller.uid,
                isAvailable = true
            )
            cap.capabilityId to stamped
        }

        val deathRecipient = IBinder.DeathRecipient {
            handleProviderDied(leaseToken)
            onDeathCallback(leaseToken)
        }

        try {
            providerBinder.asBinder().linkToDeath(deathRecipient, 0)
        } catch (e: android.os.RemoteException) {
            // Provider already died before link was established
            return ""
        } catch (ignored: Throwable) {
            // In unit test stubs or mock environments where linkToDeath is not supported, allow registration to proceed
        }

        val lease = ActiveProviderLease(
            leaseToken = leaseToken,
            verifiedPackage = caller.packageName,
            verifiedUid = caller.uid,
            capabilities = stampedCapabilities,
            providerBinder = providerBinder,
            deathRecipient = deathRecipient,
            registeredAtEpochMs = now,
            expiresAtEpochMs = now + duration
        )

        activeLeases[leaseToken] = lease
        stampedCapabilities.keys.forEach { capId ->
            capabilityRoutingTable[capId] = leaseToken
        }

        syncFlows()
        recordEvent(RegistryEvent.ProviderRegistered(leaseToken, caller.packageName, stampedCapabilities.size))
        return leaseToken
    }

    fun renewLease(caller: VerifiedCaller, leaseToken: String, additionalDurationMs: Long = 30000L): Boolean {
        val lease = activeLeases[leaseToken] ?: return false
        if (lease.verifiedUid != caller.uid) {
            return false // Caller UID mismatch
        }
        val now = System.currentTimeMillis()
        lease.expiresAtEpochMs = now + additionalDurationMs.coerceIn(10000L, 300000L)
        return true
    }

    fun unregisterProvider(caller: VerifiedCaller, leaseToken: String): Boolean {
        val lease = activeLeases.remove(leaseToken) ?: return false
        if (lease.verifiedUid != caller.uid) {
            // Re-insert if caller UID does not match lease owner
            activeLeases[leaseToken] = lease
            return false
        }

        try {
            lease.providerBinder.asBinder().unlinkToDeath(lease.deathRecipient, 0)
        } catch (_: Exception) {}

        lease.capabilities.keys.forEach { capId ->
            capabilityRoutingTable.remove(capId)
        }

        syncFlows()
        recordEvent(RegistryEvent.ProviderUnregistered(leaseToken, lease.verifiedPackage, "Graceful Deactivation"))
        return true
    }

    fun handleProviderDied(leaseToken: String) {
        val lease = activeLeases.remove(leaseToken) ?: return
        lease.capabilities.keys.forEach { capId ->
            capabilityRoutingTable.remove(capId)
        }
        syncFlows()
        recordEvent(RegistryEvent.ProviderDied(leaseToken, lease.verifiedPackage))
    }

    fun cleanupExpiredLeases(): List<String> {
        val now = System.currentTimeMillis()
        val expiredTokens = mutableListOf<String>()

        activeLeases.forEach { (token, lease) ->
            if (now > lease.expiresAtEpochMs) {
                expiredTokens.add(token)
            }
        }

        expiredTokens.forEach { token ->
            val lease = activeLeases.remove(token)
            if (lease != null) {
                try {
                    lease.providerBinder.asBinder().unlinkToDeath(lease.deathRecipient, 0)
                } catch (_: Exception) {}
                lease.capabilities.keys.forEach { capId ->
                    capabilityRoutingTable.remove(capId)
                }
                recordEvent(RegistryEvent.LeaseExpired(token, lease.verifiedPackage))
            }
        }

        if (expiredTokens.isNotEmpty()) {
            syncFlows()
        }
        return expiredTokens
    }

    fun getProviderForCapability(capabilityId: String): Pair<NexusCapability, INexusProvider>? {
        val leaseToken = capabilityRoutingTable[capabilityId] ?: return null
        val lease = activeLeases[leaseToken] ?: return null
        val cap = lease.capabilities[capabilityId] ?: return null
        return Pair(cap, lease.providerBinder)
    }

    fun getCapabilities(filterCategory: String?): List<NexusCapability> {
        val all = activeLeases.values.flatMap { it.capabilities.values }
        return if (filterCategory.isNullOrBlank() || filterCategory.equals("all", ignoreCase = true)) {
            all
        } else {
            all.filter { it.category.equals(filterCategory, ignoreCase = true) }
        }
    }

    private fun syncFlows() {
        val caps = activeLeases.values.flatMap { it.capabilities.values }
        _activeCapabilitiesFlow.value = caps
        _activeLeasesFlow.value = activeLeases.values.toList()
    }

    private fun recordEvent(event: RegistryEvent) {
        _registryEvents.value = (listOf(event) + _registryEvents.value).take(50)
    }
}
