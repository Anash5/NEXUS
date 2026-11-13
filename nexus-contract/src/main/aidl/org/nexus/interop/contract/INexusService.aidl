package org.nexus.interop.contract;

import android.content.ComponentName;
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
     * Legacy synchronous provider registration (retained for Stage 1 compatibility).
     */
    String registerProvider(
        in List<NexusCapability> capabilities,
        in INexusProvider providerBinder,
        long leaseDurationMs
    );

    /**
     * Asynchronous non-blocking provider registration (Stage 1 contract).
     * Result (leaseToken on 200 OK or failure code) is delivered via registrationCallback.
     */
    oneway void registerProviderAsync(
        in List<NexusCapability> capabilities,
        in ComponentName providerComponent,
        long leaseDurationMs,
        in INexusResponseCallback registrationCallback
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
