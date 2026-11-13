package org.nexus.framework.registry

import android.content.ComponentName
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks a single asynchronous registration attempt with monotonic generation identity,
 * recoverable commit state transitions (STAGING -> COMMITTING -> ACTIVE / ABORTED),
 * idempotent resource cleanup, and guaranteed at-most-once terminal callback delivery.
 */
class RegistrationAttempt internal constructor(
    val metadata: RegistrationMetadata,
    private val onReleaseResources: () -> Unit,
    private val onTerminalCallback: (NexusResponse) -> Unit
) {
    val registrationId: String get() = metadata.registrationId
    val generationId: Long get() = metadata.generationId

    private val stateRef = AtomicReference(RegistrationState.STAGING)
    private val resourcesReleased = AtomicBoolean(false)
    private val callbackDelivered = AtomicBoolean(false)

    val currentState: RegistrationState
        get() = stateRef.get()

    internal fun transitionState(expected: RegistrationState, target: RegistrationState): Boolean =
        stateRef.compareAndSet(expected, target)

    internal fun releaseResourcesOnce() {
        if (resourcesReleased.compareAndSet(false, true)) {
            onReleaseResources()
        }
    }

    internal fun deliverOutcomeOnce(outcome: RegistrationOutcome) {
        if (!callbackDelivered.compareAndSet(false, true)) {
            return
        }
        val response = when (outcome) {
            is RegistrationOutcome.Activated -> NexusResponse(
                requestId = outcome.registrationId,
                isSuccess = true,
                statusCode = NexusResponse.STATUS_SUCCESS,
                resultData = outcome.leaseToken,
                errorMessage = null,
                verifiedProviderPackage = metadata.callerIdentity.packageName
            )
            is RegistrationOutcome.Aborted -> NexusResponse(
                requestId = outcome.registrationId,
                isSuccess = false,
                statusCode = outcome.statusCode,
                resultData = null,
                errorMessage = outcome.reason,
                verifiedProviderPackage = metadata.callerIdentity.packageName
            )
        }
        onTerminalCallback(response)
    }
}

/**
 * Coordinates asynchronous provider registration attempts, guaranteeing:
 * 1. Unique registration IDs (duplicate IDs are rejected without interfering with existing attempts).
 * 2. Monotonic generation IDs across all attempts.
 * 3. Recoverable commit transitions (STAGING -> COMMITTING -> ACTIVE or ABORTED on failure).
 * 4. At-most-once terminal callback delivery invoked strictly outside coordination locks.
 */
class RegistrationCoordinator {

    private val generationCounter = AtomicLong(0L)
    private val activeAttempts = ConcurrentHashMap<String, RegistrationAttempt>()
    private val seenRegistrationIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Initiates a new registration attempt in the STAGING state.
     * Returns null if registrationId is blank or has already been used (without modifying any existing attempt).
     */
    fun beginRegistration(
        registrationId: String = UUID.randomUUID().toString(),
        callerPackage: String,
        callerUid: Int,
        providerComponent: ComponentName?,
        capabilities: List<NexusCapability>,
        leaseDurationMs: Long,
        onReleaseResources: () -> Unit = {},
        onTerminalCallback: (NexusResponse) -> Unit
    ): RegistrationAttempt? {
        if (registrationId.isBlank()) return null

        // Prevent duplicate registration IDs from interfering with active or previously tracked attempts
        if (!seenRegistrationIds.add(registrationId)) {
            return null
        }

        val generationId = generationCounter.incrementAndGet()
        val attempt = RegistrationAttempt(
            metadata = RegistrationMetadata(
                registrationId = registrationId,
                generationId = generationId,
                callerIdentity = ProviderIdentityKey(uid = callerUid, packageName = callerPackage),
                providerComponent = providerComponent,
                capabilities = capabilities,
                leaseDurationMs = leaseDurationMs
            ),
            onReleaseResources = onReleaseResources,
            onTerminalCallback = onTerminalCallback
        )

        val existing = activeAttempts.putIfAbsent(registrationId, attempt)
        if (existing != null) {
            return null
        }

        return attempt
    }

    /**
     * Attempts to commit a STAGING registration: STAGING -> COMMITTING -> ACTIVE.
     * If commitAction throws or returns a blank leaseToken, transitions COMMITTING -> ABORTED,
     * releases resources idempotently, and delivers a single failure callback.
     * Returns null if this attempt is not found or already transitioned out of STAGING.
     */
    fun tryCommit(
        registrationId: String,
        commitAction: (RegistrationAttempt) -> String
    ): RegistrationOutcome? {
        val attempt = activeAttempts[registrationId] ?: return null
        if (!attempt.transitionState(RegistrationState.STAGING, RegistrationState.COMMITTING)) {
            return null
        }

        val outcome: RegistrationOutcome = try {
            val leaseToken = commitAction(attempt)
            if (leaseToken.isBlank()) {
                throw IllegalStateException("Commit produced a blank leaseToken")
            }
            if (!attempt.transitionState(RegistrationState.COMMITTING, RegistrationState.ACTIVE)) {
                throw IllegalStateException("Unexpected registration state during commit finalization")
            }
            RegistrationOutcome.Activated(
                registrationId = attempt.registrationId,
                generationId = attempt.generationId,
                leaseToken = leaseToken
            )
        } catch (t: Throwable) {
            attempt.transitionState(RegistrationState.COMMITTING, RegistrationState.ABORTED)
            attempt.releaseResourcesOnce()
            RegistrationOutcome.Aborted(
                registrationId = attempt.registrationId,
                generationId = attempt.generationId,
                statusCode = NexusResponse.STATUS_EXECUTION_FAILED,
                reason = "Registration commit failed: ${t.message ?: t::class.java.simpleName}"
            )
        } finally {
            activeAttempts.remove(registrationId, attempt)
        }

        attempt.deliverOutcomeOnce(outcome)
        return outcome
    }

    /**
     * Attempts to abort a STAGING registration: STAGING -> ABORTED.
     * Releases resources idempotently and delivers a single failure callback.
     * Returns null if already COMMITTING, ACTIVE, or ABORTED.
     */
    fun tryAbort(
        registrationId: String,
        statusCode: Int,
        reason: String
    ): RegistrationOutcome.Aborted? {
        val attempt = activeAttempts[registrationId] ?: return null
        if (!attempt.transitionState(RegistrationState.STAGING, RegistrationState.ABORTED)) {
            return null
        }

        activeAttempts.remove(registrationId, attempt)
        attempt.releaseResourcesOnce()

        val outcome = RegistrationOutcome.Aborted(
            registrationId = attempt.registrationId,
            generationId = attempt.generationId,
            statusCode = statusCode,
            reason = reason
        )
        attempt.deliverOutcomeOnce(outcome)
        return outcome
    }

    fun getAttempt(registrationId: String): RegistrationAttempt? = activeAttempts[registrationId]

    fun activeStagingCount(): Int = activeAttempts.size
}
