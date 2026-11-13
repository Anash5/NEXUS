package org.nexus.interop.contract

/**
 * Release-variant trust configuration.
 * Strictly fail-closed until the finalized production signing-certificate fingerprint is supplied.
 * Never includes development or debug.keystore identities.
 */
object NexusTrustConfig {
    val current: NexusTrustPolicy = NexusTrustPolicy.failClosedRelease()
}
