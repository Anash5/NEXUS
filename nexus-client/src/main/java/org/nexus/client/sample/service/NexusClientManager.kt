package org.nexus.client.sample.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.DeadObjectException
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.INexusService
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest
import org.nexus.interop.contract.NexusResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class NexusClientManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _discoveredCapabilities = MutableStateFlow<List<NexusCapability>>(emptyList())
    val discoveredCapabilities: StateFlow<List<NexusCapability>> = _discoveredCapabilities.asStateFlow()

    private var nexusService: INexusService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val hub = INexusService.Stub.asInterface(service)
            nexusService = hub
            _isConnected.value = true
            _connectionError.value = null
            refreshCapabilities()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nexusService = null
            _isConnected.value = false
            _discoveredCapabilities.value = emptyList()
            _connectionError.value = "Disconnected from NEXUS Framework Hub"
        }
    }

    fun connect() {
        if (_isConnected.value) return
        val intent = Intent("org.nexus.action.BIND_HUB").apply {
            setPackage("org.nexus.framework")
        }
        try {
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                _connectionError.value = "Service binding returned false (Check if org.nexus.framework is installed)"
            }
        } catch (e: Exception) {
            _connectionError.value = "Failed to bind to NEXUS Hub: ${e.message}"
        }
    }

    fun disconnect() {
        if (_isConnected.value) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {}
            nexusService = null
            _isConnected.value = false
            _discoveredCapabilities.value = emptyList()
        }
    }

    fun refreshCapabilities() {
        val hub = nexusService ?: return
        scope.launch {
            try {
                val caps = hub.discoverCapabilities("text")
                _discoveredCapabilities.value = caps
            } catch (e: Exception) {
                _connectionError.value = "Failed to query capabilities: ${e.message}"
            }
        }
    }

    /**
     * Dispatches an asynchronous request through the Framework and awaits the response via Coroutine Deferred.
     * Enforces client-side 64 KB payload validation, safe timeout via withTimeoutOrNull, and drops late callbacks.
     */
    suspend fun executeCapability(
        capabilityId: String,
        payload: String,
        timeoutMs: Long = 5000L
    ): NexusResponse {
        val startTime = System.currentTimeMillis()

        // 1. Client-Side Payload Validation (Correction 3): reject > 64 KB before attempting Binder IPC
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > 65536) {
            return NexusResponse(
                requestId = UUID.randomUUID().toString(),
                isSuccess = false,
                statusCode = NexusResponse.STATUS_PAYLOAD_TOO_LARGE,
                resultData = null,
                errorMessage = "Client rejected payload: exceeds 64 KB safety limit (${payloadBytes.size} bytes)",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 2. Hub Connection Check
        val hub = nexusService ?: return NexusResponse(
            requestId = UUID.randomUUID().toString(),
            isSuccess = false,
            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
            resultData = null,
            errorMessage = "Not connected to NEXUS Framework Hub",
            executionTimeMs = System.currentTimeMillis() - startTime
        )

        val requestId = UUID.randomUUID().toString()
        val boundedTimeout = timeoutMs.coerceIn(1000L, 30000L)
        val request = NexusRequest(
            requestId = requestId,
            capabilityId = capabilityId,
            payload = payload,
            timeoutMs = boundedTimeout
        )

        val isCompleted = AtomicBoolean(false)
        val deferred = CompletableDeferred<NexusResponse>()

        // 3. Callback with Late-Response Protection (Correction 4 & 7)
        val callback = object : INexusResponseCallback.Stub() {
            override fun onResponse(response: NexusResponse) {
                if (isCompleted.compareAndSet(false, true)) {
                    deferred.complete(response)
                }
                // If isCompleted was already true (e.g. client timed out), late response is discarded.
            }
        }

        // 4. Submit Request with Explicit DeadObjectException and RemoteException Handling (Correction 4)
        try {
            hub.submitRequest(request, callback)
        } catch (e: DeadObjectException) {
            if (isCompleted.compareAndSet(false, true)) {
                return NexusResponse(
                    requestId = requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_PROVIDER_CRASHED,
                    resultData = null,
                    errorMessage = "NEXUS Hub process died during request submission (DeadObjectException)",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: RemoteException) {
            if (isCompleted.compareAndSet(false, true)) {
                return NexusResponse(
                    requestId = requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                    resultData = null,
                    errorMessage = "Binder IPC RemoteException during submission: ${e.message}",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            if (isCompleted.compareAndSet(false, true)) {
                return NexusResponse(
                    requestId = requestId,
                    isSuccess = false,
                    statusCode = NexusResponse.STATUS_EXECUTION_FAILED,
                    resultData = null,
                    errorMessage = "Binder transaction error: ${e.message}",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // 5. Client-Side Timeout (Correction 4): withTimeoutOrNull with grace margin over timeoutMs
        val response = withTimeoutOrNull(boundedTimeout + 1000L) {
            deferred.await()
        }

        return if (response != null) {
            response
        } else {
            // Expired on client side: mark completed to discard late arrivals and cancel deferred
            if (isCompleted.compareAndSet(false, true)) {
                deferred.cancel()
            }
            NexusResponse(
                requestId = requestId,
                isSuccess = false,
                statusCode = NexusResponse.STATUS_TIMEOUT,
                resultData = null,
                errorMessage = "Client request timed out after ${boundedTimeout + 1000L}ms waiting for response",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
