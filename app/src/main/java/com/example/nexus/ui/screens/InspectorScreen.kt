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
import com.example.nexus.model.PacketTrace
import com.example.nexus.ui.components.JsonSyntaxBox
import com.example.nexus.ui.components.ProtocolChip
import com.example.nexus.ui.theme.ElectricCyan
import com.example.nexus.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(
    packets: List<PacketTrace>,
    isLiveActive: Boolean,
    onToggleLive: () -> Unit,
    onClearPackets: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onlyErrors: Boolean,
    onToggleOnlyErrors: () -> Unit,
    selectedPacket: PacketTrace?,
    onSelectPacket: (PacketTrace?) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredPackets = packets.filter { p ->
        val matchesSearch = p.traceId.contains(searchQuery, ignoreCase = true) ||
                p.sourceNode.contains(searchQuery, ignoreCase = true) ||
                p.targetNode.contains(searchQuery, ignoreCase = true) ||
                p.method.contains(searchQuery, ignoreCase = true)
        val matchesError = !onlyErrors || !p.isSuccess
        matchesSearch && matchesError
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("inspector_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "Live Traffic & Packet Inspector",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Real-time telemetry stream of all cross-application packets traversing the Nexus interoperability bus.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Controls Row
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isLiveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isLiveActive) "STREAMING ACTIVE" else "STREAM PAUSED",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isLiveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = onToggleLive,
                                modifier = Modifier.testTag("toggle_stream_button"),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLiveActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isLiveActive) "Pause" else "Resume", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onClearPackets,
                                modifier = Modifier.testTag("clear_stream_button"),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search & Error toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            modifier = Modifier.weight(1f).testTag("inspector_search_field"),
                            placeholder = { Text("Filter trace, node, method...", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        FilterChip(
                            selected = onlyErrors,
                            onClick = onToggleOnlyErrors,
                            label = { Text("Errors Only", fontSize = 12.sp) },
                            leadingIcon = {
                                if (onlyErrors) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                            modifier = Modifier.testTag("filter_errors_chip")
                        )
                    }
                }
            }
        }

        // Trace list header
        item {
            Text(
                text = "Packets In Buffer (${filteredPackets.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (filteredPackets.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No packets matching current filter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Trace items
        items(filteredPackets, key = { it.id }) { packet ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPacket(packet) }
                    .testTag("traffic_packet_${packet.traceId}"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (packet.isSuccess) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (packet.isSuccess) EmeraldSuccess.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${packet.statusCode}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (packet.isSuccess) EmeraldSuccess else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = packet.traceId,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${packet.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            ProtocolChip(protocol = packet.protocol)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${packet.sourceNode} → ${packet.targetNode}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = packet.method,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = packet.timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Packet Detail Modal Dialog
    if (selectedPacket != null) {
        AlertDialog(
            onDismissRequest = { onSelectPacket(null) },
            confirmButton = {
                TextButton(
                    onClick = { onSelectPacket(null) },
                    modifier = Modifier.testTag("close_packet_dialog_button")
                ) {
                    Text("Close")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = ElectricCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Trace Inspector: ${selectedPacket.traceId}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Protocol: ${selectedPacket.protocol.label}")
                        Text("Latency: ${selectedPacket.latencyMs}ms")
                    }
                    Text("Route: ${selectedPacket.sourceNode} → ${selectedPacket.targetNode}")
                    Text("Method: ${selectedPacket.method}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)

                    HorizontalDivider()

                    Text(
                        text = "Request Payload:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    JsonSyntaxBox(code = selectedPacket.requestPayload)

                    Text(
                        text = "Response Payload:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    JsonSyntaxBox(code = selectedPacket.responsePayload)
                }
            }
        )
    }
}
