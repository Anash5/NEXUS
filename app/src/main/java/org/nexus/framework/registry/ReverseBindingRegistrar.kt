package org.nexus.framework.registry

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstraction over Android explicit component service binding so reverse-binding
 * handshakes and recovery can be tested deterministically in JVM unit tests without ComponentName stubs.
 */
interface ReverseServiceBindAdapter {
    fun bind(componentPackage: String, componentClass: String, connection: ServiceConnection): Boolean
    fun unbind(connection: ServiceConnection)
}

class AndroidReverseServiceBindAdapter(private val context: Context) : ReverseServiceBindAdapter {
    override fun bind(componentPackage: String, componentClass: String, connection: ServiceConnection): Boolean {
        val explicitIntent = Intent().apply {
            component = ComponentName(componentPackage, componentClass)
        }
        return context.bindService(
            explicitIntent,
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )
    }

    override fun unbind(connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (_: Throwable) {}
    }
}

/**
 * Orchestrates explicit Framework-to-Provider reverse binding for asynchronous
 * provider registration (Stage 2) and unified 15-second transient recovery (Stage 3).
 */
class ReverseBindingRegistrar(
    private val registry: CapabilityRegistry,
    private val coordinator: RegistrationCoordinator,
    private val scope: CoroutineScope,
    private val bindAdapter: ReverseServiceBindAdapter,
    private val verificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stagingTimeoutMs: Long = 5000L,
    private val pingTimeoutMs: Long = 2000L,
    private val recoveryGraceMs: Long = CapabilityRegistry.DEFAULT_RECOVERY_GRACE_MS,
    private val binderConverter: (IBinder) -> INexusProvider = { INexusProvider.Stub.asInterface(it) },
    private val recoveryAuthenticator: suspend (providerPackage: String, componentClass: String, expectedUid: Int, capabilityIds: List<String>) -> Boolean = { _, _, _, _ -> true },
    private val onProviderCrashed: (providerPackage: String) -> Unit = {},
    private val onLeaseRecovered: (leaseToken: String, providerPackage: String) -> Unit = { _, _ -> }
) {

    fun initiateReverseRegistration(
        registrationId: String = UUID.randomUUID().toString(),
        caller: VerifiedCaller,
        componentPackage: String,
        componentClass: String,
        providerComponent: ComponentName? = null,
        capabilities: List<NexusCapability>,
        leaseDurationMs: Long,
        onTerminalCallback: (NexusResponse) -> Unit
    ): String? {
        val leaseToken = UUID.randomUUID().toString()
        val capabilityIds = capabilities.map { it.capabilityId }
        val binderDiedDuringStaging = AtomicBoolean(false)
        val disconnectedDuringStaging = AtomicBoolean(false)
        val committed = AtomicBoolean(false)
        val committedGenerationRef = AtomicLong(0L)
        val binderGenerationCounter = AtomicLong(0L)
        val attachedBinder = AtomicReference<IBinder?>(null)
        val attachedDeathRecipient = AtomicReference<IBinder.DeathRecipient?>(null)
        val watchdogJobRef = AtomicReference<Job?>(null)
        lateinit var connection: ServiceConnection

        val verifiedCaller = if (caller.packageName == componentPackage) {
            caller
        } else {
            caller.copy(packageName = componentPackage, isAmbiguous = false)
        }

        fun handleTransientDisconnect(expectedBinderGen: Long?, reason: String) {
            if (expectedBinderGen != null && binderGenerationCounter.get() != expectedBinderGen) {
                // Ignore late binderDied() from a previous Binder generation after recovery
                return
            }
            val generationId = committedGenerationRef.get()
            if (!committed.get() || generationId == 0L) {
                return
            }

            // Unified idempotent disconnect transition: suspends routes and enters 15s grace period
            // WITHOUT unbinding the recoverable ServiceConnection.
            val disconnectResult = registry.markLeaseDisconnected(
                leaseToken = leaseToken,
                expectedGenerationId = generationId,
                reason = reason
            )

            if (disconnectResult is DisconnectResult.EnteredGracePeriod) {
                onProviderCrashed(componentPackage)

                val timerJob = scope.launch {
                    delay(recoveryGraceMs)
                    registry.expireRecoveryGrace(
                        leaseToken = disconnectResult.leaseToken,
                        expectedGenerationId = disconnectResult.generationId,
                        expectedDisconnectEpoch = disconnectResult.disconnectEpoch
                    )
                }
                registry.attachRecoveryTimer(
                    leaseToken = disconnectResult.leaseToken,
                    generationId = disconnectResult.generationId,
                    disconnectEpoch = disconnectResult.disconnectEpoch,
                    timerJob = timerJob
                )
            }
        }

        val releaseStagingResources = {
            watchdogJobRef.getAndSet(null)?.cancel()
            val binder = attachedBinder.getAndSet(null)
            val recipient = attachedDeathRecipient.getAndSet(null)
            if (binder != null && recipient != null) {
                try {
                    binder.unlinkToDeath(recipient, 0)
                } catch (_: Throwable) {}
            }
            try {
                bindAdapter.unbind(connection)
            } catch (_: Throwable) {}
        }

        val attempt = coordinator.beginRegistration(
            registrationId = registrationId,
            callerPackage = componentPackage,
            callerUid = caller.uid,
            providerComponent = providerComponent,
            capabilities = capabilities,
            leaseDurationMs = leaseDurationMs,
            onReleaseResources = releaseStagingResources,
            onTerminalCallback = onTerminalCallback
        ) ?: return null

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    if (committed.get()) {
                        handleTransientDisconnect(null, "Provider returned null IBinder during recovery")
                    } else {
                        coordinator.tryAbort(
                            registrationId = registrationId,
                            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                            reason = "Provider returned null IBinder in onServiceConnected"
                        )
                    }
                    return
                }

                // Stage 3 Recovery Path for an already-committed lease on this ServiceConnection
                if (committed.get()) {
                    val committedGen = committedGenerationRef.get()
                    if (!registry.isLeaseRecoverable(leaseToken, committedGen)) {
                        return
                    }

                    val recoveredBinderGen = binderGenerationCounter.incrementAndGet()

                    scope.launch(verificationDispatcher) {
                        if (!registry.isLeaseRecoverable(leaseToken, committedGen)) {
                            return@launch
                        }

                        // 1. Authenticate the reconnected provider before checking/reactivating lease state
                        val authOk = try {
                            recoveryAuthenticator(componentPackage, componentClass, verifiedCaller.uid, capabilityIds)
                        } catch (_: Throwable) {
                            false
                        }
                        if (!authOk) {
                            if (registry.terminateLease(leaseToken, committedGen, "Recovery authentication failed")) {
                                onProviderCrashed(componentPackage)
                            }
                            return@launch
                        }

                        val recoveredProvider = try {
                            binderConverter(service)
                        } catch (_: Throwable) {
                            return@launch
                        }

                        val recoveredDeathRecipient = IBinder.DeathRecipient {
                            if (!committed.get()) {
                                binderDiedDuringStaging.set(true)
                            } else {
                                handleTransientDisconnect(
                                    expectedBinderGen = recoveredBinderGen,
                                    reason = "Recovered provider Binder died"
                                )
                            }
                        }

                        try {
                            service.linkToDeath(recoveredDeathRecipient, 0)
                        } catch (_: Throwable) {
                            return@launch
                        }

                        val pingDeferred = scope.async(verificationDispatcher) {
                            recoveredProvider.ping()
                        }
                        val pingPassed = try {
                            withTimeoutOrNull(pingTimeoutMs) {
                                pingDeferred.await()
                            } ?: false
                        } catch (_: Throwable) {
                            false
                        } finally {
                            if (!pingDeferred.isCompleted) {
                                pingDeferred.cancel()
                            }
                        }

                        if (!pingPassed || !service.isBinderAlive) {
                            try {
                                service.unlinkToDeath(recoveredDeathRecipient, 0)
                            } catch (_: Throwable) {}
                            return@launch
                        }

                        // 2. Recheck lease generation and state atomically under registryLock
                        val recovered = registry.tryRecoverLease(
                            leaseToken = leaseToken,
                            expectedGenerationId = committedGen,
                            expectedDisconnectEpoch = null,
                            newProviderBinder = recoveredProvider,
                            newDeathRecipient = recoveredDeathRecipient
                        )

                        if (recovered) {
                            attachedBinder.set(service)
                            attachedDeathRecipient.set(recoveredDeathRecipient)
                            onLeaseRecovered(leaseToken, componentPackage)
                        } else {
                            // Grace timer expired or a newer registration superseded this lease during auth/ping.
                            // Never reactivate a superseded or terminated lease; clean up this late binder cleanly.
                            try {
                                service.unlinkToDeath(recoveredDeathRecipient, 0)
                            } catch (_: Throwable) {}
                            try {
                                bindAdapter.unbind(connection)
                            } catch (_: Throwable) {}
                        }
                    }
                    return
                }

                // Stage 2 Initial Staging Path
                if (attempt.currentState != RegistrationState.STAGING) {
                    try {
                        bindAdapter.unbind(this)
                    } catch (_: Throwable) {}
                    return
                }

                val initialBinderGen = binderGenerationCounter.incrementAndGet()

                // Hand off off-main-thread for linkToDeath + bounded ping() verification
                scope.launch(verificationDispatcher) {
                    if (attempt.currentState != RegistrationState.STAGING) {
                        try {
                            bindAdapter.unbind(connection)
                        } catch (_: Throwable) {}
                        return@launch
                    }

                    val providerBinder = try {
                        binderConverter(service)
                    } catch (e: Throwable) {
                        coordinator.tryAbort(
                            registrationId = registrationId,
                            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                            reason = "Failed to adapt provider Binder: ${e.message}"
                        )
                        return@launch
                    }

                    val deathRecipient = IBinder.DeathRecipient {
                        if (!committed.get()) {
                            binderDiedDuringStaging.set(true)
                            coordinator.tryAbort(
                                registrationId = registrationId,
                                statusCode = NexusResponse.STATUS_PROVIDER_CRASHED,
                                reason = "Provider Binder died during staging"
                            )
                        } else {
                            handleTransientDisconnect(
                                expectedBinderGen = initialBinderGen,
                                reason = "Provider Binder died"
                            )
                        }
                    }

                    attachedBinder.set(service)
                    attachedDeathRecipient.set(deathRecipient)

                    // Attach DeathRecipient BEFORE ping() and fail closed if linkToDeath fails
                    try {
                        service.linkToDeath(deathRecipient, 0)
                    } catch (e: Throwable) {
                        attachedBinder.set(null)
                        attachedDeathRecipient.set(null)
                        coordinator.tryAbort(
                            registrationId = registrationId,
                            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                            reason = "Failed to linkToDeath during registration: ${e.message}"
                        )
                        return@launch
                    }

                    // Bounded ping() verification off main & binder threads
                    val pingDeferred = scope.async(verificationDispatcher) {
                        providerBinder.ping()
                    }
                    val pingPassed = try {
                        withTimeoutOrNull(pingTimeoutMs) {
                            pingDeferred.await()
                        } ?: false
                    } catch (_: Throwable) {
                        false
                    } finally {
                        if (!pingDeferred.isCompleted) {
                            pingDeferred.cancel()
                        }
                    }

                    if (!pingPassed || binderDiedDuringStaging.get() || disconnectedDuringStaging.get() || !service.isBinderAlive) {
                        coordinator.tryAbort(
                            registrationId = registrationId,
                            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                            reason = "Provider ping() verification failed or Binder is not alive"
                        )
                        return@launch
                    }

                    val outcome = coordinator.tryCommit(registrationId) {
                        if (binderDiedDuringStaging.get() || disconnectedDuringStaging.get() || !service.isBinderAlive) {
                            throw IllegalStateException("Provider Binder died or disconnected immediately before commit")
                        }
                        watchdogJobRef.getAndSet(null)?.cancel()
                        val leaseInfo = registry.registerPreLinkedLease(
                            leaseToken = leaseToken,
                            caller = verifiedCaller,
                            capabilities = capabilities,
                            providerBinder = providerBinder,
                            deathRecipient = deathRecipient,
                            leaseDurationMs = leaseDurationMs,
                            onReleaseResources = {
                                try {
                                    bindAdapter.unbind(connection)
                                } catch (_: Throwable) {}
                            }
                        )
                        committedGenerationRef.set(leaseInfo.generationId)
                        committed.set(true)
                        if (binderDiedDuringStaging.get() || disconnectedDuringStaging.get() || !service.isBinderAlive) {
                            registry.terminateLease(
                                leaseToken = leaseToken,
                                expectedGenerationId = leaseInfo.generationId,
                                reason = "Provider disconnected during registry commit"
                            )
                            throw IllegalStateException("Provider Binder died or disconnected during registry commit")
                        }
                        leaseInfo.leaseToken
                    }

                    if (outcome == null) {
                        // Lost race to abort/watchdog: clean up late Binder
                        releaseStagingResources()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (committed.get()) {
                    // Transient Binder disconnect: mark DISCONNECTED and keep ServiceConnection bound for 15s grace
                    handleTransientDisconnect(expectedBinderGen = null, reason = "onServiceDisconnected")
                } else {
                    disconnectedDuringStaging.set(true)
                    coordinator.tryAbort(
                        registrationId = registrationId,
                        statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                        reason = "Provider disconnected during registration staging"
                    )
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (committed.get()) {
                    // Permanent binding death: immediately terminate lease and unbind once
                    val generationId = committedGenerationRef.get()
                    if (registry.terminateLease(leaseToken, generationId, "onBindingDied")) {
                        onProviderCrashed(componentPackage)
                    }
                } else {
                    disconnectedDuringStaging.set(true)
                    coordinator.tryAbort(
                        registrationId = registrationId,
                        statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                        reason = "Provider binding died during registration staging"
                    )
                }
            }

            override fun onNullBinding(name: ComponentName?) {
                if (committed.get()) {
                    val generationId = committedGenerationRef.get()
                    if (registry.terminateLease(leaseToken, generationId, "onNullBinding")) {
                        onProviderCrashed(componentPackage)
                    }
                } else {
                    disconnectedDuringStaging.set(true)
                    coordinator.tryAbort(
                        registrationId = registrationId,
                        statusCode = NexusResponse.STATUS_PERMISSION_DENIED,
                        reason = "Provider returned null binding"
                    )
                }
            }
        }

        // Start staging timeout watchdog before issuing bindService
        val watchdog = scope.launch {
            delay(stagingTimeoutMs)
            coordinator.tryAbort(
                registrationId = registrationId,
                statusCode = NexusResponse.STATUS_TIMEOUT,
                reason = "Provider reverse-binding timed out after ${stagingTimeoutMs}ms"
            )
        }
        watchdogJobRef.set(watchdog)
        if (attempt.currentState != RegistrationState.STAGING) {
            watchdog.cancel()
        }

        val bindAccepted = try {
            bindAdapter.bind(componentPackage, componentClass, connection)
        } catch (_: Throwable) {
            false
        }

        if (!bindAccepted) {
            coordinator.tryAbort(
                registrationId = registrationId,
                statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                reason = "Explicit reverse bindService returned false for $componentPackage/$componentClass"
            )
        }

        return registrationId
    }
}
