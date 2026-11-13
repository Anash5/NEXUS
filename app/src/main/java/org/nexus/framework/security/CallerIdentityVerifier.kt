package org.nexus.framework.security

import android.content.ComponentName
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
 * Validates and resolves caller identity using the Linux kernel Binder subsystem
 * and Android PackageManager signing certificates.
 * Never trusts package names or identities passed in Parcelable payload bodies.
 */
class CallerIdentityVerifier(
    private val context: Context,
    private val trustPolicyProvider: () -> NexusTrustPolicy = { NexusTrustConfig.current }
) {

    data class VerifiedCaller(
        val uid: Int,
        val pid: Int,
        val packageName: String,
        val associatedPackages: List<String> = emptyList(),
        val isAmbiguous: Boolean = false,
        val isPlatformOrSystem: Boolean = false,
        val certSha256Digests: Set<String> = emptySet(),
        val versionCode: Long = 0L
    )

    sealed class RegistrationAuthResult {
        data class Authorized(val verifiedCertSha256s: Set<String>) : RegistrationAuthResult()
        data class Denied(val reason: String) : RegistrationAuthResult()
    }

    interface PackageSigningLookup {
        fun getSigningIdentity(packageName: String): CachedSigningIdentity?
    }

    data class CachedSigningIdentity(
        val versionCode: Long,
        val certSha256Digests: Set<String>
    )

    private data class AuthCacheKey(val uid: Int, val packageName: String)

    private val authCache = ConcurrentHashMap<AuthCacheKey, CachedSigningIdentity>()

    private var signingLookupOverride: PackageSigningLookup? = null

    internal constructor(
        context: Context,
        trustPolicyProvider: () -> NexusTrustPolicy,
        signingLookupOverride: PackageSigningLookup
    ) : this(context, trustPolicyProvider) {
        this.signingLookupOverride = signingLookupOverride
    }

    /**
     * Resolves the verified calling identity for the current Binder transaction.
     * Accurately handles multi-package shared UIDs without arbitrary selection.
     */
    fun resolveCurrentCallingIdentity(): VerifiedCaller {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(callingUid)?.toList() ?: emptyList()

        return resolveIdentityForUid(callingUid, callingPid, packages)
    }

    internal fun resolveIdentityForUid(
        callingUid: Int,
        callingPid: Int,
        packages: List<String>
    ): VerifiedCaller {
        val isPlatform = callingUid < 10000 // Android system/root UIDs

        return when {
            packages.isEmpty() -> {
                VerifiedCaller(
                    uid = callingUid,
                    pid = callingPid,
                    packageName = "unknown.uid.$callingUid",
                    associatedPackages = emptyList(),
                    isAmbiguous = true,
                    isPlatformOrSystem = isPlatform
                )
            }
            packages.size == 1 -> {
                val pkg = packages[0]
                val signing = resolvePackageSigningIdentity(callingUid, pkg)
                VerifiedCaller(
                    uid = callingUid,
                    pid = callingPid,
                    packageName = pkg,
                    associatedPackages = packages,
                    isAmbiguous = false,
                    isPlatformOrSystem = isPlatform,
                    certSha256Digests = signing?.certSha256Digests ?: emptySet(),
                    versionCode = signing?.versionCode ?: 0L
                )
            }
            else -> {
                // Multiple packages share this UID (sharedUserId).
                // Do not arbitrarily select the first package. Mark as ambiguous.
                val firstSigning = resolvePackageSigningIdentity(callingUid, packages[0])
                VerifiedCaller(
                    uid = callingUid,
                    pid = callingPid,
                    packageName = "shared.uid.$callingUid.[${packages.joinToString(",")}]",
                    associatedPackages = packages,
                    isAmbiguous = true,
                    isPlatformOrSystem = isPlatform,
                    certSha256Digests = firstSigning?.certSha256Digests ?: emptySet(),
                    versionCode = firstSigning?.versionCode ?: 0L
                )
            }
        }
    }

    /**
     * Pure namespace authorization check (preserved for Stage 1 / unit tests).
     */
    fun isAuthorizedToRegisterCapability(caller: VerifiedCaller, capabilityId: String): Boolean {
        if (caller.isAmbiguous && caller.associatedPackages.isEmpty()) {
            return false
        }

        if (capabilityId.startsWith("nexus.text.")) {
            return if (caller.isAmbiguous) {
                caller.associatedPackages.isNotEmpty() && caller.associatedPackages.all { it == "org.nexus.provider.text" }
            } else {
                caller.packageName == "org.nexus.provider.text"
            }
        }

        if (capabilityId.startsWith("nexus.system.")) {
            return caller.isPlatformOrSystem && !caller.isAmbiguous
        }

        return if (caller.isAmbiguous) {
            caller.associatedPackages.isNotEmpty() && caller.associatedPackages.all { pkg ->
                capabilityId.startsWith("$pkg.") || capabilityId == pkg
            }
        } else {
            capabilityId.startsWith("${caller.packageName}.") || capabilityId == caller.packageName
        }
    }

    /**
     * Full Stage 2 registration authorization enforcing:
     * 1. Explicit ComponentName package ownership by the kernel-verified UID.
     * 2. Capability namespace ownership.
     * 3. Signing-certificate pinning for first-party namespaces (nexus.*) using NexusTrustPolicy.
     * 4. Valid signing-certificate presence for third-party providers (without requiring NEXUS's key).
     */
    fun authorizeRegistration(
        caller: VerifiedCaller,
        providerComponent: ComponentName?,
        capabilityIds: List<String>,
        trustPolicy: NexusTrustPolicy = trustPolicyProvider()
    ): RegistrationAuthResult {
        if (providerComponent == null) {
            return RegistrationAuthResult.Denied("providerComponent must not be null")
        }
        return authorizeRegistration(
            caller = caller,
            componentPackage = providerComponent.packageName,
            componentClass = providerComponent.className,
            capabilityIds = capabilityIds,
            trustPolicy = trustPolicy
        )
    }

    fun authorizeRegistration(
        caller: VerifiedCaller,
        componentPackage: String?,
        componentClass: String?,
        capabilityIds: List<String>,
        trustPolicy: NexusTrustPolicy = trustPolicyProvider()
    ): RegistrationAuthResult {
        if (componentPackage.isNullOrBlank() || componentClass.isNullOrBlank()) {
            return RegistrationAuthResult.Denied("providerComponent must not be null or blank")
        }
        if (capabilityIds.isEmpty()) {
            return RegistrationAuthResult.Denied("At least one capability is required for registration")
        }
        if (caller.isAmbiguous && caller.associatedPackages.isEmpty()) {
            return RegistrationAuthResult.Denied("Caller UID [${caller.uid}] has no verifiable package identity")
        }

        val ownsComponent = if (caller.isAmbiguous) {
            caller.associatedPackages.isNotEmpty() && caller.associatedPackages.all { it == componentPackage }
        } else {
            caller.packageName == componentPackage
        }
        if (!ownsComponent) {
            return RegistrationAuthResult.Denied(
                "Caller [${caller.packageName}] does not own service component [$componentPackage/$componentClass]"
            )
        }

        if (caller.certSha256Digests.isEmpty()) {
            return RegistrationAuthResult.Denied(
                "No verified signing certificate found for package [$componentPackage]"
            )
        }

        val trustedFirstPartyDigests = trustPolicy.allowedFrameworkCertSha256
            .map(NexusTrustPolicy::normalizeSha256)
            .filter { it.isNotEmpty() }
            .toSet()

        for (capId in capabilityIds) {
            if (!isAuthorizedToRegisterCapability(caller, capId)) {
                return RegistrationAuthResult.Denied(
                    "Caller [${caller.packageName}] is not authorized for capability namespace [$capId]"
                )
            }

            if (capId.startsWith("nexus.")) {
                if (trustedFirstPartyDigests.isEmpty()) {
                    return RegistrationAuthResult.Denied(
                        "First-party namespace [$capId] rejected: trust policy [${trustPolicy.environmentName}] is fail-closed"
                    )
                }
                val hasTrustedCert = caller.certSha256Digests.any { digest ->
                    trustedFirstPartyDigests.contains(NexusTrustPolicy.normalizeSha256(digest))
                }
                if (!hasTrustedCert) {
                    return RegistrationAuthResult.Denied(
                        "Caller [$componentPackage] signing certificate is not trusted for first-party namespace [$capId]"
                    )
                }
            }
        }

        return RegistrationAuthResult.Authorized(caller.certSha256Digests)
    }

    /**
     * Re-verifies a provider package's UID and SHA-256 signing certificate during Stage 3 recovery
     * after invalidating any cached entry for that package.
     */
    fun verifyProviderPackageForRecovery(
        packageName: String,
        expectedUid: Int,
        capabilityIds: List<String>,
        trustPolicy: NexusTrustPolicy = trustPolicyProvider()
    ): RegistrationAuthResult {
        invalidatePackage(packageName)
        val pm = context.packageManager
            ?: return RegistrationAuthResult.Denied("PackageManager unavailable during recovery")
        val packagesForUid = try {
            pm.getPackagesForUid(expectedUid)?.toList() ?: emptyList()
        } catch (e: Throwable) {
            return RegistrationAuthResult.Denied("Failed to resolve UID [$expectedUid] during recovery: ${e.message}")
        }
        val caller = resolveIdentityForUid(expectedUid, 0, packagesForUid)
        return authorizeRegistration(
            caller = caller,
            componentPackage = packageName,
            componentClass = packageName,
            capabilityIds = capabilityIds,
            trustPolicy = trustPolicy
        )
    }

    /**
     * Invalidates cached signing identities for a replaced or removed package.
     */
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

    private fun resolvePackageSigningIdentity(uid: Int, packageName: String): CachedSigningIdentity? {
        val cacheKey = AuthCacheKey(uid, packageName)

        return try {
            val freshInfo = signingLookupOverride?.getSigningIdentity(packageName) ?: run {
                val pm = context.packageManager ?: return null
                val info = getPackageInfoWithSignatures(pm, packageName) ?: return null
                val currentVersion = getLongVersionCode(info)
                val cached = authCache[cacheKey]
                if (cached != null && cached.versionCode == currentVersion) {
                    return cached
                }
                val digests = extractSha256Digests(info)
                if (digests.isEmpty()) {
                    authCache.remove(cacheKey)
                    return null
                }
                CachedSigningIdentity(currentVersion, digests)
            }

            val cached = authCache[cacheKey]
            if (cached != null && cached.versionCode == freshInfo.versionCode) {
                return cached
            }

            if (freshInfo.certSha256Digests.isEmpty()) {
                authCache.remove(cacheKey)
                return null
            }

            authCache[cacheKey] = freshInfo
            freshInfo
        } catch (_: Throwable) {
            authCache.remove(cacheKey)
            null
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
