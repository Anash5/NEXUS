package com.example.nexus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nexus.data.NexusRepository
import com.example.nexus.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NexusScreen(val title: String) {
    DASHBOARD("Nexus Mesh"),
    NODES("Node Registry"),
    PIPELINES("Interop Pipelines"),
    DISPATCHER("Test Console"),
    INSPECTOR("Traffic Inspector"),
    CODEC("Universal Codec")
}

data class PipelineExecutionState(
    val pipelineId: String? = null,
    val isRunning: Boolean = false,
    val currentStepIndex: Int = 0,
    val stepName: String = "",
    val executionLogs: List<String> = emptyList(),
    val isComplete: Boolean = false
)

class NexusViewModel(
    private val repository: NexusRepository = NexusRepository()
) : ViewModel() {

    val nodes: StateFlow<List<InteropNode>> = repository.nodes
    val pipelines: StateFlow<List<InteropPipeline>> = repository.pipelines
    val packets: StateFlow<List<PacketTrace>> = repository.packets
    val systemStats: StateFlow<SystemStats> = repository.systemStats
    val isLiveStreamActive: StateFlow<Boolean> = repository.isLiveStreamActive

    private val _currentScreen = MutableStateFlow(NexusScreen.DASHBOARD)
    val currentScreen: StateFlow<NexusScreen> = _currentScreen.asStateFlow()

    // Node Search & Filter
    private val _nodeSearchQuery = MutableStateFlow("")
    val nodeSearchQuery: StateFlow<String> = _nodeSearchQuery.asStateFlow()

    private val _selectedProtocolFilter = MutableStateFlow<ProtocolType?>(null)
    val selectedProtocolFilter: StateFlow<ProtocolType?> = _selectedProtocolFilter.asStateFlow()

    // Inspector Filter
    private val _inspectorSearch = MutableStateFlow("")
    val inspectorSearch: StateFlow<String> = _inspectorSearch.asStateFlow()

    private val _onlyErrorsFilter = MutableStateFlow(false)
    val onlyErrorsFilter: StateFlow<Boolean> = _onlyErrorsFilter.asStateFlow()

    private val _selectedPacket = MutableStateFlow<PacketTrace?>(null)
    val selectedPacket: StateFlow<PacketTrace?> = _selectedPacket.asStateFlow()

    // Pipeline Execution
    private val _pipelineState = MutableStateFlow(PipelineExecutionState())
    val pipelineState: StateFlow<PipelineExecutionState> = _pipelineState.asStateFlow()

    // Dispatcher Console State
    private val _dispatcherTargetNodeId = MutableStateFlow("node-pay")
    val dispatcherTargetNodeId: StateFlow<String> = _dispatcherTargetNodeId.asStateFlow()

    private val _dispatcherMethod = MutableStateFlow("AuthorizeCharge")
    val dispatcherMethod: StateFlow<String> = _dispatcherMethod.asStateFlow()

    private val _dispatcherPayload = MutableStateFlow("""{
  "client_origin": "NEXUS_SANDBOX",
  "transaction_id": "tx_auto_001",
  "amount": 250.00,
  "currency": "USD",
  "metadata": {
    "interop_version": "1.0",
    "route": "direct"
  }
}""")
    val dispatcherPayload: StateFlow<String> = _dispatcherPayload.asStateFlow()

    private val _lastDispatchResult = MutableStateFlow<PacketTrace?>(null)
    val lastDispatchResult: StateFlow<PacketTrace?> = _lastDispatchResult.asStateFlow()

    private val _isDispatching = MutableStateFlow(false)
    val isDispatching: StateFlow<Boolean> = _isDispatching.asStateFlow()

    // Universal Codec Transformer State
    private val _codecSourceFormat = MutableStateFlow(DataFormat.JSON)
    val codecSourceFormat: StateFlow<DataFormat> = _codecSourceFormat.asStateFlow()

    private val _codecTargetFormat = MutableStateFlow(DataFormat.PROTOBUF_SCHEMA)
    val codecTargetFormat: StateFlow<DataFormat> = _codecTargetFormat.asStateFlow()

    private val _codecInput = MutableStateFlow("""{
  "orderId": "ORD-94812",
  "customer": "Alex Mercer",
  "items": [
    { "sku": "NEX-CHIP-9", "qty": 2, "price": 49.99 }
  ],
  "isExpress": true
}""")
    val codecInput: StateFlow<String> = _codecInput.asStateFlow()

    private val _codecOutput = MutableStateFlow("")
    val codecOutput: StateFlow<String> = _codecOutput.asStateFlow()

    // Add Node Dialog State
    private val _showAddNodeDialog = MutableStateFlow(false)
    val showAddNodeDialog: StateFlow<Boolean> = _showAddNodeDialog.asStateFlow()

    init {
        updateCodecOutput()
    }

    fun navigateTo(screen: NexusScreen) {
        _currentScreen.value = screen
    }

    fun setNodeSearchQuery(query: String) {
        _nodeSearchQuery.value = query
    }

    fun setProtocolFilter(protocol: ProtocolType?) {
        _selectedProtocolFilter.value = protocol
    }

    fun setInspectorSearch(query: String) {
        _inspectorSearch.value = query
    }

    fun toggleOnlyErrors() {
        _onlyErrorsFilter.update { !it }
    }

    fun selectPacket(packet: PacketTrace?) {
        _selectedPacket.value = packet
    }

    fun pingNode(nodeId: String) {
        repository.pingNode(nodeId)
    }

    fun toggleNodeStatus(nodeId: String) {
        repository.toggleNodeStatus(nodeId)
    }

    fun toggleLiveStream() {
        repository.toggleLiveStream()
    }

    fun clearPackets() {
        repository.clearPackets()
    }

    fun setDispatcherTarget(nodeId: String) {
        _dispatcherTargetNodeId.value = nodeId
        val node = nodes.value.find { it.id == nodeId }
        if (node != null && node.supportedMethods.isNotEmpty()) {
            _dispatcherMethod.value = node.supportedMethods.first()
        }
    }

    fun setDispatcherMethod(method: String) {
        _dispatcherMethod.value = method
    }

    fun setDispatcherPayload(payload: String) {
        _dispatcherPayload.value = payload
    }

    fun dispatchSignal() {
        val target = nodes.value.find { it.id == _dispatcherTargetNodeId.value } ?: nodes.value.firstOrNull() ?: return
        viewModelScope.launch {
            _isDispatching.value = true
            val result = repository.dispatchPacket(
                sourceNodeName = "Nexus Sandbox Console",
                targetNode = target,
                method = _dispatcherMethod.value,
                payload = _dispatcherPayload.value,
                customHeaders = mapOf("x-nexus-client" to "Android-M3-Console")
            )
            _lastDispatchResult.value = result
            _isDispatching.value = false
        }
    }

    fun runPipeline(pipelineId: String) {
        viewModelScope.launch {
            _pipelineState.value = PipelineExecutionState(
                pipelineId = pipelineId,
                isRunning = true,
                currentStepIndex = 0,
                stepName = "Initializing Pipeline...",
                executionLogs = listOf("⚡ Initializing Interop Pipeline [$pipelineId]..."),
                isComplete = false
            )

            val success = repository.executePipeline(pipelineId) { stepIndex, stepName, log ->
                _pipelineState.update { current ->
                    current.copy(
                        currentStepIndex = stepIndex + 1,
                        stepName = stepName,
                        executionLogs = current.executionLogs + log
                    )
                }
            }

            _pipelineState.update { current ->
                current.copy(
                    isRunning = false,
                    isComplete = success,
                    executionLogs = current.executionLogs + (if (success) "✓ Pipeline executed successfully with 0 protocol translation faults." else "✗ Pipeline failed.")
                )
            }
        }
    }

    fun resetPipelineState() {
        _pipelineState.value = PipelineExecutionState()
    }

    fun setCodecSourceFormat(format: DataFormat) {
        _codecSourceFormat.value = format
        updateCodecOutput()
    }

    fun setCodecTargetFormat(format: DataFormat) {
        _codecTargetFormat.value = format
        updateCodecOutput()
    }

    fun setCodecInput(input: String) {
        _codecInput.value = input
        updateCodecOutput()
    }

    private fun updateCodecOutput() {
        _codecOutput.value = repository.convertFormat(
            source = _codecInput.value,
            from = _codecSourceFormat.value,
            to = _codecTargetFormat.value
        )
    }

    fun setShowAddNodeDialog(show: Boolean) {
        _showAddNodeDialog.value = show
    }

    fun registerNewNode(
        name: String,
        description: String,
        protocol: ProtocolType,
        endpointUri: String,
        authType: AuthType,
        methodsCsv: String
    ) {
        val methods = methodsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        repository.addNode(
            name = name,
            description = description,
            protocol = protocol,
            endpointUri = endpointUri,
            authType = authType,
            methods = methods
        )
        _showAddNodeDialog.value = false
    }
}
