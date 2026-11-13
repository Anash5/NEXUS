package org.nexus.provider.text

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nexus.provider.text.service.ProviderActivationStore
import org.nexus.provider.text.service.TextProviderService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val persistedEnabled = TextProviderService.syncPersistedState(this)
        if (persistedEnabled && !TextProviderService.isServiceActive) {
            val resumeIntent = Intent(this, TextProviderService::class.java).apply {
                action = TextProviderService.ACTION_START
            }
            startService(resumeIntent)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981), // Emerald
                    surface = Color(0xFF111827),
                    background = Color(0xFF090D16)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProviderControlScreen(
                        onActivate = {
                            ProviderActivationStore(this).setActivationEnabled(true)
                            val intent = Intent(this, TextProviderService::class.java).apply {
                                action = TextProviderService.ACTION_START
                            }
                            startService(intent)
                        },
                        onDeactivate = {
                            ProviderActivationStore(this).setActivationEnabled(false)
                            val intent = Intent(this, TextProviderService::class.java).apply {
                                action = TextProviderService.ACTION_STOP
                            }
                            startService(intent)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderControlScreen(
    onActivate: () -> Unit,
    onDeactivate: () -> Unit
) {
    val state by TextProviderService.providerStatusFlow.collectAsStateWithLifecycle()

    val capabilitiesList = remember {
        listOf(
            "nexus.text.uppercase" to "Convert Text to Uppercase (v1.0.0)",
            "nexus.text.lowercase" to "Convert Text to Lowercase (v1.0.0)",
            "nexus.text.wordcount" to "Word & Character JSON Metrics (v1.0.0)",
            "nexus.text.reverse" to "Text Inversion / Reverser (v1.0.0)"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "NEXUS Text Provider",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Activation Control Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("provider_activation_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Provider Status",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val statusColor = when {
                                    state.isBoundToHub -> Color(0xFF10B981)
                                    state.isActivationEnabled -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                                val statusLabel = when {
                                    state.isBoundToHub -> "ACTIVE • BOUND TO HUB"
                                    state.isActivationEnabled -> "ENABLED • RECONNECTING"
                                    else -> "DEACTIVATED / IDLE"
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = statusColor
                                    )
                                }
                            }

                            Switch(
                                checked = state.isActivationEnabled || state.isBoundToHub,
                                onCheckedChange = { checked ->
                                    if (checked) onActivate() else onDeactivate()
                                },
                                modifier = Modifier.testTag("provider_activation_switch")
                            )
                        }

                        if (state.leaseToken != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Active Lease Token:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.leaseToken ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (state.errorState != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.errorState ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Real Metrics
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Requests Served", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "${state.requestsServed}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Last Execution", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = if (state.lastRequestLatencyMs > 0) "${state.lastRequestLatencyMs} ms" else "N/A",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Exposed Capabilities
            item {
                Text(
                    text = "Exposed Capabilities (4)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(capabilitiesList) { (capId, desc) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = capId, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
                            Text(text = desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
