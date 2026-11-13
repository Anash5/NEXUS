package org.nexus.framework.registry

import android.content.ComponentName
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.NexusCapability

/**
 * Lifecycle states for a registered capability provider lease.
 */
enum class LeaseState {
    STAGING,
    COMMITTING,
    ACTIVE,
    DISCONNECTED,
    TERMINATED
}

/**
 * Lifecycle states for a single asynchronous registration attempt.
 * Valid transitions:
 * - STAGING -> COMMITTING -> ACTIVE
 * - STAGING -> COMMITTING -> ABORTED (recoverable commit failure)
 * - STAGING -> ABORTED
 */
enum class RegistrationState {
    STAGING,
    COMMITTING,
    ACTIVE,
    ABORTED
}

/**
 * Composite identity key uniquely identifying a provider package + Linux UID pair.
 */
data class ProviderIdentityKey(
    val uid: Int,
    val packageName: String
)

/**
 * Generation-tagged route ownership entry preventing stale/superseded leases from deleting newer routes.
 */
data class RouteOwner(
    val leaseToken: String,
    val generationId: Long,
    val identityKey: ProviderIdentityKey
)

/**
 * Immutable route target stored inside a copy-on-write [RoutingSnapshot].
 */
data class CapabilityRoute(
    val capability: NexusCapability,
    val providerBinder: INexusProvider,
    val leaseToken: String,
    val generationId: Long,
    val providerPackage: String,
    val providerUid: Int
)

/**
 * Immutable copy-on-write routing snapshot published atomically for lock-free request routing.
 */
data class RoutingSnapshot(
    val version: Long = 0L,
    val routesByCapabilityId: Map<String, CapabilityRoute> = emptyMap(),
    val capabilitiesList: List<NexusCapability> = emptyList()
)

/**
 * Terminal outcome of a single registration attempt.
 */
sealed class RegistrationOutcome {
    abstract val registrationId: String
    abstract val generationId: Long

    data class Activated(
        override val registrationId: String,
        override val generationId: Long,
        val leaseToken: String
    ) : RegistrationOutcome()

    data class Aborted(
        override val registrationId: String,
        override val generationId: Long,
        val statusCode: Int,
        val reason: String
    ) : RegistrationOutcome()
}

/**
 * Immutable metadata snapshot describing a staged registration request.
 */
data class RegistrationMetadata(
    val registrationId: String,
    val generationId: Long,
    val callerIdentity: ProviderIdentityKey,
    val providerComponent: ComponentName?,
    val capabilities: List<NexusCapability>,
    val leaseDurationMs: Long
)
