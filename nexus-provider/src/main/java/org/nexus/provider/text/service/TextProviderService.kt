package org.nexus.provider.text.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nexus.interop.contract.*
import org.nexus.provider.text.engine.TextProcessingEngine
import org.nexus.provider.text.security.FrameworkAuthenticator
import java.util.concurrent.atomic.AtomicLong

class TextProviderService : Service() {

    companion object {
        const val ACTION_START = "org.nexus.provider.action.START"
        const val ACTION_STOP = "org.nexus.provider.action.STOP"

        @Volatile
        var isServiceActive: Boolean = false
            private set

        private val _providerStatusFlow = MutableStateFlow(ProviderState())
        val providerStatusFlow: StateFlow<ProviderState> = _providerStatusFlow.asStateFlow()

        fun syncPersistedState(context: Context): Boolean {
            val enabled = ProviderActivationStore(context).isActivationEnabled()
            _providerStatusFlow.value = _providerStatusFlow.value.copy(isActivationEnabled = enabled)
            return enabled
        }

        data class ProviderState(
            val isActivationEnabled: Boolean = false,
            val isBoundToHub: Boolean = false,
            val leaseToken: String? = null,
            val requestsServed: Long = 0L,
            val lastRequestCapability: String? = null,
            val lastRequestLatencyMs: Long = 0L,
            val errorState: String? = null
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var frameworkAuthenticator: FrameworkAuthenticator
    private lateinit var activationStore: ProviderActivationStore

    private val hubSessionEpoch = AtomicLong(0L)

    @Volatile
    private var nexusService: INexusService? = null

    @Volatile
    private var leaseToken: String? = null

    @Volatile
    private var isHubBindRequested: Boolean = false

    private var heartbeatJob: Job? = null
    private var hubReconnectJob: Job? = null
    private var requestsServedCount = 0L

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_REPLACED || action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
                val pkg = intent.data?.schemeSpecificPart
                if (!pkg.isNullOrBlank()) {
                    frameworkAuthenticator.invalidatePackage(pkg)
                }
            }
        }
    }

    private val providerBinder = object : INexusProvider.Stub() {
        override fun executeAsync(request: NexusRequest, callback: INexusResponseCallback) {
            val authResult = frameworkAuthenticator.verifyIncomingBinderCaller(Binder.getCallingUid())
            if (authResult is FrameworkAuthenticator.AuthResult.Untrusted) {
                try {
                    callback.onResponse(
                        NexusResponse(
                            requestId = request.requestId,
                            isSuccess = false,
                            statusCode = NexusResponse.STATUS_PERMISSION_DENIED,
                            resultData = null,
                            errorMessage = authResult.reason,
                            executionTimeMs = 0L,
                            verifiedProviderPackage = packageName
                        )
                    )
                } catch (_: Exception) {}
                return
            }

            if (!activationStore.isActivationEnabled()) {
                try {
                    callback.onResponse(
                        NexusResponse(
                            requestId = request.requestId,
                            isSuccess = false,
                            statusCode = NexusResponse.STATUS_PROVIDER_DISCONNECTED,
                            resultData = null,
                            errorMessage = "Provider is deactivated by user",
                            executionTimeMs = 0L,
                            verifiedProviderPackage = packageName
                        )
                    )
                } catch (_: Exception) {}
                return
            }

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

        override fun ping(): Boolean {
            val authResult = frameworkAuthenticator.verifyIncomingBinderCaller(Binder.getCallingUid())
            if (authResult !is FrameworkAuthenticator.AuthResult.Trusted) {
                return false
            }
            return activationStore.isActivationEnabled()
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private val hubConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                safeUnbindHub()
                _providerStatusFlow.value = _providerStatusFlow.value.copy(
                    isBoundToHub = false,
                    leaseToken = null,
                    errorState = "NEXUS Hub returned null IBinder"
                )
                return
            }

            if (!activationStore.isActivationEnabled()) {
                safeUnbindHub()
                return
            }

            hubReconnectJob?.cancel()

            // Authenticate the Framework Hub's package and SHA-256 signing certificate outbound
            val authResult = frameworkAuthenticator.verifyBoundHub(name?.packageName)
            if (authResult is FrameworkAuthenticator.AuthResult.Untrusted) {
                heartbeatJob?.cancel()
                nexusService = null
                leaseToken = null
                safeUnbindHub()
                _providerStatusFlow.value = _providerStatusFlow.value.copy(
                    isBoundToHub = false,
                    leaseToken = null,
                    errorState = "Untrusted NEXUS Hub rejected: ${authResult.reason}"
                )
                return
            }

            val hub = INexusService.Stub.asInterface(service)
            nexusService = hub
            val sessionEpoch = hubSessionEpoch.incrementAndGet()
            initiateAsyncRegistration(hub, sessionEpoch)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hubSessionEpoch.incrementAndGet()
            nexusService = null
            leaseToken = null
            heartbeatJob?.cancel()
            // Do not unbindService here: BIND_AUTO_CREATE keeps the ServiceConnection recoverable
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                isBoundToHub = false,
                leaseToken = null,
                errorState = if (activationStore.isActivationEnabled()) {
                    "Disconnected from NEXUS Hub (awaiting reconnect)"
                } else {
                    null
                }
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            hubSessionEpoch.incrementAndGet()
            nexusService = null
            leaseToken = null
            heartbeatJob?.cancel()
            safeUnbindHub()
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                isBoundToHub = false,
                leaseToken = null,
                errorState = "NEXUS Hub binding died"
            )
            if (activationStore.isActivationEnabled()) {
                scheduleHubReconnect()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            hubSessionEpoch.incrementAndGet()
            nexusService = null
            leaseToken = null
            heartbeatJob?.cancel()
            safeUnbindHub()
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                isBoundToHub = false,
                leaseToken = null,
                errorState = "NEXUS Hub returned null binding"
            )
        }
    }

    private fun initiateAsyncRegistration(hub: INexusService, sessionEpoch: Long) {
        val registrationCallback = object : INexusResponseCallback.Stub() {
            override fun onResponse(response: NexusResponse) {
                // Reject stale registration callbacks from superseded sessions or deactivated state
                if (hubSessionEpoch.get() != sessionEpoch || !activationStore.isActivationEnabled()) {
                    return
                }
                val token = response.resultData
                if (response.isSuccess && !token.isNullOrBlank()) {
                    leaseToken = token
                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        isActivationEnabled = true,
                        isBoundToHub = true,
                        leaseToken = token,
                        errorState = null
                    )
                    startHeartbeat(token, sessionEpoch)
                } else {
                    leaseToken = null
                    heartbeatJob?.cancel()
                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        isBoundToHub = false,
                        leaseToken = null,
                        errorState = response.errorMessage ?: "Registration failed (${response.statusCode})"
                    )
                }
            }
        }

        serviceScope.launch {
            try {
                if (hubSessionEpoch.get() != sessionEpoch || !activationStore.isActivationEnabled()) {
                    return@launch
                }
                val capabilities = createTextCapabilities()
                val providerComponent = ComponentName(this@TextProviderService, TextProviderService::class.java)
                hub.registerProviderAsync(capabilities, providerComponent, 60000L, registrationCallback)
            } catch (e: Exception) {
                if (hubSessionEpoch.get() == sessionEpoch) {
                    _providerStatusFlow.value = _providerStatusFlow.value.copy(
                        isBoundToHub = false,
                        leaseToken = null,
                        errorState = "Failed to initiate capability registration: ${e.message}"
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        frameworkAuthenticator = FrameworkAuthenticator(this)
        activationStore = ProviderActivationStore(this)

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this,
            packageChangeReceiver,
            packageFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        val persistedEnabled = activationStore.isActivationEnabled()
        _providerStatusFlow.value = _providerStatusFlow.value.copy(isActivationEnabled = persistedEnabled)
        if (persistedEnabled) {
            connectToHub()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val shouldActivate = activationStore.resolveAndPersistForStartAction(action)
        _providerStatusFlow.value = _providerStatusFlow.value.copy(isActivationEnabled = shouldActivate)

        if (!shouldActivate) {
            disconnectFromHub()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isHubBindRequested && nexusService == null) {
            connectToHub()
        }
        return START_STICKY
    }

    private fun connectToHub() {
        if (isHubBindRequested || !activationStore.isActivationEnabled()) return
        hubReconnectJob?.cancel()
        val intent = Intent("org.nexus.action.BIND_HUB").apply {
            setPackage("org.nexus.framework")
        }
        try {
            val bound = bindService(intent, hubConnection, Context.BIND_AUTO_CREATE)
            isHubBindRequested = bound
            if (!bound) {
                _providerStatusFlow.value = _providerStatusFlow.value.copy(
                    isBoundToHub = false,
                    errorState = "Cannot bind to org.nexus.framework"
                )
            }
        } catch (e: Exception) {
            isHubBindRequested = false
            _providerStatusFlow.value = _providerStatusFlow.value.copy(
                isBoundToHub = false,
                errorState = "Cannot bind to org.nexus.framework: ${e.message}"
            )
        }
    }

    private fun scheduleHubReconnect() {
        hubReconnectJob?.cancel()
        hubReconnectJob = serviceScope.launch {
            delay(2000L)
            if (activationStore.isActivationEnabled() && !isHubBindRequested && nexusService == null) {
                connectToHub()
            }
        }
    }

    private fun safeUnbindHub() {
        if (isHubBindRequested) {
            isHubBindRequested = false
            try {
                unbindService(hubConnection)
            } catch (_: Exception) {}
        }
    }

    private fun startHeartbeat(token: String, sessionEpoch: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && hubSessionEpoch.get() == sessionEpoch && activationStore.isActivationEnabled()) {
                delay(15000)
                val hub = nexusService ?: break
                try {
                    val renewed = hub.renewLease(token)
                    if (!renewed) {
                        _providerStatusFlow.value = _providerStatusFlow.value.copy(
                            isBoundToHub = false,
                            leaseToken = null,
                            errorState = "Lease renewal rejected by Hub; re-registering"
                        )
                        if (hubSessionEpoch.get() == sessionEpoch && activationStore.isActivationEnabled()) {
                            initiateAsyncRegistration(hub, sessionEpoch)
                        }
                        break
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
        hubSessionEpoch.incrementAndGet()
        hubReconnectJob?.cancel()
        heartbeatJob?.cancel()
        val token = leaseToken
        val hub = nexusService
        if (token != null && hub != null) {
            try {
                hub.unregisterProvider(token)
            } catch (_: Exception) {}
        }
        safeUnbindHub()
        nexusService = null
        leaseToken = null
        _providerStatusFlow.value = _providerStatusFlow.value.copy(
            isBoundToHub = false,
            leaseToken = null,
            errorState = null
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

    // Inbound Binder authentication happens inside INexusProvider.Stub methods (ping/executeAsync),
    // never inside Service.onBind() which executes on ActivityThread's main looper.
    override fun onBind(intent: Intent?): IBinder = providerBinder

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: Throwable) {}
        disconnectFromHub()
        serviceScope.cancel()
    }
}
