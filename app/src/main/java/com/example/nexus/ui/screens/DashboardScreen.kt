package com.example.nexus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nexus.model.InteropNode
import com.example.nexus.model.NodeStatus
import com.example.nexus.model.PacketTrace
import com.example.nexus.model.SystemStats
import com.example.nexus.ui.NexusScreen
import com.example.nexus.ui.components.MetricCard
import com.example.nexus.ui.components.ProtocolChip
import com.example.nexus.ui.components.StatusBadge
import com.example.nexus.ui.theme.ElectricCyan
import com.example.nexus.ui.theme.EmeraldSuccess
import com.example.nexus.ui.theme.NeonIndigo
import com.example.nexus.ui.theme.NeonViolet

@Composable
fun DashboardScreen(
    stats: SystemStats,
    nodes: List<InteropNode>,
    recentPackets: List<PacketTrace>,
    onNavigate: (NexusScreen) -> Unit,
    onRunPipeline: (String) -> Unit,
    onOpenAddNode: () -> Unit,
    onSelectPacket: (PacketTrace) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ElectricCyan.copy(alpha = 0.4f))
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(EmeraldSuccess)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NEXUS INTEROP MESH • OPERATIONAL",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldSuccess
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Universal Application Interoperability",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Seamless protocol translation and high-velocity packet routing between REST, gRPC, WebSocket, GraphQL, and Android Intents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Metrics Grid (2x2)
        item {
            Text(
                text = "Mesh Telemetry",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "Active Nodes",
                        value = "${stats.activeNodesCount} / ${stats.totalNodesCount}",
                        icon = Icons.Default.Hub,
                        accentColor = ElectricCyan,
                        modifier = Modifier.weight(1f),
                        subtitle = "100% Mesh Health"
                    )
                    MetricCard(
                        title = "Throughput",
                        value = "${String.format("%.1f", stats.throughputPerSec)} m/s",
                        icon = Icons.Default.Speed,
                        accentColor = NeonIndigo,
                        modifier = Modifier.weight(1f),
                        subtitle = "Real-time Stream"
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "Mean Latency",
                        value = "${stats.avgLatencyMs} ms",
                        icon = Icons.Default.Timer,
                        accentColor = NeonViolet,
                        modifier = Modifier.weight(1f),
                        subtitle = "Sub-20ms SLA"
                    )
                    MetricCard(
                        title = "Error Rate",
                        value = "${stats.errorRatePercentage}%",
                        icon = Icons.Default.CheckCircle,
                        accentColor = EmeraldSuccess,
                        modifier = Modifier.weight(1f),
                        subtitle = "${stats.totalPacketsCount} total msgs"
                    )
                }
            }
        }

        // Quick Actions
        item {
            Text(
                text = "Interoperability Actions",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onNavigate(NexusScreen.DISPATCHER) },
                    modifier = Modifier.weight(1f).testTag("action_dispatch_signal"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dispatch", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        onNavigate(NexusScreen.PIPELINES)
                        onRunPipeline("pipe-order-sync")
                    },
                    modifier = Modifier.weight(1f).testTag("action_run_pipeline"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run Pipe", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpenAddNode,
                    modifier = Modifier.weight(1f).testTag("action_add_node"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Node", fontSize = 12.sp)
                }
            }
        }

        // Connected Services Overview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connected Protocol Nodes (${nodes.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = { onNavigate(NexusScreen.NODES) },
                    modifier = Modifier.testTag("view_all_nodes_button")
                ) {
                    Text("View All", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Preview of Nodes
        items(nodes.take(3)) { node ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(NexusScreen.NODES) }
                    .testTag("node_preview_${node.id}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            ProtocolChip(protocol = node.protocol)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = node.endpointUri,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        StatusBadge(status = node.status)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${node.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Recent Live Traffic Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Packet Stream",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = { onNavigate(NexusScreen.INSPECTOR) },
                    modifier = Modifier.testTag("view_all_traffic_button")
                ) {
                    Text("Inspect All", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Recent Traffic List
        items(recentPackets.take(4)) { packet ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPacket(packet) }
                    .testTag("packet_trace_${packet.traceId}"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (packet.isSuccess) EmeraldSuccess else MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${packet.sourceNode} → ${packet.targetNode}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "${packet.method} • ${packet.timestamp}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    ProtocolChip(protocol = packet.protocol)
                }
            }
        }
    }
}
