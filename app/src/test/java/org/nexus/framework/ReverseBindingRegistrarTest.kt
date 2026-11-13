package org.nexus.framework

import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.registry.LeaseState
import org.nexus.framework.registry.RegistrationCoordinator
import org.nexus.framework.registry.ReverseBindingRegistrar
import org.nexus.framework.registry.ReverseServiceBindAdapter
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest
import org.nexus.interop.contract.NexusResponse
import java.io.FileDescriptor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReverseBindingRegistrarTest {

    private class FakeIBinder(
        var throwOnLinkToDeath: Boolean = false,
        var alive: Boolean = true
    ) : IBinder {
        val linkCount = AtomicInteger(0)
        val unlinkCount = AtomicInteger(0)
        val recipientRef = AtomicReference<IBinder.DeathRecipient?>(null)

        override fun getInterfaceDescriptor(): String = "org.nexus.interop.contract.INexusProvider"
        override fun pingBinder(): Boolean = alive
        override fun isBinderAlive(): Boolean = alive
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (throwOnLinkToDeath) {
                throw RemoteException("Simulated dead binder during linkToDeath")
            }
            linkCount.incrementAndGet()
            recipientRef.set(recipient)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            unlinkCount.incrementAndGet()
            return recipientRef.compareAndSet(recipient, null)
        }

        fun simulateBinderDied() {
            alive = false
            recipientRef.get()?.binderDied()
        }
    }

    private class FakeProvider(
        private val binder: IBinder,
        private val pingBlockMs: Long = 0L,
        private val pingResult: Boolean = true
    ) : INexusProvider {
        val pingCalls = AtomicInteger(0)
        override fun asBinder(): IBinder = binder
        override fun executeAsync(request: NexusRequest?, callback: INexusResponseCallback?) {}
        override fun ping(): Boolean {
            pingCalls.incrementAndGet()
            if (pingBlockMs > 0L) {
                Thread.sleep(pingBlockMs)
            }
            return pingResult
        }
    }

    private class RecordingBindAdapter(
        private val bindResult: Boolean = true
    ) : ReverseServiceBindAdapter {
        val bindCalls = AtomicInteger(0)
        val unbindCalls = AtomicInteger(0)
        val connections = CopyOnWriteArrayList<ServiceConnection>()
        val lastPackage = AtomicReference<String?>(null)
        val lastClass = AtomicReference<String?>(null)
        val lastConnection = AtomicReference<ServiceConnection?>(null)

        override fun bind(componentPackage: String, componentClass: String, connection: ServiceConnection): Boolean {
            bindCalls.incrementAndGet()
            lastPackage.set(componentPackage)
            lastClass.set(componentClass)
            lastConnection.set(connection)
            connections.add(connection)
            return bindResult
        }

        override fun unbind(connection: ServiceConnection) {
            unbindCalls.incrementAndGet()
        }
    }

    private val providerCaller = VerifiedCaller(
        uid = 10142,
        pid = 2020,
        packageName = "org.nexus.provider.text",
        associatedPackages = listOf("org.nexus.provider.text"),
        certSha256Digests = setOf("ABCD"),
        versionCode = 1L
    )

    private val caps = listOf(
        NexusCapability("nexus.text.uppercase", "Uppercase", "Uppercase text", "1.0.0")
    )

    @Test
    fun testHappyPathReverseBindLinkToDeathPingAndCommit() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 5000L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val fakeBinder = FakeIBinder()
            val fakeProvider = FakeProvider(fakeBinder, pingResult = true)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)
            val crashedPackages = CopyOnWriteArrayList<String>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 5000L,
                binderConverter = { fakeProvider },
                onProviderCrashed = { crashedPackages.add(it) }
            )

            val regId = registrar.initiateReverseRegistration(
                registrationId = "reg-stage2-happy",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            assertNotNull(regId)
            assertEquals(1, adapter.bindCalls.get())
            assertEquals("org.nexus.provider.text", adapter.lastPackage.get())

            adapter.lastConnection.get()!!.onServiceConnected(null, fakeBinder)

            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertTrue(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_SUCCESS, responses[0].statusCode)
            val token = responses[0].resultData!!
            assertFalse(token.isBlank())
            assertEquals(1, fakeBinder.linkCount.get())
            assertEquals(1, fakeProvider.pingCalls.get())
            assertEquals(0, adapter.unbindCalls.get())
            assertEquals(1, registry.getCapabilities(null).size)

            // Transient Binder death suspends routes & enters DISCONNECTED grace without immediate unbind
            fakeBinder.simulateBinderDied()
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(LeaseState.DISCONNECTED, registry.getLeaseState(token))
            assertEquals(listOf("org.nexus.provider.text"), crashedPackages)
            assertEquals(0, adapter.unbindCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testBindServiceReturningFalseAbortsImmediately() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = false)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-bind-false",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            assertTrue(callbackLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, responses[0].statusCode)
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(0, coordinator.activeStagingCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testLinkToDeathFailureFailsClosedAndNeverCommits() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val fakeBinder = FakeIBinder(throwOnLinkToDeath = true)
            val fakeProvider = FakeProvider(fakeBinder, pingResult = true)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                binderConverter = { fakeProvider }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-link-fail",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            adapter.lastConnection.get()!!.onServiceConnected(null, fakeBinder)

            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, responses[0].statusCode)
            assertEquals(0, fakeProvider.pingCalls.get())
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(1, adapter.unbindCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testPingReturningFalseAbortsAndReleasesResources() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val fakeBinder = FakeIBinder()
            val fakeProvider = FakeProvider(fakeBinder, pingResult = false)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                binderConverter = { fakeProvider }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-ping-false",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            adapter.lastConnection.get()!!.onServiceConnected(null, fakeBinder)

            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, responses[0].statusCode)
            assertEquals(1, fakeBinder.linkCount.get())
            assertEquals(1, fakeBinder.unlinkCount.get())
            assertEquals(1, adapter.unbindCalls.get())
            assertEquals(0, registry.getCapabilities(null).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testHangingPingTimesOutWithinBoundedWindow() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val fakeBinder = FakeIBinder()
            val fakeProvider = FakeProvider(fakeBinder, pingBlockMs = 1500L, pingResult = true)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 3000L,
                pingTimeoutMs = 120L,
                binderConverter = { fakeProvider }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-ping-timeout",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            adapter.lastConnection.get()!!.onServiceConnected(null, fakeBinder)

            assertTrue(callbackLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_PROVIDER_DISCONNECTED, responses[0].statusCode)
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(1, adapter.unbindCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testOnNullBindingAbortsWithPermissionDenied() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-null-binding",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            adapter.lastConnection.get()!!.onNullBinding(null)

            assertTrue(callbackLatch.await(1, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_PERMISSION_DENIED, responses[0].statusCode)
            assertEquals(1, adapter.unbindCalls.get())
            assertEquals(0, registry.getCapabilities(null).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testStagingWatchdogExpiresWhenProviderNeverConnects() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry()
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val responses = CopyOnWriteArrayList<NexusResponse>()
            val callbackLatch = CountDownLatch(1)

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 150L
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-watchdog-timeout",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    responses.add(it)
                    callbackLatch.countDown()
                }
            )

            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, responses.size)
            assertFalse(responses[0].isSuccess)
            assertEquals(NexusResponse.STATUS_TIMEOUT, responses[0].statusCode)
            assertEquals(1, adapter.unbindCalls.get())
            assertEquals(0, coordinator.activeStagingCount())
        } finally {
            scope.cancel()
        }
    }

    // --- Stage 3 Recovery & Lifecycle Tests ---

    @Test
    fun testUnifiedDisconnectIdempotencyAndGraceTimerExpirationUnbindsOnce() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 150L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val callbackLatch = CountDownLatch(1)
            val crashedPackages = CopyOnWriteArrayList<String>()
            val tokenRef = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 150L,
                binderConverter = { provider1 },
                onProviderCrashed = { crashedPackages.add(it) }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-grace-expire",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    tokenRef.set(it.resultData)
                    callbackLatch.countDown()
                }
            )

            val conn = adapter.lastConnection.get()!!
            conn.onServiceConnected(null, binder1)
            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
            val token = tokenRef.get()!!

            // Fire BOTH binderDied() and onServiceDisconnected(): grace timer starts once, crash callback once, unbind NOT called yet
            binder1.simulateBinderDied()
            conn.onServiceDisconnected(null)

            assertEquals(LeaseState.DISCONNECTED, registry.getLeaseState(token))
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(1, crashedPackages.size)
            assertEquals(0, adapter.unbindCalls.get())

            // Wait for 150ms grace timer to expire -> lease terminated and ServiceConnection unbound once
            val deadline = System.currentTimeMillis() + 2000L
            while (adapter.unbindCalls.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L)
            }
            assertEquals(1, adapter.unbindCalls.get())
            assertNull(registry.getLeaseState(token))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testRecoveryWithinGracePeriodAuthenticatesPingsAndIgnoresOldBinderDeath() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 5000L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val binder2 = FakeIBinder()
            val provider2 = FakeProvider(binder2, pingResult = true)
            val map = mapOf<IBinder, INexusProvider>(binder1 to provider1, binder2 to provider2)

            val commitLatch = CountDownLatch(1)
            val recoveredLatch = CountDownLatch(1)
            val authCalls = AtomicInteger(0)
            val tokenRef = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 5000L,
                binderConverter = { map.getValue(it) },
                recoveryAuthenticator = { _, _, _, _ ->
                    authCalls.incrementAndGet()
                    true
                },
                onLeaseRecovered = { _, _ -> recoveredLatch.countDown() }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-recover-ok",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    tokenRef.set(it.resultData)
                    commitLatch.countDown()
                }
            )

            val conn = adapter.lastConnection.get()!!
            conn.onServiceConnected(null, binder1)
            assertTrue(commitLatch.await(2, TimeUnit.SECONDS))
            val token = tokenRef.get()!!

            conn.onServiceDisconnected(null)
            assertEquals(LeaseState.DISCONNECTED, registry.getLeaseState(token))
            assertEquals(0, registry.getCapabilities(null).size)

            // OS reconnects on same ServiceConnection with fresh binder2
            conn.onServiceConnected(null, binder2)
            assertTrue(recoveredLatch.await(2, TimeUnit.SECONDS))

            assertEquals(1, authCalls.get())
            assertEquals(LeaseState.ACTIVE, registry.getLeaseState(token))
            assertSame(provider2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
            assertEquals(1, binder2.linkCount.get())
            assertEquals(1, provider2.pingCalls.get())
            assertEquals(0, adapter.unbindCalls.get())

            // Late binderDied() from old binder1 MUST NOT disconnect recovered binder2
            binder1.simulateBinderDied()
            assertEquals(LeaseState.ACTIVE, registry.getLeaseState(token))
            assertSame(provider2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testTimerRaceProtectionLateAuthCannotReactivateExpiredLease() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 100L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val binder2 = FakeIBinder()
            val provider2 = FakeProvider(binder2, pingResult = true)
            val map = mapOf<IBinder, INexusProvider>(binder1 to provider1, binder2 to provider2)

            val commitLatch = CountDownLatch(1)
            val authStartedLatch = CountDownLatch(1)
            val allowAuthFinishLatch = CountDownLatch(1)
            val recoveredCalled = AtomicInteger(0)
            val tokenRef = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 100L,
                binderConverter = { map.getValue(it) },
                recoveryAuthenticator = { _, _, _, _ ->
                    authStartedLatch.countDown()
                    allowAuthFinishLatch.await(2, TimeUnit.SECONDS)
                    true
                },
                onLeaseRecovered = { _, _ -> recoveredCalled.incrementAndGet() }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-timer-race",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    tokenRef.set(it.resultData)
                    commitLatch.countDown()
                }
            )

            val conn = adapter.lastConnection.get()!!
            conn.onServiceConnected(null, binder1)
            assertTrue(commitLatch.await(2, TimeUnit.SECONDS))
            val token = tokenRef.get()!!

            conn.onServiceDisconnected(null)

            // Start recovery before timer expires, then hold auth until grace timer expires
            conn.onServiceConnected(null, binder2)
            assertTrue(authStartedLatch.await(1, TimeUnit.SECONDS))

            // Wait for 100ms recovery grace timer to terminate the lease
            val waitDeadline = System.currentTimeMillis() + 2000L
            while (registry.getLeaseState(token) != null && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(20L)
            }
            assertNull(registry.getLeaseState(token))

            // Now let late authentication complete -> must lose race and clean up without reactivating
            allowAuthFinishLatch.countDown()
            Thread.sleep(150L)

            assertEquals(0, recoveredCalled.get())
            assertNull(registry.getLeaseState(token))
            assertEquals(0, registry.getCapabilities(null).size)
            // If ping/link ran on binder2 before failing tryRecoverLease, binder2 must be unlinked
            assertEquals(binder2.linkCount.get(), binder2.unlinkCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testRecoveryAuthenticationFailureTerminatesLeaseFailClosed() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 5000L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val binder2 = FakeIBinder()
            val provider2 = FakeProvider(binder2, pingResult = true)
            val map = mapOf<IBinder, INexusProvider>(binder1 to provider1, binder2 to provider2)

            val commitLatch = CountDownLatch(1)
            val tokenRef = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 5000L,
                binderConverter = { map.getValue(it) },
                recoveryAuthenticator = { _, _, _, _ -> false }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-auth-fail-recover",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    tokenRef.set(it.resultData)
                    commitLatch.countDown()
                }
            )

            val conn = adapter.lastConnection.get()!!
            conn.onServiceConnected(null, binder1)
            assertTrue(commitLatch.await(2, TimeUnit.SECONDS))
            val token = tokenRef.get()!!

            conn.onServiceDisconnected(null)
            conn.onServiceConnected(null, binder2)

            val waitDeadline = System.currentTimeMillis() + 2000L
            while (adapter.unbindCalls.get() == 0 && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(20L)
            }
            assertEquals(1, adapter.unbindCalls.get())
            assertNull(registry.getLeaseState(token))
            assertEquals(0, registry.getCapabilities(null).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testPermanentOnBindingDiedImmediatelyTerminatesAndUnbinds() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 5000L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val commitLatch = CountDownLatch(1)
            val crashed = AtomicInteger(0)
            val tokenRef = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 5000L,
                binderConverter = { provider1 },
                onProviderCrashed = { crashed.incrementAndGet() }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-perm-die",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    tokenRef.set(it.resultData)
                    commitLatch.countDown()
                }
            )

            val conn = adapter.lastConnection.get()!!
            conn.onServiceConnected(null, binder1)
            assertTrue(commitLatch.await(2, TimeUnit.SECONDS))
            val token = tokenRef.get()!!

            conn.onBindingDied(null)

            assertNull(registry.getLeaseState(token))
            assertEquals(0, registry.getCapabilities(null).size)
            assertEquals(1, adapter.unbindCalls.get())
            assertEquals(1, crashed.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testNewRegistrationReplacesDisconnectedLeaseAndLateRecoveryIsBlocked() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val registry = CapabilityRegistry(recoveryGraceMs = 5000L)
            val coordinator = RegistrationCoordinator()
            val adapter = RecordingBindAdapter(bindResult = true)
            val binder1 = FakeIBinder()
            val provider1 = FakeProvider(binder1, pingResult = true)
            val binder1Late = FakeIBinder()
            val provider1Late = FakeProvider(binder1Late, pingResult = true)
            val binder2 = FakeIBinder()
            val provider2 = FakeProvider(binder2, pingResult = true)
            val map = mapOf<IBinder, INexusProvider>(
                binder1 to provider1,
                binder1Late to provider1Late,
                binder2 to provider2
            )

            val commit1Latch = CountDownLatch(1)
            val commit2Latch = CountDownLatch(1)
            val token1Ref = AtomicReference<String?>()
            val token2Ref = AtomicReference<String?>()

            val registrar = ReverseBindingRegistrar(
                registry = registry,
                coordinator = coordinator,
                scope = scope,
                bindAdapter = adapter,
                stagingTimeoutMs = 2000L,
                pingTimeoutMs = 500L,
                recoveryGraceMs = 5000L,
                binderConverter = { map.getValue(it) }
            )

            registrar.initiateReverseRegistration(
                registrationId = "reg-replace-1",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    token1Ref.set(it.resultData)
                    commit1Latch.countDown()
                }
            )
            val conn1 = adapter.connections[0]
            conn1.onServiceConnected(null, binder1)
            assertTrue(commit1Latch.await(2, TimeUnit.SECONDS))
            val token1 = token1Ref.get()!!

            // First lease disconnects and enters grace
            conn1.onServiceDisconnected(null)
            assertEquals(LeaseState.DISCONNECTED, registry.getLeaseState(token1))

            // Provider reconnects via fresh registration -> atomically supersedes token1
            registrar.initiateReverseRegistration(
                registrationId = "reg-replace-2",
                caller = providerCaller,
                componentPackage = "org.nexus.provider.text",
                componentClass = "org.nexus.provider.text.service.TextProviderService",
                capabilities = caps,
                leaseDurationMs = 60000L,
                onTerminalCallback = {
                    token2Ref.set(it.resultData)
                    commit2Latch.countDown()
                }
            )
            val conn2 = adapter.connections[1]
            conn2.onServiceConnected(null, binder2)
            assertTrue(commit2Latch.await(2, TimeUnit.SECONDS))
            val token2 = token2Ref.get()!!

            assertNotEquals(token1, token2)
            assertNull(registry.getLeaseState(token1))
            assertEquals(LeaseState.ACTIVE, registry.getLeaseState(token2))
            assertSame(provider2, registry.getProviderForCapability("nexus.text.uppercase")?.second)

            // Late onServiceConnected on superseded conn1 MUST NOT reactivate token1 or overwrite token2
            conn1.onServiceConnected(null, binder1Late)
            Thread.sleep(100L)

            assertNull(registry.getLeaseState(token1))
            assertEquals(LeaseState.ACTIVE, registry.getLeaseState(token2))
            assertSame(provider2, registry.getProviderForCapability("nexus.text.uppercase")?.second)
        } finally {
            scope.cancel()
        }
    }
}
