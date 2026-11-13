package org.nexus.framework.registry

import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.NexusCapability
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ActiveProviderLease(
    val leaseToken: String,
    val verifiedPackage: String,
    val verifiedUid: Int,
    val capabilities: Map<String, NexusCapability>,
    val providerBinder: INexusProvider,
    val deathRecipient: IBinder.DeathRecipient,
    val registeredAtEpochMs: Long,
    var expiresAtEpochMs: Long,
    val onReleaseResources: () -> Unit = {},
    val generationId: Long = 0L,
    val state: LeaseState = LeaseState.ACTIVE,
    val disconnectEpoch: Long = 0L,
    val recoveryDeadlineEpochMs: Long? = null,
    val leaseDurationMs: Long = 60000L
)

sealed class RegistryEvent {
    data class ProviderRegistered(val leaseToken: String, val packageName: String, val capabilityCount: Int) : RegistryEvent()
    data class ProviderReplaced(val oldLeaseToken: String, val newLeaseToken: String, val packageName: String, val newGenerationId: Long) : RegistryEvent()
    data class ProviderDisconnected(val leaseToken: String, val packageName: String, val reason: String) : RegistryEvent()
    data class ProviderRecovered(val leaseToken: String, val packageName: String, val capabilityCount: Int) : RegistryEvent()
    data class ProviderUnregistered(val leaseToken: String, val packageName: String, val reason: String) : RegistryEvent()
    data class ProviderDied(val leaseToken: String, val packageName: String) : RegistryEvent()
    data class LeaseExpired(val leaseToken: String, val packageName: String) : RegistryEvent()
}

data class RegisteredLeaseInfo(
    val leaseToken: String,
    val generationId: Long
)

sealed class DisconnectResult {
    data class EnteredGracePeriod(
        val leaseToken: String,
        val generationId: Long,
        val disconnectEpoch: Long,
        val providerPackage: String,
        val deadlineEpochMs: Long
    ) : DisconnectResult()

    data object Ignored : DisconnectResult()
}

/**
 * Independent, idempotent cleanup handle for a lease's Binder death link and
 * reverse-bound ServiceConnection. Executed at most once on permanent termination.
 */
class LeaseResourceHandle(
    @Volatile var providerBinder: INexusProvider,
    @Volatile var deathRecipient: IBinder.DeathRecipient,
    private val onReleaseResources: () -> Unit
) {
    private val released = AtomicBoolean(false)

    fun isReleased(): Boolean = released.get()

    fun swapAttachedBinder(newBinder: INexusProvider, newDeathRecipient: IBinder.DeathRecipient) {
        val oldBinder = providerBinder
        val oldRecipient = deathRecipient
        providerBinder = newBinder
        deathRecipient = newDeathRecipient
        try {
            oldBinder.asBinder().unlinkToDeath(oldRecipient, 0)
        } catch (_: Throwable) {}
    }

    fun releaseOnce() {
        if (!released.compareAndSet(false, true)) {
            return
        }
        try {
            providerBinder.asBinder().unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {}
        try {
            onReleaseResources()
        } catch (_: Throwable) {}
    }
}

class CapabilityRegistry(
    private val recoveryGraceMs: Long = DEFAULT_RECOVERY_GRACE_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_RECOVERY_GRACE_MS = 15_000L
    }

    private class ManagedLease(
        var lease: ActiveProviderLease,
        val identityKey: ProviderIdentityKey,
        val resourceHandle: LeaseResourceHandle,
        var recoveryTimerJob: Job? = null
    )

    private val registryLock = Any()
    private val generationCounter = AtomicLong(0L)
    private val snapshotVersionCounter = AtomicLong(0L)

    private val leasesByToken = HashMap<String, ManagedLease>()
    private val activeLeaseByProviderKey = HashMap<ProviderIdentityKey, String>()
    private val routeOwnerByCapability = HashMap<String, RouteOwner>()

    @Volatile
    var routingSnapshot: RoutingSnapshot = RoutingSnapshot()
        private set

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
        val deathRecipient = IBinder.DeathRecipient {
            handleProviderDied(leaseToken)
            onDeathCallback(leaseToken)
        }

        try {
            providerBinder.asBinder().linkToDeath(deathRecipient, 0)
        } catch (e: android.os.RemoteException) {
            return ""
        } catch (_: Throwable) {
            // In unit test stubs or mock environments where linkToDeath is not supported, allow registration to proceed
        }

        return registerPreLinkedProvider(
            leaseToken = leaseToken,
            caller = caller,
            capabilities = capabilities,
            providerBinder = providerBinder,
            deathRecipient = deathRecipient,
            leaseDurationMs = leaseDurationMs,
            onReleaseResources = {}
        )
    }

    /**
     * Commits a Stage 2/3 reverse-bound provider whose IBinder death link and ping()
     * verification were completed during staging prior to commit.
     * Performs atomic replacement of any previous lease for the same (uid, packageName).
     */
    fun registerPreLinkedProvider(
        leaseToken: String = UUID.randomUUID().toString(),
        caller: VerifiedCaller,
        capabilities: List<NexusCapability>,
        providerBinder: INexusProvider,
        deathRecipient: IBinder.DeathRecipient,
        leaseDurationMs: Long,
        onReleaseResources: () -> Unit = {}
    ): String {
        return registerPreLinkedLease(
            leaseToken = leaseToken,
            caller = caller,
            capabilities = capabilities,
            providerBinder = providerBinder,
            deathRecipient = deathRecipient,
            leaseDurationMs = leaseDurationMs,
            onReleaseResources = onReleaseResources
        ).leaseToken
    }

    fun registerPreLinkedLease(
        leaseToken: String = UUID.randomUUID().toString(),
        caller: VerifiedCaller,
        capabilities: List<NexusCapability>,
        providerBinder: INexusProvider,
        deathRecipient: IBinder.DeathRecipient,
        leaseDurationMs: Long,
        onReleaseResources: () -> Unit = {}
    ): RegisteredLeaseInfo {
        val now = clockMs()
        val duration = leaseDurationMs.coerceIn(10000L, 300000L)
        val identityKey = ProviderIdentityKey(caller.uid, caller.packageName)

        val stampedCapabilities = capabilities.associate { cap ->
            val stamped = cap.copy(
                providerPackage = caller.packageName,
                providerUid = caller.uid,
                isAvailable = true
            )
            cap.capabilityId to stamped
        }

        val resourceHandle = LeaseResourceHandle(providerBinder, deathRecipient, onReleaseResources)

        var supersededHandle: LeaseResourceHandle? = null
        var supersededTimer: Job? = null
        var supersededToken: String? = null
        var newGenerationId = 0L
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>

        synchronized(registryLock) {
            newGenerationId = generationCounter.incrementAndGet()

            // Atomically supersede any previous lease for the same provider identity
            val previousToken = activeLeaseByProviderKey[identityKey]
            if (previousToken != null && previousToken != leaseToken) {
                val previousManaged = leasesByToken.remove(previousToken)
                if (previousManaged != null) {
                    val prevGen = previousManaged.lease.generationId
                    previousManaged.lease = previousManaged.lease.copy(state = LeaseState.TERMINATED)
                    supersededHandle = previousManaged.resourceHandle
                    supersededTimer = previousManaged.recoveryTimerJob
                    previousManaged.recoveryTimerJob = null
                    supersededToken = previousToken

                    // Remove any routes owned by (previousToken, prevGen) that are not in the new capability set
                    previousManaged.lease.capabilities.keys.forEach { oldCapId ->
                        if (!stampedCapabilities.containsKey(oldCapId)) {
                            removeRouteIfOwnedLocked(oldCapId, previousToken, prevGen)
                        }
                    }
                }
            }

            val lease = ActiveProviderLease(
                leaseToken = leaseToken,
                verifiedPackage = caller.packageName,
                verifiedUid = caller.uid,
                capabilities = stampedCapabilities,
                providerBinder = providerBinder,
                deathRecipient = deathRecipient,
                registeredAtEpochMs = now,
                expiresAtEpochMs = now + duration,
                onReleaseResources = onReleaseResources,
                generationId = newGenerationId,
                state = LeaseState.ACTIVE,
                disconnectEpoch = 0L,
                recoveryDeadlineEpochMs = null,
                leaseDurationMs = duration
            )

            leasesByToken[leaseToken] = ManagedLease(
                lease = lease,
                identityKey = identityKey,
                resourceHandle = resourceHandle
            )
            activeLeaseByProviderKey[identityKey] = leaseToken

            stampedCapabilities.keys.forEach { capId ->
                routeOwnerByCapability[capId] = RouteOwner(
                    leaseToken = leaseToken,
                    generationId = newGenerationId,
                    identityKey = identityKey
                )
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        // Cleanup superseded resources and publish flows/events strictly outside registryLock
        supersededTimer?.cancel()
        supersededHandle?.releaseOnce()

        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        if (supersededToken != null) {
            recordEvent(
                RegistryEvent.ProviderReplaced(
                    oldLeaseToken = supersededToken!!,
                    newLeaseToken = leaseToken,
                    packageName = caller.packageName,
                    newGenerationId = newGenerationId
                )
            )
        }
        recordEvent(RegistryEvent.ProviderRegistered(leaseToken, caller.packageName, stampedCapabilities.size))

        return RegisteredLeaseInfo(leaseToken = leaseToken, generationId = newGenerationId)
    }

    /**
     * Unified idempotent transient disconnect transition (ACTIVE -> DISCONNECTED).
     * Suspends capability routes in the immutable routing snapshot while keeping the
     * ServiceConnection bound for the 15-second recovery grace period.
     */
    fun markLeaseDisconnected(
        leaseToken: String,
        expectedGenerationId: Long? = null,
        reason: String = "Binder disconnected"
    ): DisconnectResult {
        val now = clockMs()
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>
        val result: DisconnectResult.EnteredGracePeriod

        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return DisconnectResult.Ignored
            val current = managed.lease
            if (expectedGenerationId != null && current.generationId != expectedGenerationId) {
                return DisconnectResult.Ignored
            }
            if (current.state != LeaseState.ACTIVE) {
                return DisconnectResult.Ignored
            }

            val nextDisconnectEpoch = current.disconnectEpoch + 1L
            val deadline = now + recoveryGraceMs
            managed.lease = current.copy(
                state = LeaseState.DISCONNECTED,
                disconnectEpoch = nextDisconnectEpoch,
                recoveryDeadlineEpochMs = deadline
            )

            // Suspend capability routes without deleting the lease or unbinding ServiceConnection
            current.capabilities.keys.forEach { capId ->
                removeRouteIfOwnedLocked(capId, leaseToken, current.generationId)
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
            result = DisconnectResult.EnteredGracePeriod(
                leaseToken = leaseToken,
                generationId = current.generationId,
                disconnectEpoch = nextDisconnectEpoch,
                providerPackage = current.verifiedPackage,
                deadlineEpochMs = deadline
            )
        }

        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        recordEvent(RegistryEvent.ProviderDisconnected(leaseToken, result.providerPackage, reason))
        return result
    }

    /**
     * Associates a 15-second recovery timer Job with a specific (leaseToken, generationId, disconnectEpoch).
     * If the lease already recovered or terminated before attachment, cancels [timerJob] immediately.
     */
    fun attachRecoveryTimer(
        leaseToken: String,
        generationId: Long,
        disconnectEpoch: Long,
        timerJob: Job
    ) {
        var timerToCancel: Job? = null
        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken]
            if (managed != null &&
                managed.lease.generationId == generationId &&
                managed.lease.state == LeaseState.DISCONNECTED &&
                managed.lease.disconnectEpoch == disconnectEpoch
            ) {
                timerToCancel = managed.recoveryTimerJob
                managed.recoveryTimerJob = timerJob
            } else {
                timerToCancel = timerJob
            }
        }
        timerToCancel?.cancel()
    }

    fun isLeaseRecoverable(leaseToken: String, generationId: Long): Boolean {
        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            if (managed.lease.generationId != generationId) return false
            return managed.lease.state == LeaseState.DISCONNECTED || managed.lease.state == LeaseState.ACTIVE
        }
    }

    /**
     * Attempts to reactivate a lease after recovery verification & authentication finish.
     * Rechecks lease existence, generation, non-terminated state, and grace deadline under lock
     * so stale callbacks or late auth results can never resurrect superseded/expired leases.
     */
    fun tryRecoverLease(
        leaseToken: String,
        expectedGenerationId: Long,
        expectedDisconnectEpoch: Long? = null,
        newProviderBinder: INexusProvider,
        newDeathRecipient: IBinder.DeathRecipient
    ): Boolean {
        val now = clockMs()
        var timerToCancel: Job? = null
        var resourceHandleToSwap: LeaseResourceHandle? = null
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>
        val providerPackage: String
        val capCount: Int

        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            val current = managed.lease
            if (current.generationId != expectedGenerationId) return false
            if (current.state == LeaseState.TERMINATED) return false
            if (expectedDisconnectEpoch != null && current.disconnectEpoch != expectedDisconnectEpoch) return false
            if (activeLeaseByProviderKey[managed.identityKey] != leaseToken) return false

            val deadline = current.recoveryDeadlineEpochMs
            if (deadline != null && now > deadline) {
                return false
            }

            timerToCancel = managed.recoveryTimerJob
            managed.recoveryTimerJob = null

            managed.lease = current.copy(
                state = LeaseState.ACTIVE,
                providerBinder = newProviderBinder,
                deathRecipient = newDeathRecipient,
                expiresAtEpochMs = now + current.leaseDurationMs,
                recoveryDeadlineEpochMs = null
            )

            current.capabilities.keys.forEach { capId ->
                routeOwnerByCapability[capId] = RouteOwner(
                    leaseToken = leaseToken,
                    generationId = expectedGenerationId,
                    identityKey = managed.identityKey
                )
            }

            resourceHandleToSwap = managed.resourceHandle
            providerPackage = current.verifiedPackage
            capCount = current.capabilities.size
            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        timerToCancel?.cancel()
        resourceHandleToSwap?.swapAttachedBinder(newProviderBinder, newDeathRecipient)
        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        recordEvent(RegistryEvent.ProviderRecovered(leaseToken, providerPackage, capCount))
        return true
    }

    /**
     * Called when the 15-second recovery timer fires. Terminates the lease only if it is still
     * in DISCONNECTED state for the exact (generationId, disconnectEpoch).
     */
    fun expireRecoveryGrace(
        leaseToken: String,
        expectedGenerationId: Long,
        expectedDisconnectEpoch: Long
    ): Boolean {
        var handleToRelease: LeaseResourceHandle? = null
        var timerToCancel: Job? = null
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>
        val providerPackage: String

        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            val current = managed.lease
            if (current.generationId != expectedGenerationId ||
                current.disconnectEpoch != expectedDisconnectEpoch ||
                current.state != LeaseState.DISCONNECTED
            ) {
                return false
            }

            leasesByToken.remove(leaseToken)
            managed.lease = current.copy(state = LeaseState.TERMINATED)
            timerToCancel = managed.recoveryTimerJob
            managed.recoveryTimerJob = null
            handleToRelease = managed.resourceHandle
            providerPackage = current.verifiedPackage

            if (activeLeaseByProviderKey[managed.identityKey] == leaseToken) {
                activeLeaseByProviderKey.remove(managed.identityKey)
            }
            current.capabilities.keys.forEach { capId ->
                removeRouteIfOwnedLocked(capId, leaseToken, current.generationId)
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        timerToCancel?.cancel()
        handleToRelease?.releaseOnce()
        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        recordEvent(RegistryEvent.ProviderDied(leaseToken, providerPackage))
        return true
    }

    fun renewLease(caller: VerifiedCaller, leaseToken: String, additionalDurationMs: Long = 30000L): Boolean {
        val now = clockMs()
        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            val current = managed.lease
            if (current.verifiedUid != caller.uid) {
                return false
            }
            if (current.state != LeaseState.ACTIVE) {
                return false
            }
            val newExpiry = now + additionalDurationMs.coerceIn(10000L, 300000L)
            current.expiresAtEpochMs = newExpiry
            managed.lease = current.copy(expiresAtEpochMs = newExpiry)
            return true
        }
    }

    fun unregisterProvider(caller: VerifiedCaller, leaseToken: String): Boolean {
        var handleToRelease: LeaseResourceHandle? = null
        var timerToCancel: Job? = null
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>
        val providerPackage: String

        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            val current = managed.lease
            if (current.verifiedUid != caller.uid) {
                return false
            }

            leasesByToken.remove(leaseToken)
            managed.lease = current.copy(state = LeaseState.TERMINATED)
            timerToCancel = managed.recoveryTimerJob
            managed.recoveryTimerJob = null
            handleToRelease = managed.resourceHandle
            providerPackage = current.verifiedPackage

            if (activeLeaseByProviderKey[managed.identityKey] == leaseToken) {
                activeLeaseByProviderKey.remove(managed.identityKey)
            }
            current.capabilities.keys.forEach { capId ->
                removeRouteIfOwnedLocked(capId, leaseToken, current.generationId)
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        timerToCancel?.cancel()
        handleToRelease?.releaseOnce()
        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        recordEvent(RegistryEvent.ProviderUnregistered(leaseToken, providerPackage, "Graceful Deactivation"))
        return true
    }

    fun terminateLease(
        leaseToken: String,
        expectedGenerationId: Long? = null,
        reason: String = "Permanent termination"
    ): Boolean {
        var handleToRelease: LeaseResourceHandle? = null
        var timerToCancel: Job? = null
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>
        val providerPackage: String

        synchronized(registryLock) {
            val managed = leasesByToken[leaseToken] ?: return false
            val current = managed.lease
            if (expectedGenerationId != null && current.generationId != expectedGenerationId) {
                return false
            }

            leasesByToken.remove(leaseToken)
            managed.lease = current.copy(state = LeaseState.TERMINATED)
            timerToCancel = managed.recoveryTimerJob
            managed.recoveryTimerJob = null
            handleToRelease = managed.resourceHandle
            providerPackage = current.verifiedPackage

            if (activeLeaseByProviderKey[managed.identityKey] == leaseToken) {
                activeLeaseByProviderKey.remove(managed.identityKey)
            }
            current.capabilities.keys.forEach { capId ->
                removeRouteIfOwnedLocked(capId, leaseToken, current.generationId)
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        timerToCancel?.cancel()
        handleToRelease?.releaseOnce()
        publishFlows(snapshotToPublish, activeLeasesSnapshot)
        recordEvent(RegistryEvent.ProviderDied(leaseToken, providerPackage))
        return true
    }

    fun handleProviderDied(leaseToken: String) {
        terminateLease(leaseToken, expectedGenerationId = null, reason = "Provider Binder died")
    }

    fun cleanupExpiredLeases(): List<String> {
        val now = clockMs()
        val expiredEntries = mutableListOf<ManagedLease>()
        var snapshotToPublish: RoutingSnapshot? = null
        var activeLeasesSnapshot: List<ActiveProviderLease>? = null

        synchronized(registryLock) {
            val expiredTokens = leasesByToken.values
                .filter { it.lease.state == LeaseState.ACTIVE && now > it.lease.expiresAtEpochMs }
                .map { it.lease.leaseToken }

            if (expiredTokens.isEmpty()) {
                return emptyList()
            }

            expiredTokens.forEach { token ->
                val managed = leasesByToken.remove(token)
                if (managed != null) {
                    val current = managed.lease
                    managed.lease = current.copy(state = LeaseState.TERMINATED)
                    if (activeLeaseByProviderKey[managed.identityKey] == token) {
                        activeLeaseByProviderKey.remove(managed.identityKey)
                    }
                    current.capabilities.keys.forEach { capId ->
                        removeRouteIfOwnedLocked(capId, token, current.generationId)
                    }
                    expiredEntries.add(managed)
                }
            }

            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = collectActiveLeasesLocked()
        }

        expiredEntries.forEach { managed ->
            managed.recoveryTimerJob?.cancel()
            managed.recoveryTimerJob = null
            managed.resourceHandle.releaseOnce()
            recordEvent(RegistryEvent.LeaseExpired(managed.lease.leaseToken, managed.lease.verifiedPackage))
        }

        if (snapshotToPublish != null && activeLeasesSnapshot != null) {
            publishFlows(snapshotToPublish!!, activeLeasesSnapshot!!)
        }

        return expiredEntries.map { it.lease.leaseToken }
    }

    fun terminateAllLeases(reason: String = "Hub service shutdown") {
        val removed = mutableListOf<ManagedLease>()
        val snapshotToPublish: RoutingSnapshot
        val activeLeasesSnapshot: List<ActiveProviderLease>

        synchronized(registryLock) {
            removed.addAll(leasesByToken.values)
            leasesByToken.clear()
            activeLeaseByProviderKey.clear()
            routeOwnerByCapability.clear()
            snapshotToPublish = rebuildSnapshotLocked()
            activeLeasesSnapshot = emptyList()
        }

        removed.forEach { managed ->
            managed.lease = managed.lease.copy(state = LeaseState.TERMINATED)
            managed.recoveryTimerJob?.cancel()
            managed.recoveryTimerJob = null
            managed.resourceHandle.releaseOnce()
            recordEvent(RegistryEvent.ProviderUnregistered(managed.lease.leaseToken, managed.lease.verifiedPackage, reason))
        }

        publishFlows(snapshotToPublish, activeLeasesSnapshot)
    }

    /**
     * Lock-free O(1) read against the latest published immutable RoutingSnapshot.
     */
    fun getProviderForCapability(capabilityId: String): Pair<NexusCapability, INexusProvider>? {
        val route = routingSnapshot.routesByCapabilityId[capabilityId] ?: return null
        return Pair(route.capability, route.providerBinder)
    }

    fun getRouteForCapability(capabilityId: String): CapabilityRoute? {
        return routingSnapshot.routesByCapabilityId[capabilityId]
    }

    /**
     * Lock-free read against the latest published immutable RoutingSnapshot.
     */
    fun getCapabilities(filterCategory: String?): List<NexusCapability> {
        val all = routingSnapshot.capabilitiesList
        return if (filterCategory.isNullOrBlank() || filterCategory.equals("all", ignoreCase = true)) {
            all
        } else {
            all.filter { it.category.equals(filterCategory, ignoreCase = true) }
        }
    }

    fun getLeaseState(leaseToken: String): LeaseState? {
        synchronized(registryLock) {
            return leasesByToken[leaseToken]?.lease?.state
        }
    }

    fun getActiveLeaseTokenForProvider(uid: Int, packageName: String): String? {
        synchronized(registryLock) {
            return activeLeaseByProviderKey[ProviderIdentityKey(uid, packageName)]
        }
    }

    fun getRouteOwner(capabilityId: String): RouteOwner? {
        synchronized(registryLock) {
            return routeOwnerByCapability[capabilityId]
        }
    }

    private fun removeRouteIfOwnedLocked(capabilityId: String, leaseToken: String, generationId: Long) {
        val currentOwner = routeOwnerByCapability[capabilityId]
        if (currentOwner != null &&
            currentOwner.leaseToken == leaseToken &&
            currentOwner.generationId == generationId
        ) {
            routeOwnerByCapability.remove(capabilityId)
        }
    }

    private fun rebuildSnapshotLocked(): RoutingSnapshot {
        val routes = LinkedHashMap<String, CapabilityRoute>(routeOwnerByCapability.size)
        val caps = ArrayList<NexusCapability>(routeOwnerByCapability.size)

        routeOwnerByCapability.forEach { (capId, owner) ->
            val managed = leasesByToken[owner.leaseToken]
            if (managed != null &&
                managed.lease.state == LeaseState.ACTIVE &&
                managed.lease.generationId == owner.generationId
            ) {
                val cap = managed.lease.capabilities[capId]
                if (cap != null) {
                    routes[capId] = CapabilityRoute(
                        capability = cap,
                        providerBinder = managed.lease.providerBinder,
                        leaseToken = owner.leaseToken,
                        generationId = owner.generationId,
                        providerPackage = managed.lease.verifiedPackage,
                        providerUid = managed.lease.verifiedUid
                    )
                    caps.add(cap)
                }
            }
        }

        val snapshot = RoutingSnapshot(
            version = snapshotVersionCounter.incrementAndGet(),
            routesByCapabilityId = routes,
            capabilitiesList = caps
        )
        routingSnapshot = snapshot
        return snapshot
    }

    private fun collectActiveLeasesLocked(): List<ActiveProviderLease> {
        return leasesByToken.values
            .filter { it.lease.state == LeaseState.ACTIVE }
            .map { it.lease }
    }

    private fun publishFlows(snapshot: RoutingSnapshot, activeLeases: List<ActiveProviderLease>) {
        _activeCapabilitiesFlow.value = snapshot.capabilitiesList
        _activeLeasesFlow.value = activeLeases
    }

    private fun recordEvent(event: RegistryEvent) {
        _registryEvents.value = (listOf(event) + _registryEvents.value).take(50)
    }
}
