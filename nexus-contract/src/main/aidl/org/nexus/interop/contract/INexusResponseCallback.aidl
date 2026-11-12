package org.nexus.interop.contract;

import org.nexus.interop.contract.NexusResponse;

/**
 * Asynchronous response callback interface.
 * Implemented by callers (Client and Framework) to receive results non-blockingly.
 */
interface INexusResponseCallback {
    /**
     * Invoked when an asynchronous request completes or fails.
     */
    oneway void onResponse(in NexusResponse response);
}
