package com.example.nexus.data

import com.example.nexus.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nexus.framework.service.NexusHubService
import org.nexus.interop.contract.INexusResponseCallback
import org.nexus.interop.contract.NexusRequest
import org.nexus.interop.contract.NexusResponse
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class NexusRepository {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _nodes = MutableStateFlow<List<InteropNode>>(createInitialNodes())
    val nodes: StateFlow<List<InteropNode>> = _nodes.asStateFlow()

    private val _pipelines = MutableStateFlow<List<InteropPipeline>>(createInitialPipelines())
    val pipelines: StateFlow<List<InteropPipeline>> = _pipelines.asStateFlow()

    private val _packets = MutableStateFlow<List<PacketTrace>>(createInitialPackets())
    val packets: StateFlow<List<PacketTrace>> = _packets.asStateFlow()

    private val _isLiveStreamActive = MutableStateFlow(true)
    val isLiveStreamActive: StateFlow<Boolean> = _isLiveStreamActive.asStateFlow()

    private val _systemStats = MutableStateFlow(
        SystemStats(
            activeNodesCount = 6,
            totalNodesCount = 6,
            totalPacketsCount = 14280L,
            throughputPerSec = 48.6,
            avgLatencyMs = 18.4,
            errorRatePercentage = 0.04
        )
    )
    val systemStats: StateFlow<SystemStats> = _systemStats.asStateFlow()

    init {
        startTelemetryLoop()
        observeRealHubEvents()
    }

    private fun observeRealHubEvents() {
        scope.launch {
            while (true) {
                val hub = NexusHubService.instance
                if (hub != null) {
                    // Collect live leases
                    launch {
                        hub.registry.activeLeasesFlow.collect { leases ->
                            updateNodesFromRealLeases(leases)
                        }
                    }

                    // Collect live telemetry traces from real Binder calls
                    launch {
                        hub.router.telemetryStream.collect { traces ->
                            traces.firstOrNull()?.let { latestTrace ->
                                val realPacket = PacketTrace(
                                    id = latestTrace.requestId,
                                    traceId = latestTrace.traceId,
                                    sourceNode = latestTrace.clientPackage,
                                    targetNode = latestTrace.providerPackage,
                                    protocol = ProtocolType.APP_INTENT,
                                    method = latestTrace.capabilityId,
                                    statusCode = latestTrace.statusCode,
                                    requestPayload = "/* Real Binder IPC Transaction */",
                                    responsePayload = if (latestTrace.statusCode == 200) "/* Success 200 via AIDL */" else "/* Code ${latestTrace.statusCode} */",
                                    latencyMs = latestTrace.executionTimeMs.toInt(),
                                    timestamp = timeFormat.format(Date(latestTrace.timestamp)),
                                    isSuccess = latestTrace.statusCode == 200,
                                    headers = mapOf(
                                        "x-ipc-transport" to "Linux Kernel Binder",
                                        "x-payload-limit" to "64 KB",
                                        "x-request-id" to latestTrace.requestId
                                    )
                                )
                                _packets.update { current ->
                                    if (current.none { it.id == realPacket.id }) {
                                        (listOf(realPacket) + current).take(100)
                                    } else current
                                }
                                updateLiveStats()
                            }
                        }
                    }
                    break
                }
                delay(1000)
            }
        }
    }

    private fun updateNodesFromRealLeases(leases: List<org.nexus.framework.registry.ActiveProviderLease>) {
        val baseNodes = createInitialNodes()
        val realNodes = leases.map { lease ->
            InteropNode(
                id = "node-${lease.verifiedPackage}",
                name = "${lease.verifiedPackage} (Binder IPC)",
                description = "Live registered provider. UID: ${lease.verifiedUid}, Lease: ${lease.leaseToken.take(8)}...",
                protocol = ProtocolType.APP_INTENT,
                endpointUri = "aidl://org.nexus.framework/INexusService",
                version = "v1.0.0",
                latencyMs = 2,
                activeConnections = 1,
                supportedMethods = lease.capabilities.keys.toList(),
                status = NodeStatus.ONLINE,
                authType = AuthType.NONE,
                packetsProcessed = 1L
            )
        }
        _nodes.value = realNodes + baseNodes
        updateLiveStats()
    }

    private fun startTelemetryLoop() {
        scope.launch {
            while (true) {
                delay(3000)
                if (_isLiveStreamActive.value) {
                    emitSimulatedPacket()
                    updateLiveStats()
                }
            }
        }
    }

    private fun emitSimulatedPacket() {
        val currentNodes = _nodes.value
        if (currentNodes.size < 2) return

        val srcIndex = Random.nextInt(currentNodes.size)
        var tgtIndex = Random.nextInt(currentNodes.size)
        if (tgtIndex == srcIndex) {
            tgtIndex = (srcIndex + 1) % currentNodes.size
        }
        val src = currentNodes[srcIndex]
        val tgt = currentNodes[tgtIndex]

        val isError = Random.nextInt(100) < 3
        val statusCode = if (isError) 500 else 200
        val method = tgt.supportedMethods.randomOrNull() ?: "POST /interop/sync"
        val latency = Random.nextInt(5, 30)

        val trace = PacketTrace(
            id = UUID.randomUUID().toString(),
            traceId = "trc-${UUID.randomUUID().toString().take(8)}",
            sourceNode = src.name,
            targetNode = tgt.name,
            protocol = tgt.protocol,
            method = method,
            statusCode = statusCode,
            requestPayload = """{ "source": "${src.name}", "event": "$method", "timestamp": ${System.currentTimeMillis()} }""",
            responsePayload = if (!isError) {
                """{ "status": "ACK", "latency_ms": $latency, "nexus_route": "ok" }"""
            } else {
                """{ "error": "RPC_DISPATCH_TIMEOUT", "code": $statusCode }"""
            },
            latencyMs = latency,
            timestamp = timeFormat.format(Date()),
            isSuccess = !isError,
            headers = mapOf(
                "x-nexus-trace" to "trc-${UUID.randomUUID().toString().take(8)}",
                "content-type" to "application/json",
                "x-nexus-auth" to tgt.authType.name
            )
        )

        _packets.update { current ->
            (listOf(trace) + current).take(100)
        }

        _nodes.update { list ->
            list.map { node ->
                if (node.id == tgt.id) {
                    node.copy(packetsProcessed = node.packetsProcessed + 1)
                } else node
            }
        }
    }

    private fun updateLiveStats() {
        val currentNodes = _nodes.value
        val onlineCount = currentNodes.count { it.status == NodeStatus.ONLINE }
        val recentPackets = _packets.value.take(20)
        val avgLatency = if (recentPackets.isNotEmpty()) recentPackets.map { it.latencyMs }.average() else 14.0
        val errors = recentPackets.count { !it.isSuccess }
        val errorRate = if (recentPackets.isNotEmpty()) (errors.toDouble() / recentPackets.size) * 100 else 0.0

        _systemStats.update { current ->
            current.copy(
                activeNodesCount = onlineCount,
                totalNodesCount = currentNodes.size,
                totalPacketsCount = current.totalPacketsCount + 1,
                throughputPerSec = 40.0 + Random.nextDouble(0.0, 10.0),
                avgLatencyMs = String.format(Locale.US, "%.1f", avgLatency).toDoubleOrNull() ?: 14.0,
                errorRatePercentage = String.format(Locale.US, "%.2f", errorRate).toDoubleOrNull() ?: 0.0
            )
        }
    }

    fun toggleLiveStream() {
        _isLiveStreamActive.update { !it }
    }

    fun clearPackets() {
        _packets.value = emptyList()
    }

    fun dispatchPacket(
        sourceNodeName: String,
        targetNode: InteropNode,
        method: String,
        payload: String,
        customHeaders: Map<String, String>
    ): PacketTrace {
        val latency = Random.nextInt(targetNode.latencyMs.coerceAtLeast(3), targetNode.latencyMs + 15)
        val traceId = "trc-${UUID.randomUUID().toString().take(8)}"

        val isSyntaxValid = payload.trim().startsWith("{") && payload.trim().endsWith("}") || payload.isNotBlank()
        val statusCode = if (isSyntaxValid) 200 else 422
        val isSuccess = statusCode == 200

        val responsePayload = if (isSuccess) {
            """{
  "status": "DISPATCH_SUCCESS",
  "traceId": "$traceId",
  "node": "${targetNode.name}",
  "protocol": "${targetNode.protocol.name}",
  "endpoint": "${targetNode.endpointUri}",
  "executionTimeMs": $latency,
  "transformedPayload": {
    "payloadEcho": true,
    "receivedBytes": ${payload.length},
    "nexusBridgeVerdict": "ROUTED_CLEAN"
  }
}"""
        } else {
            """{
  "status": "INVALID_INTEROP_PAYLOAD",
  "error": "Malformed payload structure for target protocol ${targetNode.protocol.name}",
  "traceId": "$traceId"
}"""
        }

        val newTrace = PacketTrace(
            id = UUID.randomUUID().toString(),
            traceId = traceId,
            sourceNode = sourceNodeName,
            targetNode = targetNode.name,
            protocol = targetNode.protocol,
            method = method,
            statusCode = statusCode,
            requestPayload = payload,
            responsePayload = responsePayload,
            latencyMs = latency,
            timestamp = timeFormat.format(Date()),
            isSuccess = isSuccess,
            headers = mapOf(
                "x-nexus-trace" to traceId,
                "x-nexus-protocol" to targetNode.protocol.name,
                "x-nexus-auth" to targetNode.authType.name
            ) + customHeaders
        )

        _packets.update { listOf(newTrace) + it }
        _nodes.update { list ->
            list.map {
                if (it.id == targetNode.id) it.copy(packetsProcessed = it.packetsProcessed + 1) else it
            }
        }
        return newTrace
    }

    fun addNode(
        name: String,
        description: String,
        protocol: ProtocolType,
        endpointUri: String,
        authType: AuthType,
        methods: List<String>
    ) {
        val newNode = InteropNode(
            id = "node-${UUID.randomUUID().toString().take(6)}",
            name = name,
            description = description,
            protocol = protocol,
            endpointUri = endpointUri,
            version = "v1.0.0",
            latencyMs = Random.nextInt(8, 25),
            activeConnections = 1,
            supportedMethods = if (methods.isNotEmpty()) methods else listOf("POST /sync", "GET /status"),
            status = NodeStatus.ONLINE,
            authType = authType,
            packetsProcessed = 0L
        )
        _nodes.update { it + newNode }
    }

    fun pingNode(nodeId: String) {
        _nodes.update { list ->
            list.map { node ->
                if (node.id == nodeId) {
                    val newLatency = Random.nextInt(4, 25)
                    node.copy(
                        latencyMs = newLatency,
                        status = NodeStatus.ONLINE
                    )
                } else node
            }
        }
    }

    fun toggleNodeStatus(nodeId: String) {
        _nodes.update { list ->
            list.map { node ->
                if (node.id == nodeId) {
                    val newStatus = when (node.status) {
                        NodeStatus.ONLINE -> NodeStatus.OFFLINE
                        NodeStatus.OFFLINE -> NodeStatus.ONLINE
                        NodeStatus.DEGRADED -> NodeStatus.ONLINE
                        NodeStatus.CONNECTING -> NodeStatus.ONLINE
                    }
                    node.copy(status = newStatus)
                } else node
            }
        }
    }

    suspend fun executePipeline(
        pipelineId: String,
        onStepProgress: (stepIndex: Int, stepName: String, log: String) -> Unit
    ): Boolean {
        val pipeline = _pipelines.value.find { it.id == pipelineId } ?: return false

        for (i in pipeline.steps.indices) {
            val step = pipeline.steps[i]
            val stepLatency = Random.nextLong(150, 400)
            delay(stepLatency)
            onStepProgress(
                i,
                step.name,
                "✓ Executed [${step.actionType}] via ${step.protocol.label} (took ${stepLatency}ms) -> ${step.transformDetail}"
            )
        }

        _pipelines.update { list ->
            list.map { p ->
                if (p.id == pipelineId) {
                    p.copy(
                        totalExecutions = p.totalExecutions + 1,
                        lastExecutedAt = timeFormat.format(Date())
                    )
                } else p
            }
        }
        return true
    }

    fun convertFormat(source: String, from: DataFormat, to: DataFormat): String {
        val clean = source.trim()
        if (clean.isBlank()) return "/* Empty payload */"

        return when {
            from == to -> clean
            from == DataFormat.JSON && to == DataFormat.YAML -> {
                clean.lines().map { line ->
                    line.replace("\"", "")
                        .replace("{", "")
                        .replace("}", "")
                        .replace(",", "")
                }.filter { it.isNotBlank() }.joinToString("\n")
            }
            from == DataFormat.JSON && to == DataFormat.PROTOBUF_SCHEMA -> {
                """syntax = "proto3";
package nexus.interop;

message UniversalInteropPayload {
  string source_entity = 1;
  string target_route = 2;
  int64 timestamp_epoch = 3;
  bytes raw_transformed_data = 4;
  map<string, string> nexus_metadata = 5;
}"""
            }
            from == DataFormat.JSON && to == DataFormat.GRAPHQL_SCHEMA -> {
                """type UniversalInteropPayload {
  id: ID!
  sourceEntity: String!
  targetRoute: String!
  timestamp: Float!
  status: InteropStatus!
  attributes: [NexusAttribute!]!
}

enum InteropStatus {
  ONLINE
  PROCESSED
  FORWARDED
}"""
            }
            from == DataFormat.JSON && to == DataFormat.URL_ENCODED -> {
                "source=NexusClient&event=interop_sync&auth_type=bearer&version=1.0"
            }
            to == DataFormat.JSON -> {
                """{
  "interop_converted": true,
  "source_format": "${from.label}",
  "target_format": "${to.label}",
  "data_preview": "$clean"
}"""
            }
            else -> {
                "# Converted from ${from.label} to ${to.label}\n$clean"
            }
        }
    }

    private fun createInitialNodes(): List<InteropNode> = listOf(
        InteropNode(
            id = "node-pay",
            name = "PayBridge Gateway",
            description = "Financial settlement & payment tokenization bridge",
            protocol = ProtocolType.GRPC,
            endpointUri = "grpc://payments.nexus.mesh:50051",
            version = "v2.4.0",
            latencyMs = 12,
            activeConnections = 142,
            supportedMethods = listOf("AuthorizeCharge", "TokenizeCard", "RefundTransaction"),
            status = NodeStatus.ONLINE,
            authType = AuthType.MTLS,
            packetsProcessed = 3180L
        ),
        InteropNode(
            id = "node-auth",
            name = "Identity Exchange (OIDC)",
            description = "Universal cross-application token exchange and OAuth2 federation",
            protocol = ProtocolType.REST,
            endpointUri = "https://auth.nexus.mesh/api/v1/oauth",
            version = "v3.1.2",
            latencyMs = 19,
            activeConnections = 310,
            supportedMethods = listOf("POST /oauth/token", "GET /userinfo", "POST /claims/validate"),
            status = NodeStatus.ONLINE,
            authType = AuthType.BEARER,
            packetsProcessed = 5892L
        ),
        InteropNode(
            id = "node-iot",
            name = "IoT Telemetry Stream",
            description = "Real-time edge device sensor multiplexer and telemetry pipeline",
            protocol = ProtocolType.WEBSOCKET,
            endpointUri = "wss://telemetry.nexus.mesh/stream",
            version = "v1.9.1",
            latencyMs = 8,
            activeConnections = 850,
            supportedMethods = listOf("DEVICE_TELEMETRY", "PING_HEARTBEAT", "OTA_NOTIFY"),
            status = NodeStatus.ONLINE,
            authType = AuthType.API_KEY,
            packetsProcessed = 14820L
        ),
        InteropNode(
            id = "node-intent",
            name = "Android Intent Dispatcher",
            description = "Local on-device inter-process communication & deep-linking bus",
            protocol = ProtocolType.APP_INTENT,
            endpointUri = "intent://org.nexus.framework/action/interop#Intent",
            version = "v1.0.0",
            latencyMs = 3,
            activeConnections = 1,
            supportedMethods = listOf("ACTION_INTEROP_SEND", "ACTION_DATA_RECEIVE", "ACTION_SYNC_BROADCAST"),
            status = NodeStatus.ONLINE,
            authType = AuthType.NONE,
            packetsProcessed = 420L
        ),
        InteropNode(
            id = "node-graph",
            name = "Catalog GraphQL Mesh",
            description = "Unified federated entity graph for inventory, users & assets",
            protocol = ProtocolType.GRAPHQL,
            endpointUri = "https://graph.nexus.mesh/graphql",
            version = "v4.0.0",
            latencyMs = 24,
            activeConnections = 95,
            supportedMethods = listOf("query CatalogMesh", "mutation MutateItem", "subscription OnStockChange"),
            status = NodeStatus.ONLINE,
            authType = AuthType.HMAC,
            packetsProcessed = 2190L
        ),
        InteropNode(
            id = "node-events",
            name = "Universal Event Stream",
            description = "Kafka/Pulsar compatible pub-sub event fabric with schema registry",
            protocol = ProtocolType.EVENT_BUS,
            endpointUri = "events://broker.nexus.mesh:9092/topics",
            version = "v2.8.0",
            latencyMs = 15,
            activeConnections = 520,
            supportedMethods = listOf("PublishEvent", "SubscribeTopic", "AckMessage"),
            status = NodeStatus.ONLINE,
            authType = AuthType.BEARER,
            packetsProcessed = 8400L
        )
    )

    private fun createInitialPipelines(): List<InteropPipeline> = listOf(
        InteropPipeline(
            id = "pipe-order-sync",
            name = "Omni-Commerce Order Sync",
            description = "Android Mobile Cart Intent -> JSON to Protobuf transformation -> gRPC Payment Gateway -> EventStream Notification",
            triggerProtocol = ProtocolType.APP_INTENT,
            targetProtocol = ProtocolType.EVENT_BUS,
            steps = listOf(
                PipelineStep("s1", 1, "Capture App Intent", "CAPTURE", ProtocolType.APP_INTENT, "Read local cart payload"),
                PipelineStep("s2", 2, "Transform to Proto3", "TRANSFORM", ProtocolType.GRPC, "Normalize JSON dictionary into binary Protobuf"),
                PipelineStep("s3", 3, "gRPC Settle Authorization", "DISPATCH", ProtocolType.GRPC, "Authorize transaction with PayBridge"),
                PipelineStep("s4", 4, "Publish Order Event", "NOTIFY", ProtocolType.EVENT_BUS, "Emit OrderCompleted to EventStream topic")
            ),
            isEnabled = true,
            totalExecutions = 384,
            lastExecutedAt = "Just now"
        ),
        InteropPipeline(
            id = "pipe-telemetry-alert",
            name = "Telemetry Normalization & Anomaly Alert",
            description = "High-frequency WebSocket sensor stream -> Schema Validation -> Threshold Filter -> Webhook Dispatch",
            triggerProtocol = ProtocolType.WEBSOCKET,
            targetProtocol = ProtocolType.REST,
            steps = listOf(
                PipelineStep("s1", 1, "Ingest WS Stream", "INGEST", ProtocolType.WEBSOCKET, "Receive compressed binary telemetry frame"),
                PipelineStep("s2", 2, "Schema Sanitize", "VALIDATE", ProtocolType.REST, "Validate against Nexus Schema Registry"),
                PipelineStep("s3", 3, "Anomaly Detection Rule", "FILTER", ProtocolType.EVENT_BUS, "Evaluate thermal & latency threshold"),
                PipelineStep("s4", 4, "Dispatch Incident Webhook", "DISPATCH", ProtocolType.REST, "POST alert to on-call monitoring endpoint")
            ),
            isEnabled = true,
            totalExecutions = 1290,
            lastExecutedAt = "2m ago"
        ),
        InteropPipeline(
            id = "pipe-identity-bridge",
            name = "Cross-App Identity Token Exchange",
            description = "Federated OAuth2 token exchange with claims enrichment and secure IPC broadcast",
            triggerProtocol = ProtocolType.REST,
            targetProtocol = ProtocolType.APP_INTENT,
            steps = listOf(
                PipelineStep("s1", 1, "Exchange Token", "AUTH", ProtocolType.REST, "Post authorization_code to Identity Exchange"),
                PipelineStep("s2", 2, "Enrich User Claims", "TRANSFORM", ProtocolType.GRAPHQL, "Query profile metadata from Catalog GraphQL"),
                PipelineStep("s3", 3, "Local IPC Broadcast", "DISPATCH", ProtocolType.APP_INTENT, "Broadcast ACTION_SESSION_SYNC to registered apps")
            ),
            isEnabled = true,
            totalExecutions = 712,
            lastExecutedAt = "15m ago"
        )
    )

    private fun createInitialPackets(): List<PacketTrace> = listOf(
        PacketTrace(
            id = "trace-1",
            traceId = "trc-8f92a10c",
            sourceNode = "Android Intent Dispatcher",
            targetNode = "PayBridge Gateway",
            protocol = ProtocolType.GRPC,
            method = "AuthorizeCharge",
            statusCode = 200,
            requestPayload = """{ "amount": 149.99, "currency": "USD", "merchant": "NEXUS_STORE" }""",
            responsePayload = """{ "transactionId": "tx_90192348", "authorized": true, "authCode": "NEX_OK" }""",
            latencyMs = 14,
            timestamp = "20:38:12.102",
            isSuccess = true,
            headers = mapOf("content-type" to "application/grpc", "x-nexus-auth" to "MTLS")
        ),
        PacketTrace(
            id = "trace-2",
            traceId = "trc-3b77e21a",
            sourceNode = "IoT Telemetry Stream",
            targetNode = "Universal Event Stream",
            protocol = ProtocolType.EVENT_BUS,
            method = "PublishEvent",
            statusCode = 200,
            requestPayload = """{ "sensorId": "sensor_mesh_409", "temperature": 23.4, "pressureKpa": 101.3 }""",
            responsePayload = """{ "offset": 194821, "partition": 3, "status": "COMMITTED" }""",
            latencyMs = 9,
            timestamp = "20:38:13.415",
            isSuccess = true,
            headers = mapOf("x-kafka-topic" to "telemetry.normalized", "x-nexus-auth" to "BEARER")
        ),
        PacketTrace(
            id = "trace-3",
            traceId = "trc-5e199d30",
            sourceNode = "Identity Exchange (OIDC)",
            targetNode = "Catalog GraphQL Mesh",
            protocol = ProtocolType.GRAPHQL,
            method = "query CatalogMesh",
            statusCode = 200,
            requestPayload = """query { user(id: "usr_42") { roles permissions activeTenant } }""",
            responsePayload = """{ "data": { "user": { "roles": ["ADMIN", "INTEROP_OPERATOR"] } } }""",
            latencyMs = 22,
            timestamp = "20:38:14.020",
            isSuccess = true,
            headers = mapOf("content-type" to "application/json", "x-nexus-auth" to "HMAC")
        )
    )
}
