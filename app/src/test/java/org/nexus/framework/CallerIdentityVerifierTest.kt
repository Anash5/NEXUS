package org.nexus.framework

import org.junit.Assert.*
import org.junit.Test
import org.nexus.framework.security.CallerIdentityVerifier
import org.nexus.framework.security.CallerIdentityVerifier.CachedSigningIdentity
import org.nexus.framework.security.CallerIdentityVerifier.PackageSigningLookup
import org.nexus.framework.security.CallerIdentityVerifier.RegistrationAuthResult
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.NexusTrustConfig
import org.nexus.interop.contract.NexusTrustPolicy

class CallerIdentityVerifierTest {

    // Dummy context (not used for pure isAuthorizedToRegisterCapability / authorizeRegistration checks)
    private val dummyContext = object : android.content.ContextWrapper(null) {}
    private val verifier = CallerIdentityVerifier(dummyContext)

    private val debugCertSha256 = NexusTrustPolicy.normalizeSha256(
        "EC:AA:AB:BB:ED:1C:49:E3:60:AA:CE:03:F6:AF:D1:3C:54:52:8E:E4:85:C7:D8:7A:AB:4F:7A:71:49:05:6E:68"
    )
    private val thirdPartyCertSha256 = NexusTrustPolicy.normalizeSha256(
        "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF"
    )

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

    // --- Stage 2 Tests ---

    @Test
    fun testAuthorizeRegistrationFirstPartyTrustedDebugCertPasses() {
        val caller = VerifiedCaller(
            uid = 10142,
            pid = 200,
            packageName = "org.nexus.provider.text",
            associatedPackages = listOf("org.nexus.provider.text"),
            isAmbiguous = false,
            isPlatformOrSystem = false,
            certSha256Digests = setOf(debugCertSha256),
            versionCode = 1L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "org.nexus.provider.text",
            componentClass = "org.nexus.provider.text.service.TextProviderService",
            capabilityIds = listOf("nexus.text.uppercase", "nexus.text.reverse"),
            trustPolicy = NexusTrustConfig.current
        )
        assertTrue(result is RegistrationAuthResult.Authorized)
    }

    @Test
    fun testAuthorizeRegistrationFirstPartyUntrustedCertFailsClosed() {
        val caller = VerifiedCaller(
            uid = 10142,
            pid = 200,
            packageName = "org.nexus.provider.text",
            associatedPackages = listOf("org.nexus.provider.text"),
            isAmbiguous = false,
            isPlatformOrSystem = false,
            certSha256Digests = setOf(thirdPartyCertSha256),
            versionCode = 1L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "org.nexus.provider.text",
            componentClass = "org.nexus.provider.text.service.TextProviderService",
            capabilityIds = listOf("nexus.text.uppercase"),
            trustPolicy = NexusTrustConfig.current
        )
        assertTrue(result is RegistrationAuthResult.Denied)
    }

    @Test
    fun testAuthorizeRegistrationFirstPartyFailClosedReleasePolicyRejectsDebugCert() {
        val caller = VerifiedCaller(
            uid = 10142,
            pid = 200,
            packageName = "org.nexus.provider.text",
            associatedPackages = listOf("org.nexus.provider.text"),
            certSha256Digests = setOf(debugCertSha256),
            versionCode = 1L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "org.nexus.provider.text",
            componentClass = "org.nexus.provider.text.service.TextProviderService",
            capabilityIds = listOf("nexus.text.uppercase"),
            trustPolicy = NexusTrustPolicy.failClosedRelease()
        )
        assertTrue(result is RegistrationAuthResult.Denied)
    }

    @Test
    fun testAuthorizeRegistrationThirdPartyOwnCertPassesWithoutNexusKey() {
        val caller = VerifiedCaller(
            uid = 10300,
            pid = 500,
            packageName = "com.acme.translator",
            associatedPackages = listOf("com.acme.translator"),
            certSha256Digests = setOf(thirdPartyCertSha256),
            versionCode = 4L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "com.acme.translator",
            componentClass = "com.acme.translator.TranslateService",
            capabilityIds = listOf("com.acme.translator.en2fr"),
            trustPolicy = NexusTrustConfig.current
        )
        assertTrue(result is RegistrationAuthResult.Authorized)
    }

    @Test
    fun testAuthorizeRegistrationThirdPartyWithoutCertFailsClosed() {
        val caller = VerifiedCaller(
            uid = 10300,
            pid = 500,
            packageName = "com.acme.translator",
            associatedPackages = listOf("com.acme.translator"),
            certSha256Digests = emptySet(),
            versionCode = 4L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "com.acme.translator",
            componentClass = "com.acme.translator.TranslateService",
            capabilityIds = listOf("com.acme.translator.en2fr"),
            trustPolicy = NexusTrustConfig.current
        )
        assertTrue(result is RegistrationAuthResult.Denied)
    }

    @Test
    fun testAuthorizeRegistrationRejectsComponentOwnershipMismatch() {
        val caller = VerifiedCaller(
            uid = 10999,
            pid = 999,
            packageName = "org.malicious.app",
            associatedPackages = listOf("org.malicious.app"),
            certSha256Digests = setOf(thirdPartyCertSha256),
            versionCode = 1L
        )
        val result = verifier.authorizeRegistration(
            caller = caller,
            componentPackage = "org.nexus.provider.text",
            componentClass = "org.nexus.provider.text.service.TextProviderService",
            capabilityIds = listOf("nexus.text.uppercase"),
            trustPolicy = NexusTrustConfig.current
        )
        assertTrue(result is RegistrationAuthResult.Denied)
    }

    @Test
    fun testCertificateCacheInvalidationAndVersionCodeChange() {
        var currentVersion = 1L
        var currentCert = debugCertSha256
        var lookupCount = 0

        val lookup = object : PackageSigningLookup {
            override fun getSigningIdentity(packageName: String): CachedSigningIdentity? {
                lookupCount++
                return CachedSigningIdentity(currentVersion, setOf(currentCert))
            }
        }

        val cachingVerifier = CallerIdentityVerifier(
            context = dummyContext,
            trustPolicyProvider = { NexusTrustConfig.current },
            signingLookupOverride = lookup
        )

        val first = cachingVerifier.resolveIdentityForUid(10142, 200, listOf("org.nexus.provider.text"))
        assertEquals(setOf(debugCertSha256), first.certSha256Digests)
        assertEquals(1, cachingVerifier.cacheSize())

        // Reinstall/rotate cert at same versionCode: cached until invalidated
        currentCert = thirdPartyCertSha256
        val cachedRead = cachingVerifier.resolveIdentityForUid(10142, 200, listOf("org.nexus.provider.text"))
        assertEquals(setOf(debugCertSha256), cachedRead.certSha256Digests)

        // Explicit package replacement invalidation clears cache and forces fresh cert check
        cachingVerifier.invalidatePackage("org.nexus.provider.text")
        assertEquals(0, cachingVerifier.cacheSize())

        val refreshedAfterInvalidate = cachingVerifier.resolveIdentityForUid(10142, 200, listOf("org.nexus.provider.text"))
        assertEquals(setOf(thirdPartyCertSha256), refreshedAfterInvalidate.certSha256Digests)

        // Version code bump automatically invalidates stale cached cert
        currentVersion = 2L
        currentCert = debugCertSha256
        val refreshedOnVersionBump = cachingVerifier.resolveIdentityForUid(10142, 200, listOf("org.nexus.provider.text"))
        assertEquals(setOf(debugCertSha256), refreshedOnVersionBump.certSha256Digests)
        assertTrue(lookupCount >= 3)
    }
}
