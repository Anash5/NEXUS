package com.example.nexus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.nexus.model.InteropNode
import com.example.nexus.model.PacketTrace
import com.example.nexus.ui.components.JsonSyntaxBox
import com.example.nexus.ui.components.ProtocolChip
import com.example.nexus.ui.theme.ElectricCyan
import com.example.nexus.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatcherScreen(
    nodes: List<InteropNode>,
    selectedTargetId: String,
    onSelectTargetId: (String) -> Unit,
    method: String,
    onMethodChange: (String) -> Unit,
    payload: String,
    onPayloadChange: (String) -> Unit,
    lastResult: PacketTrace?,
    isDispatching: Boolean,
    onDispatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedNode = nodes.find { it.id == selectedTargetId } ?: nodes.firstOrNull()
    var expandedNodeDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dispatcher_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "Universal Test Console & Dispatcher",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Inject arbitrary interop signals and test cross-protocol transformation live against registered nodes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Target Selector & Protocol
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Target Endpoint",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedNodeDropdown,
                        onExpandedChange = { expandedNodeDropdown = !expandedNodeDropdown }
                    ) {
                        OutlinedTextField(
                            value = "${selectedNode?.name ?: "Select Node"} (${selectedNode?.protocol?.label ?: ""})",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedNodeDropdown) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("target_node_selector"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedNodeDropdown,
                            onDismissRequest = { expandedNodeDropdown = false }
                        ) {
                            nodes.forEach { node ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(node.name)
                                            ProtocolChip(protocol = node.protocol)
                                        }
                                    },
                                    onClick = {
                                        onSelectTargetId(node.id)
                                        expandedNodeDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // RPC / Method input
                    Text(
                        text = "Method / Action / RPC Topic:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = method,
                        onValueChange = onMethodChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("method_input_field"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }
        }

        // Preset Templates Row
        item {
            Text(
                text = "Quick Payload Templates:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        onPayloadChange("""{
  "orderId": "ORD-${System.currentTimeMillis().toString().takeLast(5)}",
  "amount": 299.50,
  "currency": "USD",
  "merchantId": "NEXUS_CORE_MERCHANT",
  "capture": true
}""")
                    },
                    label = { Text("Payment JSON") },
                    leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.testTag("template_payment")
                )
                AssistChip(
                    onClick = {
                        onPayloadChange("""{
  "sensorId": "thermo_node_08",
  "metrics": {
    "temperature": 24.8,
    "humidity": 55.2,
    "vibrationLevel": 0.02
  },
  "timestamp": ${System.currentTimeMillis()}
}""")
                    },
                    label = { Text("Telemetry WS") },
                    leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.testTag("template_telemetry")
                )
                AssistChip(
                    onClick = {
                        onPayloadChange("""query GetUserProfile {
  user(id: "usr_9921") {
    name
    email
    interopScopes
    activeTenant
  }
}""")
                    },
                    label = { Text("GraphQL Query") },
                    leadingIcon = { Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.testTag("template_graphql")
                )
            }
        }

        // Payload Editor
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Payload Body",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${payload.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = payload,
                        onValueChange = onPayloadChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp)
                            .testTag("payload_input_editor"),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDispatch,
                        enabled = !isDispatching,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("execute_dispatch_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isDispatching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Routing Signal...")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dispatch Interop Signal", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Response Section
        if (lastResult != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dispatch_response_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (lastResult.isSuccess) EmeraldSuccess else MaterialTheme.colorScheme.error
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
                                            if (lastResult.isSuccess) EmeraldSuccess.copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${lastResult.statusCode} ${if (lastResult.isSuccess) "OK" else "ERROR"}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (lastResult.isSuccess) EmeraldSuccess else MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Latency: ${lastResult.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                            Text(
                                text = lastResult.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Transformed Response Payload:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        JsonSyntaxBox(
                            code = lastResult.responsePayload,
                            modifier = Modifier.testTag("response_payload_box")
                        )
                    }
                }
            }
        }
    }
}
