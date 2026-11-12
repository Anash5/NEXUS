package org.nexus.provider.text.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nexus.interop.contract.*
import org.nexus.provider.text.engine.TextProcessingEngine

class TextProviderService : Service() {

    companion object {
        const val ACTION_START = "org.nexus.provider.action.START"
        const val ACTION_STOP = "org.nexus.provider.action.STOP"

        @Volatile
        var isServiceActive: Boolean = false
            private set

        private val _providerStatusFlow = MutableStateFlow(ProviderState())
        val providerStatusFlow: StateFlow<ProviderState> = _providerStatusFlow.asStateFlow()

        data class ProviderState(
            val isBoundToHub: Boolean = false,
            val leaseToken: String? = null,
            val requestsServed: Long = 0L,
            val lastRequestCapability: String? = null,
            val lastRequestLatencyMs: Long = 0L,
            val errorState: String? = null
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var nexusService: INexusService? = null
    private var leaseToken: String? = null
    private var heartbeatJob: Job? = null
    private var requestsServedCount = 0L

    private val providerBinder = object : INexusProvider.Stub() {
        override fun executeAsync(request: NexusRequest, callback: INexusResponseCallback) {
            val startTime = System.currentTimeMillis()
            serviceScope.launch {
                val (isSuccess, statusCode, resultData, errorMessage) = try {
                    when (request.capabilityId) {
                        "nexus.text.uppercase" -> {
                            val res = TextProcessingEngine.toUppercase(request.payload)
                            Tuple4(true, NexusResponse.STATUS_SUCCESS, res, null)
                        }
                        "nexus.text.lowercase" -> {
                            val res = TextProcessingEngine.toLowercase(request.payload)
                            Tuple4(true, NexusResponse.STATUS_SUCCESS, res, null)
                        }
                        "nexus.text.wordcount" -> {
                            val res = TextProcessingEngine.wordCountJson(request.payload)
                            Tuple4(true, NexusResponse.STATUS_SUCCESS, res, null)
                        }
                        "nexus.text.reverse" -> {
                            val res = TextProcessingEngine.reverse(request.payload)
                            Tuple4(true, NexusResponse.STATUS_SUCCESS, res, null)
                        }
                        else -> {
                            Tuple4(false, NexusResponse.STATUS_CAPABILITY_NOT_FOUND, null, "Unsupported capability [${request.capabilityId}]")
                        }
                    }
                } catch (e: Exception) {
                    Tuple4(false, NexusResponse.STATUS_EXECUTION_FAILED, null, "Execution error: ${e.message}")
                }

                val duration = System.currentTimeMillis() - startTime
                requestsServedCount++

                _providerStatusFlow.value = _providerStatusFlow.value.copy(
                    requestsServed = requestsServedCount,
                    lastRequestCapability = request.capabilityId,
                    lastRequestLatencyMs = duration
                )

                val response = NexusResponse(
                    requestId = request.requestId,
                    isSuccess = isSuccess,
                    statusCode = statusCode,
                    resultData = resultData,
                    errorMessage = errorMessage,
                    executionTimeMs = duration,
                    verifiedProviderPackage = packageName
                )

                try {
                    callback.onResponse(response)
                } catch (_: Exception) {}
            }
        }

        override fun ping(): Boolean = true
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private val hubConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val hub = INexusService.Stub.asInterface(service)
            nexusService = hub

            serviceScope.launch {
                try {
                    val capabilities = createTextCapabilities()
                    val token = hub.registerProvider(capabilities, providerBinder, 60000L)
                    leaseToken = token

                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        isBoundToHub = true,
                        leaseToken = token,
                        errorState = null
                    )

                    startHeartbeat(token)
                } catch (e: Exception) {
                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        isBoundToHub = false,
                        errorState = "Failed to register capabilities: ${e.message}"
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nexusService = null
            leaseToken = null
            heartbeatJob?.cancel()
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                isBoundToHub = false,
                leaseToken = null,
                errorState = "Disconnected from NEXUS Hub"
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        connectToHub()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disconnectFromHub()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun connectToHub() {
        val intent = Intent("org.nexus.action.BIND_HUB").apply {
            setPackage("org.nexus.framework")
        }
        try {
            bindService(intent, hubConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                errorState = "Cannot bind to org.nexus.framework: ${e.message}"
            )
        }
    }

    private fun startHeartbeat(token: String) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(15000)
                try {
                    val renewed = nexusService?.renewLease(token) ?: false
                    if (!renewed) {
                        _providerStatusFlow.value = _providerStatusFlow.value.copy(
                            errorState = "Lease renewal rejected by Hub"
                        )
                    }
                } catch (e: Exception) {
                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        errorState = "Heartbeat failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun disconnectFromHub() {
        heartbeatJob?.cancel()
        val token = leaseToken
        val hub = nexusService
        if (token != null && hub != null) {
            try {
                hub.unregisterProvider(token)
            } catch (_: Exception) {}
        }
        try {
            unbindService(hubConnection)
        } catch (_: Exception) {}
        nexusService = null
        leaseToken = null
        _providerStatusFlow.value = _providerStatusFlow.value.copy(
            isBoundToHub = false,
            leaseToken = null
        )
    }

    private fun createTextCapabilities(): List<NexusCapability> {
        return listOf(
            NexusCapability(
                capabilityId = "nexus.text.uppercase",
                displayName = "Uppercase Converter",
                description = "Converts input text to uppercase characters",
                version = "1.0.0",
                category = "text",
                inputMimeType = "text/plain",
                outputMimeType = "text/plain",
                inputSchema = "raw_string",
                outputSchema = "uppercase_string",
                maxPayloadSizeBytes = 65536L,
                timeoutMs = 5000L
            ),
            NexusCapability(
                capabilityId = "nexus.text.lowercase",
                displayName = "Lowercase Converter",
                description = "Converts input text to lowercase characters",
                version = "1.0.0",
                category = "text",
                inputMimeType = "text/plain",
                outputMimeType = "text/plain",
                inputSchema = "raw_string",
                outputSchema = "lowercase_string",
                maxPayloadSizeBytes = 65536L,
                timeoutMs = 5000L
            ),
            NexusCapability(
                capabilityId = "nexus.text.wordcount",
                displayName = "Word & Character Counter",
                description = "Computes word and character count metrics",
                version = "1.0.0",
                category = "text",
                inputMimeType = "text/plain",
                outputMimeType = "application/json",
                inputSchema = "raw_string",
                outputSchema = """{"wordCount": number, "characters": number}""",
                maxPayloadSizeBytes = 65536L,
                timeoutMs = 5000L
            ),
            NexusCapability(
                capabilityId = "nexus.text.reverse",
                displayName = "Text Inversion / Reverser",
                description = "Reverses the order of characters in the input string",
                version = "1.0.0",
                category = "text",
                inputMimeType = "text/plain",
                outputMimeType = "text/plain",
                inputSchema = "raw_string",
                outputSchema = "reversed_string",
                maxPayloadSizeBytes = 65536L,
                timeoutMs = 5000L
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder = providerBinder

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        disconnectFromHub()
        serviceScope.cancel()
    }
}
