package org.nexus.provider.text.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import org.nexus.interop.contract.NexusTrustConfig
import org.nexus.interop.contract.NexusTrustPolicy
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional authenticator for the Provider service (Stage 2).
 *
 * Authenticates the NEXUS Framework both:
 * 1. Outbound before sending registerProviderAsync() to a bound Hub service.
 * 2. Inbound inside INexusProvider.Stub Binder methods (ping(), executeAsync()),
 *    never inside Service.onBind().
 */
class FrameworkAuthenticator(
    private val lookup: PackageIdentityLookup,
    private val trustPolicyProvider: () -> NexusTrustPolicy = { NexusTrustConfig.current }
) {

    constructor(
        context: Context,
        trustPolicyProvider: () -> NexusTrustPolicy = { NexusTrustConfig.current }
    ) : this(AndroidPackageIdentityLookup(context), trustPolicyProvider)

    sealed class AuthResult {
        data class Trusted(
            val uid: Int,
            val packageName: String,
            val certSha256Digests: Set<String>
        ) : AuthResult()

        data class Untrusted(val reason: String) : AuthResult()
    }

    data class PackageVersionInfo(
        val uid: Int,
        val versionCode: Long
    )

    interface PackageIdentityLookup {
        fun getPackagesForUid(uid: Int): List<String>
        fun getPackageVersion(packageName: String): PackageVersionInfo?
        fun getPackageCertSha256Digests(packageName: String): Set<String>
    }

    private data class AuthCacheKey(val uid: Int, val packageName: String)
    private data class CachedCertIdentity(
        val versionCode: Long,
        val certSha256Digests: Set<String>
    )

    private val authCache = ConcurrentHashMap<AuthCacheKey, CachedCertIdentity>()

    /**
     * Authenticates the remote Hub package before sending registration requests.
     */
    fun verifyBoundHub(componentPackage: String?): AuthResult {
        val policy = trustPolicyProvider()
        if (componentPackage.isNullOrBlank()) {
            return AuthResult.Untrusted("Bound Hub component package is null or blank")
        }
        if (componentPackage != policy.frameworkPackage) {
            return AuthResult.Untrusted(
                "Unexpected Hub package [$componentPackage]; expected [${policy.frameworkPackage}]"
            )
        }
        return verifyPackageAndCertificate(expectedUid = null, packageName = componentPackage, policy = policy)
    }

    /**
     * Authenticates an incoming Binder transaction caller inside INexusProvider.Stub methods.
     */
    fun verifyIncomingBinderCaller(callingUid: Int = Binder.getCallingUid()): AuthResult {
        val policy = trustPolicyProvider()
        val packages = try {
            lookup.getPackagesForUid(callingUid)
        } catch (e: Throwable) {
            return AuthResult.Untrusted("Failed to resolve packages for calling UID [$callingUid]: ${e.message}")
        }

        if (packages.isEmpty()) {
            return AuthResult.Untrusted("No package found for calling UID [$callingUid]")
        }
        if (packages.size > 1) {
            return AuthResult.Untrusted(
                "Ambiguous shared UID [$callingUid] with multiple packages $packages rejected (fail-closed)"
            )
        }

        val callerPackage = packages[0]
        if (callerPackage != policy.frameworkPackage) {
            return AuthResult.Untrusted(
                "Unauthorized caller package [$callerPackage]; expected [${policy.frameworkPackage}]"
            )
        }

        return verifyPackageAndCertificate(expectedUid = callingUid, packageName = callerPackage, policy = policy)
    }

    fun invalidatePackage(packageName: String) {
        val iterator = authCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.packageName == packageName) {
                iterator.remove()
            }
        }
    }

    fun clearCache() {
        authCache.clear()
    }

    internal fun cacheSize(): Int = authCache.size

    private fun verifyPackageAndCertificate(
        expectedUid: Int?,
        packageName: String,
        policy: NexusTrustPolicy
    ): AuthResult {
        if (policy.isFailClosed()) {
            return AuthResult.Untrusted(
                "Trust policy [${policy.environmentName}] is fail-closed with no pinned Framework certificates"
            )
        }

        val versionInfo = try {
            lookup.getPackageVersion(packageName)
        } catch (e: Throwable) {
            invalidatePackage(packageName)
            return AuthResult.Untrusted("Failed to inspect package [$packageName]: ${e.message}")
        } ?: run {
            invalidatePackage(packageName)
            return AuthResult.Untrusted("Package [$packageName] is not installed or visible")
        }

        if (expectedUid != null && versionInfo.uid != expectedUid) {
            invalidatePackage(packageName)
            return AuthResult.Untrusted(
                "UID mismatch for [$packageName]: expected [$expectedUid], resolved [${versionInfo.uid}]"
            )
        }

        val cacheKey = AuthCacheKey(versionInfo.uid, packageName)
        val cached = authCache[cacheKey]
        val digests = if (cached != null && cached.versionCode == versionInfo.versionCode) {
            cached.certSha256Digests
        } else {
            val freshDigests = try {
                lookup.getPackageCertSha256Digests(packageName)
                    .map(NexusTrustPolicy::normalizeSha256)
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (e: Throwable) {
                authCache.remove(cacheKey)
                return AuthResult.Untrusted("Failed to extract signing certificates for [$packageName]: ${e.message}")
            }

            if (freshDigests.isEmpty()) {
                authCache.remove(cacheKey)
                return AuthResult.Untrusted("Package [$packageName] has no signing certificates")
            }

            authCache[cacheKey] = CachedCertIdentity(versionInfo.versionCode, freshDigests)
            freshDigests
        }

        val trusted = digests.any { digest ->
            policy.isTrustedFrameworkCertificate(packageName, digest)
        }
        if (!trusted) {
            return AuthResult.Untrusted(
                "Package [$packageName] signing certificate is not trusted under [${policy.environmentName}] policy"
            )
        }

        return AuthResult.Trusted(
            uid = versionInfo.uid,
            packageName = packageName,
            certSha256Digests = digests
        )
    }

    private class AndroidPackageIdentityLookup(private val context: Context) : PackageIdentityLookup {
        override fun getPackagesForUid(uid: Int): List<String> {
            return context.packageManager?.getPackagesForUid(uid)?.toList() ?: emptyList()
        }

        override fun getPackageVersion(packageName: String): PackageVersionInfo? {
            val pm = context.packageManager ?: return null
            val info = pm.getPackageInfo(packageName, 0) ?: return null
            val uid = info.applicationInfo?.uid ?: return null
            return PackageVersionInfo(uid = uid, versionCode = getLongVersionCode(info))
        }

        override fun getPackageCertSha256Digests(packageName: String): Set<String> {
            val pm = context.packageManager ?: return emptySet()
            val info = getPackageInfoWithSignatures(pm, packageName) ?: return emptySet()
            return extractSha256Digests(info)
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun getPackageInfoWithSignatures(pm: PackageManager, packageName: String): PackageInfo? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }

        @Suppress("DEPRECATION")
        fun getLongVersionCode(info: PackageInfo): Long {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }

        @Suppress("DEPRECATION")
        fun extractSha256Digests(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo ?: return emptySet()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                info.signatures
            } ?: return emptySet()

            val digest = MessageDigest.getInstance("SHA-256")
            return signatures.map { sig ->
                val bytes = digest.digest(sig.toByteArray())
                bytes.joinToString("") { "%02X".format(it) }
            }.toSet()
        }
    }
}
