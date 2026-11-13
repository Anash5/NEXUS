package org.nexus.interop.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusTrustConfigTest {

    private val debugSha256Colon =
        "EC:AA:AB:BB:ED:1C:49:E3:60:AA:CE:03:F6:AF:D1:3C:54:52:8E:E4:85:C7:D8:7A:AB:4F:7A:71:49:05:6E:68"
    private val debugSha256Compact =
        "ecaaabbbed1c49e360aace03f6afd13c54528ee485c7d87aab4f7a7149056e68"

    @Test
    fun debugTrustConfigContainsDevelopmentIdentity() {
        val policy = NexusTrustConfig.current
        assertEquals("debug", policy.environmentName)
        assertFalse(policy.isFailClosed())
        assertTrue(policy.isTrustedFrameworkCertificate("org.nexus.framework", debugSha256Colon))
        assertTrue(policy.isTrustedFrameworkCertificate("org.nexus.framework", debugSha256Compact))
    }

    @Test
    fun debugTrustConfigRejectsSpoofedPackageOrUnknownCert() {
        val policy = NexusTrustConfig.current
        assertFalse(policy.isTrustedFrameworkCertificate("org.malicious.hub", debugSha256Colon))
        assertFalse(
            policy.isTrustedFrameworkCertificate(
                "org.nexus.framework",
                "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun releaseTrustConfigIsFailClosedAndRejectsDevelopmentIdentity() {
        val releasePolicy = NexusTrustPolicy.failClosedRelease()
        assertEquals("release", releasePolicy.environmentName)
        assertTrue(releasePolicy.isFailClosed())
        assertFalse(releasePolicy.isTrustedFrameworkCertificate("org.nexus.framework", debugSha256Colon))
        assertFalse(releasePolicy.isTrustedFrameworkCertificate("org.nexus.framework", debugSha256Compact))
        assertFalse(releasePolicy.isTrustedFrameworkCertificate("org.nexus.framework", ""))
    }

    @Test
    fun statusTooManyRequestsConstantIs429() {
        assertEquals(429, NexusResponse.STATUS_TOO_MANY_REQUESTS)
    }
}
