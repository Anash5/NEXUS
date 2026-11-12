package org.nexus.framework

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nexus.framework.security.CallerIdentityVerifier
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller

class CallerIdentityVerifierTest {

    // Dummy context (not used for pure isAuthorizedToRegisterCapability checks)
    private val dummyContext = object : android.content.ContextWrapper(null) {}
    private val verifier = CallerIdentityVerifier(dummyContext)

    @Test
    fun testDesignatedTextProviderAuthorized() {
        val caller = VerifiedCaller(
            uid = 10142,
            pid = 200,
            packageName = "org.nexus.provider.text",
            associatedPackages = listOf("org.nexus.provider.text"),
            isAmbiguous = false,
            isPlatformOrSystem = false
        )
        assertTrue(verifier.isAuthorizedToRegisterCapability(caller, "nexus.text.uppercase"))
        assertTrue(verifier.isAuthorizedToRegisterCapability(caller, "nexus.text.wordcount"))
    }

    @Test
    fun testSystemUidCannotBypassTextProviderNamespace() {
        // System UID app (e.g. systemui or system server) must NOT hijack or squat nexus.text.* namespace (Correction 6)
        val systemCaller = VerifiedCaller(
            uid = 1000,
            pid = 50,
            packageName = "com.android.systemui",
            associatedPackages = listOf("com.android.systemui"),
            isAmbiguous = false,
            isPlatformOrSystem = true
        )
        assertFalse(verifier.isAuthorizedToRegisterCapability(systemCaller, "nexus.text.uppercase"))
        assertFalse(verifier.isAuthorizedToRegisterCapability(systemCaller, "nexus.text.reverse"))
    }

    @Test
    fun testAmbiguousSharedUidFailsClosed() {
        // When multiple packages share a UID and one is untrusted, must fail closed (Correction 5)
        val ambiguousCaller = VerifiedCaller(
            uid = 10500,
            pid = 300,
            packageName = "shared.uid.10500",
            associatedPackages = listOf("org.nexus.provider.text", "org.malicious.squatter"),
            isAmbiguous = true,
            isPlatformOrSystem = false
        )
        assertFalse(verifier.isAuthorizedToRegisterCapability(ambiguousCaller, "nexus.text.uppercase"))
    }

    @Test
    fun testUnanimousSharedUidCanBeExplicitlyVerified() {
        // When all packages sharing the UID strictly qualify, registration is permitted
        val unambiguousSharedCaller = VerifiedCaller(
            uid = 10500,
            pid = 300,
            packageName = "shared.uid.10500",
            associatedPackages = listOf("org.nexus.provider.text"),
            isAmbiguous = true,
            isPlatformOrSystem = false
        )
        assertTrue(verifier.isAuthorizedToRegisterCapability(unambiguousSharedCaller, "nexus.text.uppercase"))
    }

    @Test
    fun testCustomCapabilityStrictNamespacePrefix() {
        val caller = VerifiedCaller(
            uid = 10200,
            pid = 400,
            packageName = "com.custom.converter",
            associatedPackages = listOf("com.custom.converter"),
            isAmbiguous = false,
            isPlatformOrSystem = false
        )
        assertTrue(verifier.isAuthorizedToRegisterCapability(caller, "com.custom.converter.json2xml"))
        assertFalse(verifier.isAuthorizedToRegisterCapability(caller, "com.other.package.capability"))
        assertFalse(verifier.isAuthorizedToRegisterCapability(caller, "nexus.text.uppercase"))
    }

    @Test
    fun testSystemNamespaceRequiresSystemUid() {
        val normalCaller = VerifiedCaller(
            uid = 10200,
            pid = 400,
            packageName = "com.custom.converter",
            isPlatformOrSystem = false
        )
        val systemCaller = VerifiedCaller(
            uid = 1000,
            pid = 50,
            packageName = "android",
            isPlatformOrSystem = true
        )
        assertFalse(verifier.isAuthorizedToRegisterCapability(normalCaller, "nexus.system.telemetry"))
        assertTrue(verifier.isAuthorizedToRegisterCapability(systemCaller, "nexus.system.telemetry"))
    }
}
