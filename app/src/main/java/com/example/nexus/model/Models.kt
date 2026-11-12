package com.example.nexus.model

import kotlinx.serialization.Serializable

enum class ProtocolType(val label: String, val badgeColorHex: Long) {
    REST("REST / HTTP", 0xFF0284C7),
    GRPC("gRPC / Proto", 0xFF8B5CF6),
    GRAPHQL("GraphQL", 0xFFE11D48),
    WEBSOCKET("WebSocket", 0xFF10B981),
    APP_INTENT("Android Intent", 0xFFF59E0B),
    EVENT_BUS("EventStream", 0xFF6366F1)
}

enum class NodeStatus(val label: String) {
    ONLINE("Online"),
    DEGRADED("Degraded"),
    OFFLINE("Offline"),
    CONNECTING("Connecting")
}

enum class AuthType(val label: String) {
    NONE("None / Open"),
    BEARER("Bearer Token"),
    HMAC("HMAC-SHA256"),
    MTLS("mTLS / Cert"),
    API_KEY("API Key (x-nexus-key)")
}

data class InteropNode(
    val id: String,
    val name: String,
    val description: String,
    val protocol: ProtocolType,
    val endpointUri: String,
    val version: String,
    val latencyMs: Int,
    val activeConnections: Int,
    val supportedMethods: List<String>,
    val status: NodeStatus,
    val authType: AuthType,
    val packetsProcessed: Long
)

data class PipelineStep(
    val id: String,
    val order: Int,
    val name: String,
    val actionType: String,
    val protocol: ProtocolType,
    val transformDetail: String
)

data class InteropPipeline(
    val id: String,
    val name: String,
    val description: String,
    val triggerProtocol: ProtocolType,
    val targetProtocol: ProtocolType,
    val steps: List<PipelineStep>,
    val isEnabled: Boolean,
    val totalExecutions: Long,
    val lastExecutedAt: String
)

data class PacketTrace(
    val id: String,
    val traceId: String,
    val sourceNode: String,
    val targetNode: String,
    val protocol: ProtocolType,
    val method: String,
    val statusCode: Int,
    val requestPayload: String,
    val responsePayload: String,
    val latencyMs: Int,
    val timestamp: String,
    val isSuccess: Boolean,
    val headers: Map<String, String> = emptyMap()
)

data class SystemStats(
    val activeNodesCount: Int,
    val totalNodesCount: Int,
    val totalPacketsCount: Long,
    val throughputPerSec: Double,
    val avgLatencyMs: Double,
    val errorRatePercentage: Double
)

enum class DataFormat(val label: String) {
    JSON("JSON"),
    YAML("YAML"),
    PROTOBUF_SCHEMA("Protobuf (proto3)"),
    GRAPHQL_SCHEMA("GraphQL Schema"),
    URL_ENCODED("URL Form-Encoded")
}
