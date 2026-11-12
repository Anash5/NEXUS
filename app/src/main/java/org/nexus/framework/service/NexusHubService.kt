package org.nexus.framework.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.router.AsyncRequestRouter
import org.nexus.framework.security.CallerIdentityVerifier
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.INexusService
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest

class NexusHubService : Service() {

    companion object {
        const val ACTION_BIND_HUB = "org.nexus.action.BIND_HUB"

        // Singleton reference for Dashboard UI telemetry integration
        @Volatile
        var instance: NexusHubService? = null
            private set
    }

    val registry = CapabilityRegistry()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var router: AsyncRequestRouter
    private lateinit var callerVerifier: CallerIdentityVerifier

    private val binder = object : INexusService.Stub() {

        override fun registerProvider(
            capabilities: List<NexusCapability>,
            providerBinder: INexusProvider,
            leaseDurationMs: Long
        ): String {
            val caller = callerVerifier.resolveCurrentCallingIdentity()

            // Verify capability namespace authorization (Correction 2)
            capabilities.forEach { cap ->
                if (!callerVerifier.isAuthorizedToRegisterCapability(caller, cap.capabilityId)) {
                    throw SecurityException("Caller [${caller.packageName}] is not authorized to register capability [${cap.capabilityId}]")
                }
            }

            return registry.registerProvider(
                caller = caller,
                capabilities = capabilities,
                providerBinder = providerBinder,
                leaseDurationMs = leaseDurationMs,
                onDeathCallback = { token ->
                    router.handleProviderCrashed(caller.packageName)
                }
            )
        }

        override fun renewLease(leaseToken: String): Boolean {
            val caller = callerVerifier.resolveCurrentCallingIdentity()
            return registry.renewLease(caller, leaseToken)
        }

        override fun unregisterProvider(leaseToken: String): Boolean {
            val caller = callerVerifier.resolveCurrentCallingIdentity()
            return registry.unregisterProvider(caller, leaseToken)
        }

        override fun discoverCapabilities(filterCategory: String?): List<NexusCapability> {
            return registry.getCapabilities(filterCategory)
        }

        override fun submitRequest(request: NexusRequest, clientCallback: INexusResponseCallback) {
            val caller = callerVerifier.resolveCurrentCallingIdentity()
            router.routeRequest(caller, request, clientCallback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        callerVerifier = CallerIdentityVerifier(this)
        router = AsyncRequestRouter(registry, scope)

        // Periodic lease expiration sweep
        scope.launch {
            while (isActive) {
                delay(10000)
                registry.cleanupExpiredLeases()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }
}
