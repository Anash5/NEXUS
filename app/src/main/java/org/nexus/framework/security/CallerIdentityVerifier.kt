package org.nexus.framework.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder

/**
 * Validates and resolves caller identity using the Linux kernel Binder subsystem.
 * Never trusts package names or identities passed in Parcelable payload bodies.
 */
class CallerIdentityVerifier(private val context: Context) {

    data class VerifiedCaller(
        val uid: Int,
        val pid: Int,
        val packageName: String,
        val associatedPackages: List<String> = emptyList(),
        val isAmbiguous: Boolean = false,
        val isPlatformOrSystem: Boolean = false
    )

    /**
     * Resolves the verified calling identity for the current Binder transaction.
     * Accurately handles multi-package shared UIDs without arbitrary selection.
     */
    fun resolveCurrentCallingIdentity(): VerifiedCaller {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()

        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(callingUid)?.toList() ?: emptyList()

        val isPlatform = callingUid < 10000 // Android system/root UIDs

        return when {
            packages.isEmpty() -> {
                // Completely unresolvable identity -> fail-closed ambiguous
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
                // Exactly one unambiguous package owning this UID
                VerifiedCaller(
                    uid = callingUid,
                    pid = callingPid,
                    packageName = packages[0],
                    associatedPackages = packages,
                    isAmbiguous = false,
                    isPlatformOrSystem = isPlatform
                )
            }
            else -> {
                // Multiple packages share this UID (sharedUserId).
                // Do not arbitrarily select the first package. Mark as ambiguous (Correction 5).
                VerifiedCaller(
                    uid = callingUid,
                    pid = callingPid,
                    packageName = "shared.uid.$callingUid.[${packages.joinToString(",")}]",
                    associatedPackages = packages,
                    isAmbiguous = true,
                    isPlatformOrSystem = isPlatform
                )
            }
        }
    }

    /**
     * Validates whether a caller UID is authorized to register a specific capability namespace.
     * Prevents malicious packages from squatting on reserved namespaces.
     * Fails closed on ambiguous shared-UID identities unless ALL packages are verified (Correction 5).
     * System UIDs cannot bypass provider namespace restrictions unintentionally (Correction 6).
     */
    fun isAuthorizedToRegisterCapability(caller: VerifiedCaller, capabilityId: String): Boolean {
        // Fail closed on unresolvable identities
        if (caller.isAmbiguous && caller.associatedPackages.isEmpty()) {
            return false
        }

        // Core NEXUS text capabilities can ONLY be provided by the authorized text provider package.
        // System UIDs cannot bypass this designated provider namespace (Correction 6).
        if (capabilityId.startsWith("nexus.text.")) {
            return if (caller.isAmbiguous) {
                // Fail closed unless ALL packages sharing the UID are the authorized text provider
                caller.associatedPackages.isNotEmpty() && caller.associatedPackages.all { it == "org.nexus.provider.text" }
            } else {
                caller.packageName == "org.nexus.provider.text"
            }
        }

        // Reserved system capabilities can only be registered by platform/system UIDs that are not ambiguous
        if (capabilityId.startsWith("nexus.system.")) {
            return caller.isPlatformOrSystem && !caller.isAmbiguous
        }

        // Custom capabilities must be strictly namespaced with the provider's verified package name
        return if (caller.isAmbiguous) {
            // Fail closed unless ALL packages sharing this UID match the capability namespace
            caller.associatedPackages.isNotEmpty() && caller.associatedPackages.all { pkg ->
                capabilityId.startsWith("$pkg.") || capabilityId == pkg
            }
        } else {
            capabilityId.startsWith("${caller.packageName}.") || capabilityId == caller.packageName
        }
    }
}
