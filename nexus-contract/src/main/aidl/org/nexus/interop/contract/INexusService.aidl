package org.nexus.interop.contract;

import org.nexus.interop.contract.INexusProvider;
import org.nexus.interop.contract.INexusResponseCallback;
import org.nexus.interop.contract.NexusCapability;
import org.nexus.interop.contract.NexusRequest;

/**
 * Core interface exported by the central NEXUS Framework Hub (nexus-framework).
 * Provides capability discovery, lifecycle management, and request routing.
 */
interface INexusService {
    /**
     * Registers a provider and its exposed capabilities.
     * Returns an opaque lease token string on success.
     */
    String registerProvider(
        in List<NexusCapability> capabilities,
        in INexusProvider providerBinder,
        long leaseDurationMs
    );

    /**
     * Heartbeat / Lease renewal to keep capabilities active.
     */
    boolean renewLease(String leaseToken);

    /**
     * Graceful deactivation / unregistration of all capabilities associated with the lease.
     */
    boolean unregisterProvider(String leaseToken);

    /**
     * Discovers currently available and registered capabilities.
     */
    List<NexusCapability> discoverCapabilities(String filterCategory);

    /**
     * Submits an asynchronous request for routing through the Framework to the responsible provider.
     * The response will be delivered asynchronously to clientCallback.
     */
    oneway void submitRequest(in NexusRequest request, in INexusResponseCallback clientCallback);
}
