package org.nexus.client.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nexus.client.sample.service.NexusClientManager
import org.nexus.interop.contract.NexusResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveScreen(
    clientManager: NexusClientManager,
    modifier: Modifier = Modifier
) {
    val isConnected by clientManager.isConnected.collectAsState()
    val capabilities by clientManager.discoveredCapabilities.collectAsState()
    val connectionError by clientManager.connectionError.collectAsState()

    var selectedCapabilityId by remember { mutableStateOf("nexus.text.uppercase") }
    var inputText by remember { mutableStateOf("Hello NEXUS Interoperability Framework!") }
    var isExecuting by remember { mutableStateOf(false) }
    var lastResponse by remember { mutableStateOf<NexusResponse?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Default selection to first discovered capability
    LaunchedEffect(capabilities) {
        if (capabilities.isNotEmpty() && !capabilities.any { it.capabilityId == selectedCapabilityId }) {
            selectedCapabilityId = capabilities.first().capabilityId
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("client_interactive_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hub Connection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("client_connection_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Framework Hub Connection",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "CONNECTED • org.nexus.framework" else "DISCONNECTED",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isConnected) {
                                Button(
                                    onClick = { clientManager.connect() },
                                    modifier = Modifier.testTag("btn_connect_hub"),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Connect", fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { clientManager.disconnect() },
                                    modifier = Modifier.testTag("btn_disconnect_hub"),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Disconnect", fontSize = 12.sp)
                                }
                                IconButton(
                                    onClick = { clientManager.refreshCapabilities() },
                                    modifier = Modifier.testTag("btn_refresh_capabilities")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
                    }

                    if (connectionError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = connectionError ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Capability Selection & Metadata
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Discovered Capabilities (${capabilities.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (capabilities.isEmpty()) {
                        Text(
                            text = if (isConnected) "No capabilities currently published. (Activate nexus-provider)" else "Connect to Framework Hub to discover capabilities.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                        ) {
                            val currentCap = capabilities.find { it.capabilityId == selectedCapabilityId }
                            OutlinedTextField(
                                value = "${currentCap?.displayName ?: selectedCapabilityId} (${currentCap?.version ?: "1.0.0"})",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .testTag("capability_dropdown"),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                capabilities.forEach { cap ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(cap.displayName, fontWeight = FontWeight.Bold)
                                                Text(
                                                    "${cap.capabilityId} • Provider: ${cap.providerPackage}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedCapabilityId = cap.capabilityId
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        val selectedCap = capabilities.find { it.capabilityId == selectedCapabilityId }
                        if (selectedCap != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Provider: ${selectedCap.providerPackage}", style = MaterialTheme.typography.labelSmall)
                                Text("Max: 64 KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // Quick Preset Inputs
        item {
            Text(
                text = "Preset Inputs:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { inputText = "Hello NEXUS Interoperability Framework!" },
                    label = { Text("Standard Greeting") }
                )
                AssistChip(
                    onClick = { inputText = "The quick brown fox jumps over the lazy dog" },
                    label = { Text("Pangram Sentence") }
                )
                AssistChip(
                    onClick = { inputText = "One two three four five six seven eight nine ten" },
                    label = { Text("10 Word Count") }
                )
            }
        }

        // Input Editor & Execute Button
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Request Payload Input",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 180.dp)
                            .testTag("client_payload_input"),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            isExecuting = true
                            coroutineScope.launch {
                                val resp = clientManager.executeCapability(selectedCapabilityId, inputText)
                                lastResponse = resp
                                isExecuting = false
                            }
                        },
                        enabled = isConnected && !isExecuting && capabilities.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_execute_request"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Executing via Binder IPC...")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dispatch Request Across Process")
                        }
                    }
                }
            }
        }

        // Real Response Card
        if (lastResponse != null) {
            item {
                val resp = lastResponse!!
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("client_response_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (resp.isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (resp.isSuccess) Color(0xFF10B981).copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${resp.statusCode} ${if (resp.isSuccess) "SUCCESS" else "ERROR"}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (resp.isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Latency: ${resp.executionTimeMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Req: ${resp.requestId.take(8)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (resp.verifiedProviderPackage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Verified Provider: ${resp.verifiedProviderPackage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Result Payload:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = resp.resultData ?: (resp.errorMessage ?: "No output data"),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                ),
                                color = if (resp.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
