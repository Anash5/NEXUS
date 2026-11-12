package org.nexus.client.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import org.nexus.client.sample.testing.AutomatedIntegrationRunner
import org.nexus.client.sample.testing.TestCaseResult

@Composable
fun IntegrationTestScreen(
    clientManager: NexusClientManager,
    modifier: Modifier = Modifier
) {
    val isConnected by clientManager.isConnected.collectAsState()
    var isRunningTests by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<TestCaseResult>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    val runner = remember { AutomatedIntegrationRunner(clientManager) }

    val passedCount = testResults.count { it.isPassed }
    val totalCount = testResults.size

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("client_test_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Test Header & Run Action Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("test_runner_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Automated Integration Test Suite",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Runs complete end-to-end IPC assertions against real Binder services across processes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (totalCount > 0) {
                            Text(
                                text = "Score: $passedCount / $totalCount Passed",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (passedCount == totalCount) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        } else {
                            Text(
                                text = "Ready to test 8 test cases",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                isRunningTests = true
                                testResults = emptyList()
                                coroutineScope.launch {
                                    val currentList = mutableListOf<TestCaseResult>()
                                    runner.runAllIntegrationTests { res ->
                                        currentList.add(res)
                                        testResults = currentList.toList()
                                    }
                                    isRunningTests = false
                                }
                            },
                            enabled = isConnected && !isRunningTests,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("btn_run_tests")
                        ) {
                            if (isRunningTests) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Testing...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Tests")
                            }
                        }
                    }

                    if (!isConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠ Please connect to Framework Hub in the Explorer tab before running tests.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Test Results List
        items(testResults) { test ->
            Card(
                modifier = Modifier.fillMaxWidth().testTag("test_item_${test.testName.replace(" ", "_")}"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (test.isPassed) Color(0xFF10B981).copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (test.isPassed) Color(0xFF10B981).copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (test.isPassed) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (test.isPassed) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = test.testName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${test.durationMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = test.details,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
