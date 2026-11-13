package org.nexus.provider.text

import org.junit.Assert.*
import org.junit.Test
import org.nexus.interop.contract.NexusTrustConfig
import org.nexus.interop.contract.NexusTrustPolicy
import org.nexus.provider.text.security.FrameworkAuthenticator
import org.nexus.provider.text.security.FrameworkAuthenticator.AuthResult
import org.nexus.provider.text.security.FrameworkAuthenticator.PackageIdentityLookup
import org.nexus.provider.text.security.FrameworkAuthenticator.PackageVersionInfo

class FrameworkAuthenticatorTest {

    private val debugCert =
        "EC:AA:AB:BB:ED:1C:49:E3:60:AA:CE:03:F6:AF:D1:3C:54:52:8E:E4:85:C7:D8:7A:AB:4F:7A:71:49:05:6E:68"
    private val untrustedCert =
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

    private class FakeLookup(
        var uidToPackages: MutableMap<Int, List<String>> = mutableMapOf(
            10100 to listOf("org.nexus.framework")
        ),
        var pkgVersions: MutableMap<String, PackageVersionInfo> = mutableMapOf(
            "org.nexus.framework" to PackageVersionInfo(uid = 10100, versionCode = 1L)
        ),
        var pkgCerts: MutableMap<String, Set<String>> = mutableMapOf(
            "org.nexus.framework" to setOf(
                "EC:AA:AB:BB:ED:1C:49:E3:60:AA:CE:03:F6:AF:D1:3C:54:52:8E:E4:85:C7:D8:7A:AB:4F:7A:71:49:05:6E:68"
            )
        )
    ) : PackageIdentityLookup {
        var certLookupCount = 0

        override fun getPackagesForUid(uid: Int): List<String> = uidToPackages[uid] ?: emptyList()

        override fun getPackageVersion(packageName: String): PackageVersionInfo? = pkgVersions[packageName]

        override fun getPackageCertSha256Digests(packageName: String): Set<String> {
            certLookupCount++
            return pkgCerts[packageName] ?: emptySet()
        }
    }

    @Test
    fun testTrustedDebugFrameworkOutboundAndInboundPass() {
        val lookup = FakeLookup()
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustConfig.current }

        val outbound = authenticator.verifyBoundHub("org.nexus.framework")
        assertTrue(outbound is AuthResult.Trusted)

        val inbound = authenticator.verifyIncomingBinderCaller(10100)
        assertTrue(inbound is AuthResult.Trusted)
        assertEquals(1, authenticator.cacheSize())
    }

    @Test
    fun testUntrustedCertificateRejectedOutboundAndInbound() {
        val lookup = FakeLookup(
            pkgCerts = mutableMapOf("org.nexus.framework" to setOf(untrustedCert))
        )
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustConfig.current }

        assertTrue(authenticator.verifyBoundHub("org.nexus.framework") is AuthResult.Untrusted)
        assertTrue(authenticator.verifyIncomingBinderCaller(10100) is AuthResult.Untrusted)
    }

    @Test
    fun testSpoofedPackageRejected() {
        val lookup = FakeLookup(
            uidToPackages = mutableMapOf(10999 to listOf("org.malicious.hub")),
            pkgVersions = mutableMapOf("org.malicious.hub" to PackageVersionInfo(10999, 1L)),
            pkgCerts = mutableMapOf("org.malicious.hub" to setOf(debugCert))
        )
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustConfig.current }

        assertTrue(authenticator.verifyBoundHub("org.malicious.hub") is AuthResult.Untrusted)
        assertTrue(authenticator.verifyBoundHub(null) is AuthResult.Untrusted)
        assertTrue(authenticator.verifyIncomingBinderCaller(10999) is AuthResult.Untrusted)
    }

    @Test
    fun testAmbiguousSharedUidAndEmptyUidFailClosed() {
        val lookup = FakeLookup(
            uidToPackages = mutableMapOf(
                10500 to listOf("org.nexus.framework", "org.evil.companion"),
                10501 to emptyList()
            )
        )
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustConfig.current }

        val ambiguous = authenticator.verifyIncomingBinderCaller(10500)
        assertTrue(ambiguous is AuthResult.Untrusted)
        assertTrue((ambiguous as AuthResult.Untrusted).reason.contains("Ambiguous"))

        assertTrue(authenticator.verifyIncomingBinderCaller(10501) is AuthResult.Untrusted)
    }

    @Test
    fun testFailClosedReleasePolicyRejectsDebugCertificate() {
        val lookup = FakeLookup()
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustPolicy.failClosedRelease() }

        val outbound = authenticator.verifyBoundHub("org.nexus.framework")
        val inbound = authenticator.verifyIncomingBinderCaller(10100)

        assertTrue(outbound is AuthResult.Untrusted)
        assertTrue(inbound is AuthResult.Untrusted)
    }

    @Test
    fun testCertificateCacheHitAndInvalidationOnReplaceOrVersionBump() {
        val lookup = FakeLookup()
        val authenticator = FrameworkAuthenticator(lookup) { NexusTrustConfig.current }

        // First call extracts certs and populates cache
        assertTrue(authenticator.verifyIncomingBinderCaller(10100) is AuthResult.Trusted)
        assertEquals(1, lookup.certLookupCount)
        assertEquals(1, authenticator.cacheSize())

        // Second call at same versionCode hits cache (no extra cert extraction)
        assertTrue(authenticator.verifyIncomingBinderCaller(10100) is AuthResult.Trusted)
        assertEquals(1, lookup.certLookupCount)

        // Simulate APK replacement at same versionCode with untrusted key + package broadcast invalidation
        lookup.pkgCerts["org.nexus.framework"] = setOf(untrustedCert)
        authenticator.invalidatePackage("org.nexus.framework")
        assertEquals(0, authenticator.cacheSize())

        assertTrue(authenticator.verifyIncomingBinderCaller(10100) is AuthResult.Untrusted)
        assertEquals(2, lookup.certLookupCount)

        // Simulate upgrade (versionCode bump back to trusted debug cert) auto-refreshes cache
        lookup.pkgVersions["org.nexus.framework"] = PackageVersionInfo(uid = 10100, versionCode = 2L)
        lookup.pkgCerts["org.nexus.framework"] = setOf(debugCert)
        assertTrue(authenticator.verifyIncomingBinderCaller(10100) is AuthResult.Trusted)
        assertEquals(3, lookup.certLookupCount)
    }
}
