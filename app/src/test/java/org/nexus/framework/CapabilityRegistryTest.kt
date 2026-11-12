package org.nexus.framework

import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest

class CapabilityRegistryTest {

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
}
