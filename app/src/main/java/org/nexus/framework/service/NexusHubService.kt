package org.nexus.framework.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.nexus.framework.registry.AndroidReverseServiceBindAdapter
import org.nexus.framework.registry.CapabilityRegistry
import org.nexus.framework.registry.RegistrationCoordinator
import org.nexus.framework.registry.ReverseBindingRegistrar
import org.nexus.framework.router.AsyncRequestRouter
import org.nexus.framework.security.CallerIdentityVerifier
import org.nexus.interop.contract.INexusProvider
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.INexusService
import org.nexus.interop.contract.NexusCapability
import org.nexus.interop.contract.NexusRequest
import org.nexus.interop.contract.NexusResponse
import java.util.UUID

class NexusHubService : Service() {

    companion object {
        const val ACTION_BIND_HUB = "org.nexus.action.BIND_HUB"

        // Singleton reference for Dashboard UI telemetry integration
        @Volatile
        var instance: NexusHubService? = null
            private set
    }

    val registry = CapabilityRegistry()
    val registrationCoordinator = RegistrationCoordinator()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var router: AsyncRequestRouter
    private lateinit var callerVerifier: CallerIdentityVerifier
    private lateinit var reverseBindingRegistrar: ReverseBindingRegistrar

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_REPLACED || action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
                val pkg = intent.data?.schemeSpecificPart
                if (!pkg.isNullOrBlank()) {
                    callerVerifier.invalidatePackage(pkg)
                }
            }
        }
    }

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
                onDeathCallback = { _ ->
                    router.handleProviderCrashed(caller.packageName)
                }
            )
        }

        override fun registerProviderAsync(
            capabilities: List<NexusCapability>,
            providerComponent: ComponentName?,
            leaseDurationMs: Long,
            registrationCallback: INexusResponseCallback?
        ) {
            val callback = registrationCallback ?: return
            val regId = UUID.randomUUID().toString()

            if (providerComponent == null) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_BAD_REQUEST,
                        resultData = null,
                        errorMessage = "providerComponent must not be null"
                    )
                )
                return
            }

            if (capabilities.isEmpty()) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_BAD_REQUEST,
                        resultData = null,
                        errorMessage = "At least one capability is required for registration"
                    )
                )
                return
            }

            val caller = callerVerifier.resolveCurrentCallingIdentity()
            val authResult = callerVerifier.authorizeRegistration(
                caller = caller,
                providerComponent = providerComponent,
                capabilityIds = capabilities.map { it.capabilityId }
            )
            if (authResult is CallerIdentityVerifier.RegistrationAuthResult.Denied) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_PERMISSION_DENIED,
                        resultData = null,
                        errorMessage = authResult.reason
                    )
                )
                return
            }

            val serviceInfo = try {
                packageManager.getServiceInfo(providerComponent, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_CAPABILITY_NOT_FOUND,
                        resultData = null,
                        errorMessage = "Provider service component [${providerComponent.flattenToShortString()}] not found"
                    )
                )
                return
            } catch (e: Exception) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_EXECUTION_FAILED,
                        resultData = null,
                        errorMessage = "Failed to inspect provider component: ${e.message}"
                    )
                )
                return
            }

            if (!serviceInfo.exported || !serviceInfo.enabled) {
                safeSendRegistrationResponse(
                    callback,
                    NexusResponse(
                        requestId = regId,
                        isSuccess = false,
                        statusCode = NexusResponse.STATUS_PERMISSION_DENIED,
                        resultData = null,
                        errorMessage = "Provider service component [${providerComponent.flattenToShortString()}] is not exported or enabled"
                    )
                )
                return
            }

            reverseBindingRegistrar.initiateReverseRegistration(
                registrationId = regId,
                caller = caller,
                componentPackage = providerComponent.packageName,
                componentClass = providerComponent.className,
                providerComponent = providerComponent,
                capabilities = capabilities,
                leaseDurationMs = leaseDurationMs,
                onTerminalCallback = { response -> safeSendRegistrationResponse(callback, response) }
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

    private fun safeSendRegistrationResponse(callback: INexusResponseCallback, response: NexusResponse) {
        try {
            callback.onResponse(response)
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        callerVerifier = CallerIdentityVerifier(this)
        router = AsyncRequestRouter(registry, scope)
        reverseBindingRegistrar = ReverseBindingRegistrar(
            registry = registry,
            coordinator = registrationCoordinator,
            scope = scope,
            bindAdapter = AndroidReverseServiceBindAdapter(this),
            recoveryAuthenticator = { providerPkg, componentCls, expectedUid, capIds ->
                val auth = callerVerifier.verifyProviderPackageForRecovery(
                    packageName = providerPkg,
                    expectedUid = expectedUid,
                    capabilityIds = capIds
                )
                if (auth !is CallerIdentityVerifier.RegistrationAuthResult.Authorized) {
                    false
                } else {
                    try {
                        val info = packageManager.getServiceInfo(ComponentName(providerPkg, componentCls), 0)
                        info.exported && info.enabled
                    } catch (_: Throwable) {
                        false
                    }
                }
            },
            onProviderCrashed = { pkg -> router.handleProviderCrashed(pkg) }
        )

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
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: Throwable) {}
        registry.terminateAllLeases("NexusHubService destroyed")
        scope.cancel()
    }
}
