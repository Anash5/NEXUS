package com.example.nexus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nexus.R
import com.example.nexus.model.AuthType
import com.example.nexus.model.ProtocolType
import com.example.nexus.ui.screens.*
import com.example.nexus.ui.theme.ElectricCyan
import com.example.nexus.ui.theme.EmeraldSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusApp(
    viewModel: NexusViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val pipelines by viewModel.pipelines.collectAsStateWithLifecycle()
    val packets by viewModel.packets.collectAsStateWithLifecycle()
    val stats by viewModel.systemStats.collectAsStateWithLifecycle()
    val isLiveActive by viewModel.isLiveStreamActive.collectAsStateWithLifecycle()

    val nodeSearchQuery by viewModel.nodeSearchQuery.collectAsStateWithLifecycle()
    val selectedProtocolFilter by viewModel.selectedProtocolFilter.collectAsStateWithLifecycle()

    val inspectorSearch by viewModel.inspectorSearch.collectAsStateWithLifecycle()
    val onlyErrorsFilter by viewModel.onlyErrorsFilter.collectAsStateWithLifecycle()
    val selectedPacket by viewModel.selectedPacket.collectAsStateWithLifecycle()

    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()

    val dispatcherTargetId by viewModel.dispatcherTargetNodeId.collectAsStateWithLifecycle()
    val dispatcherMethod by viewModel.dispatcherMethod.collectAsStateWithLifecycle()
    val dispatcherPayload by viewModel.dispatcherPayload.collectAsStateWithLifecycle()
    val lastDispatchResult by viewModel.lastDispatchResult.collectAsStateWithLifecycle()
    val isDispatching by viewModel.isDispatching.collectAsStateWithLifecycle()

    val codecSourceFormat by viewModel.codecSourceFormat.collectAsStateWithLifecycle()
    val codecTargetFormat by viewModel.codecTargetFormat.collectAsStateWithLifecycle()
    val codecInput by viewModel.codecInput.collectAsStateWithLifecycle()
    val codecOutput by viewModel.codecOutput.collectAsStateWithLifecycle()

    val showAddNodeDialog by viewModel.showAddNodeDialog.collectAsStateWithLifecycle()

    // Back handling to return to Dashboard
    if (currentScreen != NexusScreen.DASHBOARD) {
        BackHandler {
            viewModel.navigateTo(NexusScreen.DASHBOARD)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nexus_root_scaffold"),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_nexus_logo),
                            contentDescription = "NEXUS Logo",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "NEXUS",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ElectricCyan.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "FRAMEWORK",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ElectricCyan
                                    )
                                }
                            }
                            Text(
                                text = currentScreen.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Live Mesh Pulse indicator
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isLiveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiveActive) "LIVE" else "PAUSED",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isLiveActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("nexus_bottom_nav")
            ) {
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.DASHBOARD,
                    onClick = { viewModel.navigateTo(NexusScreen.DASHBOARD) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Mesh") },
                    label = { Text("Mesh", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_dashboard")
                )
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.NODES,
                    onClick = { viewModel.navigateTo(NexusScreen.NODES) },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Nodes") },
                    label = { Text("Nodes", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_nodes")
                )
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.PIPELINES,
                    onClick = { viewModel.navigateTo(NexusScreen.PIPELINES) },
                    icon = { Icon(Icons.Default.AccountTree, contentDescription = "Pipelines") },
                    label = { Text("Pipes", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_pipelines")
                )
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.DISPATCHER,
                    onClick = { viewModel.navigateTo(NexusScreen.DISPATCHER) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Test") },
                    label = { Text("Test", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_dispatcher")
                )
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.INSPECTOR,
                    onClick = { viewModel.navigateTo(NexusScreen.INSPECTOR) },
                    icon = { Icon(Icons.Default.Traffic, contentDescription = "Traffic") },
                    label = { Text("Traffic", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_inspector")
                )
                NavigationBarItem(
                    selected = currentScreen == NexusScreen.CODEC,
                    onClick = { viewModel.navigateTo(NexusScreen.CODEC) },
                    icon = { Icon(Icons.Default.Transform, contentDescription = "Codec") },
                    label = { Text("Codec", fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_item_codec")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                NexusScreen.DASHBOARD -> DashboardScreen(
                    stats = stats,
                    nodes = nodes,
                    recentPackets = packets,
                    onNavigate = { viewModel.navigateTo(it) },
                    onRunPipeline = {
                        viewModel.navigateTo(NexusScreen.PIPELINES)
                        viewModel.runPipeline(it)
                    },
                    onOpenAddNode = { viewModel.setShowAddNodeDialog(true) },
                    onSelectPacket = { viewModel.selectPacket(it) }
                )
                NexusScreen.NODES -> NodesScreen(
                    nodes = nodes,
                    searchQuery = nodeSearchQuery,
                    onSearchQueryChange = { viewModel.setNodeSearchQuery(it) },
                    selectedProtocol = selectedProtocolFilter,
                    onSelectProtocol = { viewModel.setProtocolFilter(it) },
                    onPingNode = { viewModel.pingNode(it) },
                    onToggleStatus = { viewModel.toggleNodeStatus(it) },
                    onDispatchToNode = { nodeId ->
                        viewModel.setDispatcherTarget(nodeId)
                        viewModel.navigateTo(NexusScreen.DISPATCHER)
                    },
                    onOpenAddNode = { viewModel.setShowAddNodeDialog(true) }
                )
                NexusScreen.PIPELINES -> PipelinesScreen(
                    pipelines = pipelines,
                    executionState = pipelineState,
                    onRunPipeline = { viewModel.runPipeline(it) },
                    onDismissExecution = { viewModel.resetPipelineState() }
                )
                NexusScreen.DISPATCHER -> DispatcherScreen(
                    nodes = nodes,
                    selectedTargetId = dispatcherTargetId,
                    onSelectTargetId = { viewModel.setDispatcherTarget(it) },
                    method = dispatcherMethod,
                    onMethodChange = { viewModel.setDispatcherMethod(it) },
                    payload = dispatcherPayload,
                    onPayloadChange = { viewModel.setDispatcherPayload(it) },
                    lastResult = lastDispatchResult,
                    isDispatching = isDispatching,
                    onDispatch = { viewModel.dispatchSignal() }
                )
                NexusScreen.INSPECTOR -> InspectorScreen(
                    packets = packets,
                    isLiveActive = isLiveActive,
                    onToggleLive = { viewModel.toggleLiveStream() },
                    onClearPackets = { viewModel.clearPackets() },
                    searchQuery = inspectorSearch,
                    onSearchChange = { viewModel.setInspectorSearch(it) },
                    onlyErrors = onlyErrorsFilter,
                    onToggleOnlyErrors = { viewModel.toggleOnlyErrors() },
                    selectedPacket = selectedPacket,
                    onSelectPacket = { viewModel.selectPacket(it) }
                )
                NexusScreen.CODEC -> CodecScreen(
                    sourceFormat = codecSourceFormat,
                    onSourceFormatChange = { viewModel.setCodecSourceFormat(it) },
                    targetFormat = codecTargetFormat,
                    onTargetFormatChange = { viewModel.setCodecTargetFormat(it) },
                    inputText = codecInput,
                    onInputChange = { viewModel.setCodecInput(it) },
                    outputText = codecOutput
                )
            }
        }
    }

    // Add Node Dialog
    if (showAddNodeDialog) {
        AddNodeDialog(
            onDismiss = { viewModel.setShowAddNodeDialog(false) },
            onConfirm = { name, desc, protocol, endpoint, auth, methods ->
                viewModel.registerNewNode(name, desc, protocol, endpoint, auth, methods)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        description: String,
        protocol: ProtocolType,
        endpointUri: String,
        authType: AuthType,
        methodsCsv: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(ProtocolType.REST) }
    var endpointUri by remember { mutableStateOf("https://") }
    var authType by remember { mutableStateOf(AuthType.BEARER) }
    var methodsCsv by remember { mutableStateOf("POST /sync, GET /health") }

    var expandedProtocolDropdown by remember { mutableStateOf(false) }
    var expandedAuthDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Register Interop Node",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && endpointUri.isNotBlank()) {
                        onConfirm(name, description, protocol, endpointUri, authType, methodsCsv)
                    }
                },
                enabled = name.isNotBlank() && endpointUri.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_node_button")
            ) {
                Text("Register")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Node Name") },
                    placeholder = { Text("e.g. Warehouse Inventory Mesh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_node_name")
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Real-time stock reservation service") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_node_desc")
                )

                // Protocol Dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedProtocolDropdown,
                    onExpandedChange = { expandedProtocolDropdown = !expandedProtocolDropdown }
                ) {
                    OutlinedTextField(
                        value = protocol.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProtocolDropdown) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedProtocolDropdown,
                        onDismissRequest = { expandedProtocolDropdown = false }
                    ) {
                        ProtocolType.values().forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    protocol = p
                                    expandedProtocolDropdown = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = endpointUri,
                    onValueChange = { endpointUri = it },
                    label = { Text("Endpoint URI") },
                    placeholder = { Text("e.g. grpc://inventory:50051") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_node_uri")
                )

                // Auth Dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedAuthDropdown,
                    onExpandedChange = { expandedAuthDropdown = !expandedAuthDropdown }
                ) {
                    OutlinedTextField(
                        value = authType.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Auth Handshake") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAuthDropdown) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedAuthDropdown,
                        onDismissRequest = { expandedAuthDropdown = false }
                    ) {
                        AuthType.values().forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                onClick = {
                                    authType = a
                                    expandedAuthDropdown = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = methodsCsv,
                    onValueChange = { methodsCsv = it },
                    label = { Text("Supported Methods (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_node_methods")
                )
            }
        }
    )
}
