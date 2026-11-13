package org.nexus.interop.contract

/**
 * Debug-variant trust configuration.
 * Only compiled into debug builds; contains the local debug.keystore SHA-256 identity.
 */
object NexusTrustConfig {
    private const val DEBUG_KEYSTORE_SHA256 =
        "EC:AA:AB:BB:ED:1C:49:E3:60:AA:CE:03:F6:AF:D1:3C:54:52:8E:E4:85:C7:D8:7A:AB:4F:7A:71:49:05:6E:68"

    val current: NexusTrustPolicy = NexusTrustPolicy(
        environmentName = "debug",
        frameworkPackage = NexusTrustPolicy.FRAMEWORK_PACKAGE,
        allowedFrameworkCertSha256 = setOf(DEBUG_KEYSTORE_SHA256)
    )
}
