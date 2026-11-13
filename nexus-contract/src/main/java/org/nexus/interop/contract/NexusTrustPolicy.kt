package org.nexus.interop.contract

/**
 * Trust policy describing the expected identity and pinned signing certificates
 * for the NEXUS Framework Hub.
 *
 * Production/release configurations MUST NOT contain development/debug fingerprints
 * and must fail closed when no release fingerprint has been configured.
 */
data class NexusTrustPolicy(
    val environmentName: String,
    val frameworkPackage: String = FRAMEWORK_PACKAGE,
    val allowedFrameworkCertSha256: Set<String>
) {
    private val normalizedAllowedDigests: Set<String> =
        allowedFrameworkCertSha256.map(::normalizeSha256).filter { it.isNotEmpty() }.toSet()

    fun isTrustedFrameworkCertificate(packageName: String, certSha256: String): Boolean {
        if (packageName != frameworkPackage) return false
        if (normalizedAllowedDigests.isEmpty()) return false // Fail closed when unpinned
        val normalized = normalizeSha256(certSha256)
        return normalized.isNotEmpty() && normalizedAllowedDigests.contains(normalized)
    }

    fun isFailClosed(): Boolean = normalizedAllowedDigests.isEmpty()

    companion object {
        const val FRAMEWORK_PACKAGE = "org.nexus.framework"

        fun normalizeSha256(fingerprint: String): String =
            fingerprint.replace(":", "").replace(" ", "").trim().uppercase()

        /**
         * Fail-closed release trust policy.
         * Remains unpinned (rejecting all certificates, including debug keys) until
         * the finalized production signing-certificate SHA-256 fingerprint is supplied.
         */
        fun failClosedRelease(
            frameworkPackage: String = FRAMEWORK_PACKAGE,
            releaseCertSha256: Set<String> = emptySet()
        ): NexusTrustPolicy = NexusTrustPolicy(
            environmentName = "release",
            frameworkPackage = frameworkPackage,
            allowedFrameworkCertSha256 = releaseCertSha256
        )
    }
}
