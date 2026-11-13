package org.nexus.framework

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.registry.DisconnectResult
import org.nexus.framework.registry.LeaseResourceHandle
import org.nexus.framework.registry.LeaseState
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

class CapabilityRegistryTest {

    private class TestIBinder : IBinder {
        val unlinkCount = AtomicInteger(0)
        override fun getInterfaceDescriptor(): String = "org.nexus.interop.contract.INexusProvider"
        override fun pingBinder(): Boolean = true
        override fun isBinderAlive(): Boolean = true
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            unlinkCount.incrementAndGet()
            return true
        }
    }

    private class TestProvider(val tag: String, val testBinder: TestIBinder = TestIBinder()) : INexusProvider {
        override fun asBinder(): IBinder = testBinder
        override fun executeAsync(request: NexusRequest?, callback: INexusResponseCallback?) {}
        override fun ping(): Boolean = true
    }

    private val dummyBinder = object : INexusProvider {
        override fun asBinder(): IBinder = object : android.os.Binder() {}
        override fun executeAsync(request: NexusRequest?, callback: INexusResponseCallback?) {}
        override fun ping(): Boolean = true
    }

    @Test
    fun testRegistrationAndDiscovery() {
        val registry = CapabilityRegistry()
        val caller = VerifiedCaller(uid = 10142, pid = 4120, packageName = "org.nexus.provider.text", isPlatformOrSystem = false)

        val caps = listOf(
            NexusCapability(
                capabilityId = "nexus.text.uppercase",
                displayName = "Uppercase",
                description = "Converts to uppercase",
                version = "1.0.0"
            ),
            NexusCapability(
                capabilityId = "nexus.text.lowercase",
                displayName = "Lowercase",
                description = "Converts to lowercase",
                version = "1.0.0"
            )
        )

        val token = registry.registerProvider(
            caller = caller,
            capabilities = caps,
            providerBinder = dummyBinder,
            leaseDurationMs = 30000L,
            onDeathCallback = {}
        )

        assertTrue(token.isNotBlank())

        val discovered = registry.getCapabilities("text")
        assertEquals(2, discovered.size)
        assertTrue(discovered.any { it.capabilityId == "nexus.text.uppercase" })
        assertEquals("org.nexus.provider.text", discovered.first().providerPackage)
        assertEquals(10142, discovered.first().providerUid)

        // Test unregister
        val unregistered = registry.unregisterProvider(caller, token)
        assertTrue(unregistered)
        assertEquals(0, registry.getCapabilities("text").size)
    }

    @Test
    fun testCallerMismatchCannotUnregister() {
        val registry = CapabilityRegistry()
        val ownerCaller = VerifiedCaller(uid = 10142, pid = 4120, packageName = "org.nexus.provider.text", isPlatformOrSystem = false)
        val attackerCaller = VerifiedCaller(uid = 10999, pid = 8888, packageName = "org.malicious.app", isPlatformOrSystem = false)

        val token = registry.registerProvider(
            caller = ownerCaller,
            capabilities = listOf(NexusCapability("nexus.text.reverse", "Reverse", "Reverses text")),
            providerBinder = dummyBinder,
            leaseDurationMs = 30000L,
            onDeathCallback = {}
        )

        // Attacker attempts to unregister owner's lease
        val attempt = registry.unregisterProvider(attackerCaller, token)
        assertFalse(attempt)
        assertEquals(1, registry.getCapabilities("text").size)
    }

    // --- Stage 3 Tests ---

    @Test
    fun testImmutableRoutingSnapshotVersioningAndCopyOnWriteIsolation() {
        val registry = CapabilityRegistry()
        val caller = VerifiedCaller(uid = 10142, pid = 100, packageName = "org.nexus.provider.text")
        val initialSnapshot = registry.routingSnapshot
        assertEquals(0L, initialSnapshot.version)
        assertTrue(initialSnapshot.routesByCapabilityId.isEmpty())

        val p1 = TestProvider("v1")
        val token1 = registry.registerPreLinkedProvider(
            caller = caller,
            capabilities = listOf(NexusCapability("nexus.text.uppercase", "Upper", "Upper")),
            providerBinder = p1,
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 30000L
        )

        val snapshotAfterFirst = registry.routingSnapshot
        assertEquals(1L, snapshotAfterFirst.version)
        assertEquals(1, snapshotAfterFirst.routesByCapabilityId.size)
        // Prior snapshot remains immutable
        assertTrue(initialSnapshot.routesByCapabilityId.isEmpty())

        val p2 = TestProvider("v2")
        registry.registerPreLinkedProvider(
            caller = caller,
            capabilities = listOf(
                NexusCapability("nexus.text.uppercase", "Upper", "Upper"),
                NexusCapability("nexus.text.reverse", "Reverse", "Reverse")
            ),
            providerBinder = p2,
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 30000L
        )

        val snapshotAfterSecond = registry.routingSnapshot
        assertTrue(snapshotAfterSecond.version > snapshotAfterFirst.version)
        assertEquals(1, snapshotAfterFirst.routesByCapabilityId.size)
        assertEquals(token1, snapshotAfterFirst.routesByCapabilityId["nexus.text.uppercase"]?.leaseToken)
        assertEquals(2, snapshotAfterSecond.routesByCapabilityId.size)
        assertSame(p2, snapshotAfterSecond.routesByCapabilityId["nexus.text.uppercase"]?.providerBinder)
    }

    @Test
    fun testAtomicLeaseReplacementAndStaleLeaseCannotDeleteNewRoutes() {
        val registry = CapabilityRegistry()
        val caller = VerifiedCaller(uid = 10142, pid = 100, packageName = "org.nexus.provider.text")

        val releaseOldCount = AtomicInteger(0)
        val releaseNewCount = AtomicInteger(0)
        val p1 = TestProvider("p1")
        val p2 = TestProvider("p2")

        val lease1 = registry.registerPreLinkedLease(
            leaseToken = "lease-gen-1",
            caller = caller,
            capabilities = listOf(
                NexusCapability("nexus.text.uppercase", "Upper", "Upper"),
                NexusCapability("nexus.text.reverse", "Reverse", "Reverse")
            ),
            providerBinder = p1,
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseOldCount.incrementAndGet() }
        )

        val lease2 = registry.registerPreLinkedLease(
            leaseToken = "lease-gen-2",
            caller = caller,
            capabilities = listOf(
                NexusCapability("nexus.text.uppercase", "Upper", "Upper"),
                NexusCapability("nexus.text.wordcount", "WordCount", "WordCount")
            ),
            providerBinder = p2,
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseNewCount.incrementAndGet() }
        )

        assertTrue(lease2.generationId > lease1.generationId)
        assertEquals(1, releaseOldCount.get())
        assertEquals(0, releaseNewCount.get())
        assertNull(registry.getLeaseState(lease1.leaseToken))
        assertEquals(LeaseState.ACTIVE, registry.getLeaseState(lease2.leaseToken))
        assertEquals(lease2.leaseToken, registry.getActiveLeaseTokenForProvider(10142, "org.nexus.provider.text"))

        // Stale route removed, new & shared routes owned strictly by lease2 generation2
        assertNull(registry.getProviderForCapability("nexus.text.reverse"))
        assertEquals(lease2.generationId, registry.getRouteOwner("nexus.text.uppercase")?.generationId)
        assertEquals(lease2.leaseToken, registry.getRouteOwner("nexus.text.uppercase")?.leaseToken)
        assertSame(p2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
        assertSame(p2, registry.getProviderForCapability("nexus.text.wordcount")?.second)

        // Late cleanup/events on old lease1 MUST NOT touch lease2's routes or releaseCount
        assertFalse(registry.unregisterProvider(caller, lease1.leaseToken))
        assertTrue(registry.markLeaseDisconnected(lease1.leaseToken, lease1.generationId) is DisconnectResult.Ignored)
        assertFalse(registry.expireRecoveryGrace(lease1.leaseToken, lease1.generationId, 1L))
        registry.handleProviderDied(lease1.leaseToken)

        assertEquals(1, releaseOldCount.get())
        assertEquals(0, releaseNewCount.get())
        assertEquals(LeaseState.ACTIVE, registry.getLeaseState(lease2.leaseToken))
        assertSame(p2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
        assertSame(p2, registry.getProviderForCapability("nexus.text.wordcount")?.second)
    }

    @Test
    fun testIndependentIdempotentLeaseResourceCleanupHandle() {
        val binder = TestIBinder()
        val provider = TestProvider("p", binder)
        val releaseCalls = AtomicInteger(0)
        val handle = LeaseResourceHandle(provider, IBinder.DeathRecipient {}) {
            releaseCalls.incrementAndGet()
        }

        handle.releaseOnce()
        handle.releaseOnce()
        handle.releaseOnce()

        assertTrue(handle.isReleased())
        assertEquals(1, releaseCalls.get())
        assertEquals(1, binder.unlinkCount.get())
    }

    @Test
    fun testUnifiedDisconnectSuspendsRoutesIdempotentlyAndRecoversWithinGrace() {
        var now = 1000L
        val registry = CapabilityRegistry(recoveryGraceMs = 15000L, clockMs = { now })
        val caller = VerifiedCaller(uid = 10142, pid = 100, packageName = "org.nexus.provider.text")
        val releaseCount = AtomicInteger(0)

        val p1 = TestProvider("initial")
        val p2 = TestProvider("recovered")

        val info = registry.registerPreLinkedLease(
            leaseToken = "lease-rec-1",
            caller = caller,
            capabilities = listOf(NexusCapability("nexus.text.uppercase", "Upper", "Upper")),
            providerBinder = p1,
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() }
        )

        // First disconnect signal enters DISCONNECTED and suspends routes WITHOUT releasing ServiceConnection
        val firstDisconnect = registry.markLeaseDisconnected(info.leaseToken, info.generationId, "binderDied")
        assertTrue(firstDisconnect is DisconnectResult.EnteredGracePeriod)
        assertEquals(LeaseState.DISCONNECTED, registry.getLeaseState(info.leaseToken))
        assertNull(registry.getProviderForCapability("nexus.text.uppercase"))
        assertEquals(0, releaseCount.get())

        // Competing second disconnect signal is idempotent
        val secondDisconnect = registry.markLeaseDisconnected(info.leaseToken, info.generationId, "onServiceDisconnected")
        assertTrue(secondDisconnect is DisconnectResult.Ignored)

        // Advance time by 5s (within 15s grace period) and recover
        now += 5000L
        val recovered = registry.tryRecoverLease(
            leaseToken = info.leaseToken,
            expectedGenerationId = info.generationId,
            expectedDisconnectEpoch = (firstDisconnect as DisconnectResult.EnteredGracePeriod).disconnectEpoch,
            newProviderBinder = p2,
            newDeathRecipient = IBinder.DeathRecipient {}
        )

        assertTrue(recovered)
        assertEquals(LeaseState.ACTIVE, registry.getLeaseState(info.leaseToken))
        assertSame(p2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
        assertEquals(1, p1.testBinder.unlinkCount.get())
        assertEquals(0, releaseCount.get())
    }

    @Test
    fun testRecoveryRejectedAfterDeadlineOrTermination() {
        var now = 1000L
        val registry = CapabilityRegistry(recoveryGraceMs = 15000L, clockMs = { now })
        val caller = VerifiedCaller(uid = 10142, pid = 100, packageName = "org.nexus.provider.text")
        val releaseCount = AtomicInteger(0)

        val info = registry.registerPreLinkedLease(
            leaseToken = "lease-expire-1",
            caller = caller,
            capabilities = listOf(NexusCapability("nexus.text.uppercase", "Upper", "Upper")),
            providerBinder = TestProvider("initial"),
            deathRecipient = IBinder.DeathRecipient {},
            leaseDurationMs = 60000L,
            onReleaseResources = { releaseCount.incrementAndGet() }
        )

        val disconnect = registry.markLeaseDisconnected(info.leaseToken, info.generationId)
            as DisconnectResult.EnteredGracePeriod

        // Advance clock past 15s grace deadline
        now += 15001L

        // Even before expireRecoveryGrace runs, late recovery past deadline must fail closed
        assertFalse(
            registry.tryRecoverLease(
                leaseToken = info.leaseToken,
                expectedGenerationId = info.generationId,
                expectedDisconnectEpoch = disconnect.disconnectEpoch,
                newProviderBinder = TestProvider("late"),
                newDeathRecipient = IBinder.DeathRecipient {}
            )
        )

        // Expiring grace terminates and unbinds once
        assertTrue(registry.expireRecoveryGrace(info.leaseToken, info.generationId, disconnect.disconnectEpoch))
        assertEquals(1, releaseCount.get())
        assertNull(registry.getLeaseState(info.leaseToken))
        assertNull(registry.getProviderForCapability("nexus.text.uppercase"))

        // Second expiration and late recovery both fail closed
        assertFalse(registry.expireRecoveryGrace(info.leaseToken, info.generationId, disconnect.disconnectEpoch))
        assertFalse(
            registry.tryRecoverLease(
                leaseToken = info.leaseToken,
                expectedGenerationId = info.generationId,
                newProviderBinder = TestProvider("late2"),
                newDeathRecipient = IBinder.DeathRecipient {}
            )
        )
        assertEquals(1, releaseCount.get())
    }
}
