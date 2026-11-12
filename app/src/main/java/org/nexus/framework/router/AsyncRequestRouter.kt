package org.nexus.framework.router

import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.security.CallerIdentityVerifier.VerifiedCaller
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.NexusRequest
import org.nexus.interop.contract.NexusResponse
import java.util.concurrent.ConcurrentHashMap

data class RouterTelemetryTrace(
    val traceId: String,
    val requestId: String,
    val clientPackage: String,
    val providerPackage: String,
    val capabilityId: String,
    val statusCode: Int,
    val executionTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class AsyncRequestRouter(
    private val registry: CapabilityRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private data class InflightRequest(
        val requestId: String,
        val clientCallback: INexusResponseCallback,
        val clientDeathRecipient: IBinder.DeathRecipient?,
        val providerPackage: String,
        val startTimeMs: Long,
        val timeoutJob: Job
    )

    private val inflightRequests = ConcurrentHashMap<String, InflightRequest>()

    private val _telemetryStream = MutableStateFlow<List<RouterTelemetryTrace>>(emptyList())
    val telemetryStream: StateFlow<List<RouterTelemetryTrace>> = _telemetryStream.asStateFlow()

    fun routeRequest(caller: VerifiedCaller, request: NexusRequest, clientCallback: INexusResponseCallback) {
        val startTime = System.currentTimeMillis()

        // 1. Enforce 64 KB initial Binder payload limit (Correction 3)
        if (request.payload.toByteArray(Charsets.UTF_8).size > 65536) {
            safeSendResponse(
                clientCallback,
                NexusResponse(
                    requestId = request.requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_PAYLOAD_TOO_LARGE,
                    resultData = null,
                    errorMessage = "Payload exceeds 64 KB Binder safety limit (${request.payload.length} bytes)",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            )
            return
        }

        // 2. Prevent Duplicate Request IDs (Idempotency)
        if (inflightRequests.containsKey(request.requestId)) {
            safeSendResponse(
                clientCallback,
                NexusResponse(
                    requestId = request.requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_DUPLICATE_REQUEST,
                    resultData = null,
                    errorMessage = "Request ID [${request.requestId}] is already in-flight",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            )
            return
        }

        // 3. Resolve Provider from Capability Registry
        val providerEntry = registry.getProviderForCapability(request.capabilityId)
        if (providerEntry == null) {
            safeSendResponse(
                clientCallback,
                NexusResponse(
                    requestId = request.requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_CAPABILITY_NOT_FOUND,
                    resultData = null,
                    errorMessage = "No active provider found for capability [${request.capabilityId}]",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            )
            return
        }

        val (capability, providerBinder) = providerEntry

        // 4. Stamp Request with Verified Kernel Caller Identity (Correction 1)
        val stampedRequest = request.copy(
            verifiedClientPackage = caller.packageName,
            verifiedClientUid = caller.uid
        )

        // 5. Watch for Client Death to purge abandoned callbacks (Correction 7)
        var clientDeathRecipient: IBinder.DeathRecipient? = null
        clientDeathRecipient = IBinder.DeathRecipient {
            val abandoned = inflightRequests.remove(request.requestId)
            if (abandoned != null) {
                cleanupInflightRequest(abandoned)
            }
        }
        try {
            clientCallback.asBinder()?.linkToDeath(clientDeathRecipient, 0)
        } catch (_: Throwable) {
            // Unmocked in JVM tests or client already dead
            clientDeathRecipient = null
        }

        // 6. Setup Watchdog Timeout Job
        val timeoutMs = request.timeoutMs.coerceIn(1000L, 30000L)
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            val expired = inflightRequests.remove(request.requestId)
            if (expired != null) {
                cleanupInflightRequest(expired)
                val duration = System.currentTimeMillis() - startTime
                safeSendResponse(
                    clientCallback,
                    NexusResponse(
                        requestId = request.requestId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_TIMEOUT,
                        resultData = null,
                        errorMessage = "Execution timed out after ${timeoutMs}ms",
                        executionTimeMs = duration,
                        verifiedProviderPackage = capability.providerPackage
                    )
                )
                recordTrace(
                    requestId = request.requestId,
                    clientPackage = caller.packageName,
                    providerPackage = capability.providerPackage,
                    capabilityId = request.capabilityId,
                    statusCode = NexusResponse.STATUS_TIMEOUT,
                    executionTimeMs = duration
                )
            }
        }

        val inflight = InflightRequest(
            requestId = request.requestId,
            clientCallback = clientCallback,
            clientDeathRecipient = clientDeathRecipient,
            providerPackage = capability.providerPackage,
            startTimeMs = startTime,
            timeoutJob = timeoutJob
        )
        inflightRequests[request.requestId] = inflight

        // 7. Create Framework-Controlled Response Proxy Callback
        val proxyCallback = object : INexusResponseCallback.Stub() {
            override fun onResponse(response: NexusResponse) {
                val active = inflightRequests.remove(response.requestId)
                if (active != null) {
                    cleanupInflightRequest(active)
                    val duration = System.currentTimeMillis() - active.startTimeMs

                    val enrichedResponse = response.copy(
                        executionTimeMs = duration,
                        verifiedProviderPackage = capability.providerPackage
                    )

                    safeSendResponse(active.clientCallback, enrichedResponse)

                    recordTrace(
                        requestId = response.requestId,
                        clientPackage = caller.packageName,
                        providerPackage = capability.providerPackage,
                        capabilityId = request.capabilityId,
                        statusCode = response.statusCode,
                        executionTimeMs = duration
                    )
                }
            }
        }

        // 8. Execute Asynchronously across Process Boundary
        try {
            providerBinder.executeAsync(stampedRequest, proxyCallback)
        } catch (e: Exception) {
            val failed = inflightRequests.remove(request.requestId)
            if (failed != null) {
                cleanupInflightRequest(failed)
            }
            safeSendResponse(
                clientCallback,
                NexusResponse(
                    requestId = request.requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                    resultData = null,
                    errorMessage = "Failed to dispatch Binder transaction to provider: ${e.message}",
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    verifiedProviderPackage = capability.providerPackage
                )
            )
        }
    }

    fun handleProviderCrashed(providerPackage: String) {
        val affected = inflightRequests.filter { it.value.providerPackage == providerPackage }
        affected.forEach { (reqId, inflight) ->
            val removed = inflightRequests.remove(reqId)
            if (removed != null) {
                cleanupInflightRequest(removed)
                safeSendResponse(
                    removed.clientCallback,
                    NexusResponse(
                        requestId = reqId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_PROVIDER_CRASHED,
                        resultData = null,
                        errorMessage = "Provider process crashed while request was in-flight",
                        executionTimeMs = System.currentTimeMillis() - removed.startTimeMs,
                        verifiedProviderPackage = providerPackage
                    )
                )
            }
        }
    }

    private fun cleanupInflightRequest(inflight: InflightRequest) {
        inflight.timeoutJob.cancel()
        if (inflight.clientDeathRecipient != null) {
            try {
                inflight.clientCallback.asBinder()?.unlinkToDeath(inflight.clientDeathRecipient, 0)
            } catch (_: Throwable) {}
        }
    }

    private fun safeSendResponse(callback: INexusResponseCallback, response: NexusResponse) {
        try {
            callback.onResponse(response)
        } catch (_: Exception) {
            // Client process already terminated
        }
    }

    private fun recordTrace(
        requestId: String,
        clientPackage: String,
        providerPackage: String,
        capabilityId: String,
        statusCode: Int,
        executionTimeMs: Long
    ) {
        val trace = RouterTelemetryTrace(
            traceId = "trc-${requestId.take(8)}",
            requestId = requestId,
            clientPackage = clientPackage,
            providerPackage = providerPackage,
            capabilityId = capabilityId,
            statusCode = statusCode,
            executionTimeMs = executionTimeMs
        )
        _telemetryStream.value = (listOf(trace) + _telemetryStream.value).take(100)
    }
}
