package org.nexus.client.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nexus.client.sample.service.NexusClientManager
import org.nexus.client.sample.ui.IntegrationTestScreen
import org.nexus.client.sample.ui.InteractiveScreen

class MainActivity : ComponentActivity() {

    private lateinit var clientManager: NexusClientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clientManager = NexusClientManager(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    surface = Color(0xFF111827),
                    background = Color(0xFF090D16)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClientAppShell(clientManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clientManager.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientAppShell(clientManager: NexusClientManager) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row {
                        Text(
                            text = "NEXUS Client Sample",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Explorer") },
                    label = { Text("Explorer", fontSize = 12.sp) },
                    modifier = Modifier.testTag("tab_explorer")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = "Tests") },
                    label = { Text("Auto Tests", fontSize = 12.sp) },
                    modifier = Modifier.testTag("tab_tests")
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> InteractiveScreen(clientManager)
                1 -> IntegrationTestScreen(clientManager)
            }
        }
    }
}
