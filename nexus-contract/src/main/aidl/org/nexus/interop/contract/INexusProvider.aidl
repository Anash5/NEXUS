package org.nexus.interop.contract;

import org.nexus.interop.contract.NexusRequest;
import org.nexus.interop.contract.INexusResponseCallback;

/**
 * Interface exported by capability providers (e.g. nexus-provider).
 * Invoked asynchronously by the Framework to execute operations.
 */
interface INexusProvider {
    /**
     * Executes an operation asynchronously.
     * The provider must return the result via callback.onResponse().
     */
    oneway void executeAsync(in NexusRequest request, in INexusResponseCallback callback);

    /**
     * Liveness check to verify the provider process is healthy.
     */
    boolean ping();
}
