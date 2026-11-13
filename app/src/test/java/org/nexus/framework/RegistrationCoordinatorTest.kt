package org.nexus.framework

import org.junit.Assert.*
import org.junit.Test
import org.nexus.framework.registry.RegistrationCoordinator
import org.nexus.framework.registry.RegistrationOutcome
import org.nexus.framework.registry.RegistrationState
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RegistrationCoordinatorTest {

    private val sampleCaps = listOf(
        NexusCapability(
            capabilityId = "nexus.text.uppercase",
            displayName = "Uppercase",
            description = "Converts text to uppercase",
            version = "1.0.0"
        )
    )

    @Test
    fun testStagingToCommittingToActiveHappyPath() {
        val coordinator = RegistrationCoordinator()
        val callbacks = CopyOnWriteArrayList<NexusResponse>()
        val releaseCount = AtomicInteger(0)

        val attempt = coordinator.beginRegistration(
            registrationId = "reg-happy-1",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() },
            onTerminalCallback = { callbacks.add(it) }
        )

        assertNotNull(attempt)
        assertEquals(RegistrationState.STAGING, attempt!!.currentState)
        assertEquals(1L, attempt.generationId)
        assertEquals(1, coordinator.activeStagingCount())

        var observedStateInsideCommit: RegistrationState? = null
        val outcome = coordinator.tryCommit("reg-happy-1") {
            observedStateInsideCommit = it.currentState
            "lease-token-uuid-123"
        }

        assertEquals(RegistrationState.COMMITTING, observedStateInsideCommit)
        assertTrue(outcome is RegistrationOutcome.Activated)
        assertEquals("lease-token-uuid-123", (outcome as RegistrationOutcome.Activated).leaseToken)
        assertEquals(RegistrationState.ACTIVE, attempt.currentState)
        assertEquals(0, coordinator.activeStagingCount())
        assertEquals(0, releaseCount.get())
        assertEquals(1, callbacks.size)
        assertTrue(callbacks[0].isSuccess)
        assertEquals(NexusResponse.STATUS_SUCCESS, callbacks[0].statusCode)
        assertEquals("lease-token-uuid-123", callbacks[0].resultData)
    }

    @Test
    fun testStagingToAbortedPath() {
        val coordinator = RegistrationCoordinator()
        val callbacks = CopyOnWriteArrayList<NexusResponse>()
        val releaseCount = AtomicInteger(0)

        val attempt = coordinator.beginRegistration(
            registrationId = "reg-abort-1",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() },
            onTerminalCallback = { callbacks.add(it) }
        )!!

        val outcome = coordinator.tryAbort(
            registrationId = "reg-abort-1",
            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
            reason = "Staging watchdog expired"
        )

        assertNotNull(outcome)
        assertEquals(RegistrationState.ABORTED, attempt.currentState)
        assertEquals(0, coordinator.activeStagingCount())
        assertEquals(1, releaseCount.get())
        assertEquals(1, callbacks.size)
        assertFalse(callbacks[0].isSuccess)
        assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, callbacks[0].statusCode)
    }

    @Test
    fun testRecoverableCommitFailureTransitionsCommittingToAborted() {
        val coordinator = RegistrationCoordinator()
        val callbacks = CopyOnWriteArrayList<NexusResponse>()
        val releaseCount = AtomicInteger(0)

        val attempt = coordinator.beginRegistration(
            registrationId = "reg-commit-fail-1",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() },
            onTerminalCallback = { callbacks.add(it) }
        )!!

        val outcome = coordinator.tryCommit("reg-commit-fail-1") {
            throw IllegalStateException("Simulated registry commit fault")
        }

        assertTrue(outcome is RegistrationOutcome.Aborted)
        assertEquals(RegistrationState.ABORTED, attempt.currentState)
        assertEquals(0, coordinator.activeStagingCount())
        assertEquals(1, releaseCount.get())
        assertEquals(1, callbacks.size)
        assertFalse(callbacks[0].isSuccess)
        assertEquals(NexusResponse.STATUS_EXECUTION_FAILED, callbacks[0].statusCode)
        assertTrue(callbacks[0].errorMessage?.contains("Simulated registry commit fault") == true)
    }

    @Test
    fun testAbortCannotPreemptInProgressCommitAndOnlyOneCallbackFires() {
        val coordinator = RegistrationCoordinator()
        val callbacks = CopyOnWriteArrayList<NexusResponse>()
        val releaseCount = AtomicInteger(0)
        val enteredCommit = CountDownLatch(1)
        val allowCommitFinish = CountDownLatch(1)

        val attempt = coordinator.beginRegistration(
            registrationId = "reg-race-commit",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() },
            onTerminalCallback = { callbacks.add(it) }
        )!!

        val executor = Executors.newSingleThreadExecutor()
        val commitFuture = executor.submit<RegistrationOutcome?> {
            coordinator.tryCommit("reg-race-commit") {
                enteredCommit.countDown()
                allowCommitFinish.await(2, TimeUnit.SECONDS)
                "lease-winner"
            }
        }

        assertTrue(enteredCommit.await(2, TimeUnit.SECONDS))
        assertEquals(RegistrationState.COMMITTING, attempt.currentState)

        // Competing abort during COMMITTING must lose and must NOT fire callback or release resources
        val abortOutcome = coordinator.tryAbort(
            registrationId = "reg-race-commit",
            statusCode = NexusResponse.STATUS_TIMEOUT,
            reason = "Late watchdog"
        )
        assertNull(abortOutcome)
        assertEquals(0, callbacks.size)
        assertEquals(0, releaseCount.get())

        allowCommitFinish.countDown()
        val commitOutcome = commitFuture.get(2, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(commitOutcome is RegistrationOutcome.Activated)
        assertEquals(RegistrationState.ACTIVE, attempt.currentState)
        assertEquals(1, callbacks.size)
        assertEquals(0, releaseCount.get())
    }

    @Test
    fun testDuplicateTerminalEventsOnlyOneWins() {
        val coordinator = RegistrationCoordinator()
        val callbacks = CopyOnWriteArrayList<NexusResponse>()
        val releaseCount = AtomicInteger(0)

        coordinator.beginRegistration(
            registrationId = "reg-dup-events",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() },
            onTerminalCallback = { callbacks.add(it) }
        )

        val firstAbort = coordinator.tryAbort("reg-dup-events", NexusResponse.STATUS_TIMEOUT, "Timeout")
        val secondAbort = coordinator.tryAbort("reg-dup-events", NexusResponse.STATUS_PROVIDER_DISCONNECTED, "Disconnected")
        val lateCommit = coordinator.tryCommit("reg-dup-events") { "lease-should-not-commit" }

        assertNotNull(firstAbort)
        assertNull(secondAbort)
        assertNull(lateCommit)
        assertEquals(1, releaseCount.get())
        assertEquals(1, callbacks.size)
        assertEquals(NexusResponse.STATUS_TIMEOUT, callbacks[0].statusCode)
    }

    @Test
    fun testDuplicateRegistrationIdsCannotInterfere() {
        val coordinator = RegistrationCoordinator()
        val firstCallbacks = CopyOnWriteArrayList<NexusResponse>()
        val duplicateCallbacks = CopyOnWriteArrayList<NexusResponse>()
        val firstReleaseCount = AtomicInteger(0)

        val first = coordinator.beginRegistration(
            registrationId = "same-reg-id",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onReleaseResources = { firstReleaseCount.incrementAndGet() },
            onTerminalCallback = { firstCallbacks.add(it) }
        )
        assertNotNull(first)

        // Duplicate attempt while active must be rejected without disturbing original attempt
        val duplicateWhileActive = coordinator.beginRegistration(
            registrationId = "same-reg-id",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onTerminalCallback = { duplicateCallbacks.add(it) }
        )
        assertNull(duplicateWhileActive)
        assertEquals(RegistrationState.STAGING, first!!.currentState)
        assertEquals(0, firstReleaseCount.get())
        assertEquals(0, firstCallbacks.size)
        assertEquals(0, duplicateCallbacks.size)

        // Original attempt can still commit cleanly
        val outcome = coordinator.tryCommit("same-reg-id") { "lease-original" }
        assertTrue(outcome is RegistrationOutcome.Activated)
        assertEquals(1, firstCallbacks.size)

        // Duplicate ID submitted even after completion is still rejected
        val duplicateAfterComplete = coordinator.beginRegistration(
            registrationId = "same-reg-id",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onTerminalCallback = { duplicateCallbacks.add(it) }
        )
        assertNull(duplicateAfterComplete)
        assertEquals(0, duplicateCallbacks.size)
    }

    @Test
    fun testGenerationIdsAreStrictlyMonotonic() {
        val coordinator = RegistrationCoordinator()
        val a1 = coordinator.beginRegistration(
            registrationId = "gen-1",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onTerminalCallback = {}
        )!!
        val a2 = coordinator.beginRegistration(
            registrationId = "gen-2",
            callerPackage = "org.nexus.provider.text",
            callerUid = 10142,
            providerComponent = null,
            capabilities = sampleCaps,
            leaseDurationMs = 60000L,
            onTerminalCallback = {}
        )!!

        assertTrue(a2.generationId > a1.generationId)
    }
}
