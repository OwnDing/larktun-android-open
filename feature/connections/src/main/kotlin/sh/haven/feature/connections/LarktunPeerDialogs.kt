package sh.haven.feature.connections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.larktun.LarktunSshCredential
import sh.haven.core.tunnel.LarktunTailnetPeer
import sh.haven.core.ui.PasswordField
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val LARKTUN_PING_SAMPLE_COUNT = 10

private enum class LarktunSshAuthMode {
    PASSWORD,
    KEY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarktunPingSheet(
    peer: LarktunTailnetPeer,
    onDismiss: () -> Unit,
    pingSample: suspend (LarktunTailnetPeer, Int) -> LarktunPingSample,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var samples by remember(peer.id) { mutableStateOf(emptyList<LarktunPingSample>()) }
    var running by remember(peer.id) { mutableStateOf(true) }

    LaunchedEffect(peer.id) {
        samples = emptyList()
        running = true
        for (index in 1..LARKTUN_PING_SAMPLE_COUNT) {
            samples = (samples + pingSample(peer, index)).takeLast(LARKTUN_PING_SAMPLE_COUNT)
            if (index < LARKTUN_PING_SAMPLE_COUNT) {
                delay(1_000)
            }
        }
        running = false
    }

    val latest = samples.lastOrNull()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.connections_larktun_ping_title, peer.bestName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = peer.bestAddress ?: peer.primaryTailscaleIP.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = stringResource(
                            R.string.connections_larktun_ping_samples,
                            samples.size,
                            LARKTUN_PING_SAMPLE_COUNT,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = larktunPingModeLabel(latest?.connectionMode ?: LarktunPingConnectionMode.UNKNOWN),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = latest?.latencyMillis?.let(::formatLatencyMillis)
                        ?: stringResource(R.string.connections_larktun_ping_latest_empty),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (latest?.errorMessage.isNullOrBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            LarktunPingChart(samples = samples)

            val error = latest?.errorMessage?.takeIf { it.isNotBlank() }
            if (error != null) {
                Text(
                    text = stringResource(R.string.connections_larktun_ping_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (running) {
                Text(
                    text = stringResource(R.string.connections_larktun_ping_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LarktunPingChart(samples: List<LarktunPingSample>) {
    val chartColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val pointErrorColor = MaterialTheme.colorScheme.error
    val pointEmptyColor = MaterialTheme.colorScheme.outline
    val values = samples.mapNotNull { it.latencyMillis }
    val maxValue = (ceil(((values.maxOrNull() ?: 200.0).coerceAtLeast(200.0)) / 50.0) * 50.0)
        .coerceAtMost(10_000.0)
    val minLabel = stringResource(R.string.connections_larktun_ping_chart_min)
    val maxLabel = stringResource(R.string.connections_larktun_ping_chart_max, maxValue.roundToInt())

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        ) {
            val horizontalPadding = 12.dp.toPx()
            val verticalPadding = 18.dp.toPx()
            val chartWidth = size.width - horizontalPadding * 2
            val chartHeight = size.height - verticalPadding * 2

            for (step in 0..4) {
                val y = verticalPadding + chartHeight * (step / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(horizontalPadding, y),
                    end = Offset(size.width - horizontalPadding, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            var path = Path()
            var hasSegment = false
            samples.forEach { sample ->
                val x = horizontalPadding + chartWidth * ((sample.index - 1).coerceIn(0, 9) / 9f)
                val latency = sample.latencyMillis
                if (latency == null) {
                    if (hasSegment) {
                        drawPath(
                            path = path,
                            color = chartColor,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    path = Path()
                    hasSegment = false
                } else {
                    val y = verticalPadding + chartHeight * (1f - (latency / maxValue).toFloat().coerceIn(0f, 1f))
                    if (hasSegment) {
                        path.lineTo(x, y)
                    } else {
                        path.moveTo(x, y)
                        hasSegment = true
                    }
                }
            }
            if (hasSegment) {
                drawPath(
                    path = path,
                    color = chartColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            for (index in 1..LARKTUN_PING_SAMPLE_COUNT) {
                val sample = samples.firstOrNull { it.index == index }
                val x = horizontalPadding + chartWidth * ((index - 1) / 9f)
                if (sample?.latencyMillis != null) {
                    val y = verticalPadding + chartHeight * (1f - (sample.latencyMillis / maxValue).toFloat().coerceIn(0f, 1f))
                    drawCircle(color = chartColor, radius = 4.dp.toPx(), center = Offset(x, y))
                } else {
                    val y = verticalPadding + chartHeight
                    drawCircle(
                        color = if (sample?.errorMessage.isNullOrBlank()) pointEmptyColor else pointErrorColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = maxLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = minLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarktunSshDialog(
    peer: LarktunTailnetPeer,
    sshKeys: List<SshKey>,
    savedCredentials: List<LarktunSshCredential>,
    onDismiss: () -> Unit,
    onConnect: (username: String, password: String, rememberPassword: Boolean, keyId: String?) -> Unit,
    onForgetCredential: (LarktunSshCredential) -> Unit,
) {
    val host = peer.bestAddress.orEmpty()
    val peerCredentials = remember(host, savedCredentials) {
        savedCredentials.filter { it.host.equals(host, ignoreCase = true) && it.port == 22 }
    }
    val initialCredential = peerCredentials.firstOrNull()
    var authMode by rememberSaveable(peer.id) { mutableStateOf(LarktunSshAuthMode.PASSWORD) }
    var username by rememberSaveable(peer.id, initialCredential?.id) {
        mutableStateOf(initialCredential?.username.orEmpty())
    }
    var password by rememberSaveable(peer.id, initialCredential?.id) {
        mutableStateOf(initialCredential?.password.orEmpty())
    }
    var rememberPassword by rememberSaveable(peer.id, initialCredential?.id) {
        mutableStateOf(initialCredential != null)
    }
    var selectedKeyId by rememberSaveable(peer.id) { mutableStateOf<String?>(sshKeys.firstOrNull()?.id) }
    var keyPassphrase by rememberSaveable(peer.id, selectedKeyId) { mutableStateOf("") }
    var savedMenuExpanded by remember { mutableStateOf(false) }
    var keyMenuExpanded by remember { mutableStateOf(false) }

    val selectedKey = sshKeys.firstOrNull { it.id == selectedKeyId }
    val canConnect = username.isNotBlank() && when (authMode) {
        LarktunSshAuthMode.PASSWORD -> password.isNotBlank()
        LarktunSshAuthMode.KEY -> selectedKey != null && (!selectedKey.isEncrypted || keyPassphrase.isNotBlank())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_larktun_ssh_title, peer.bestName)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = host.ifBlank { stringResource(R.string.connections_larktun_no_address) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = authMode == LarktunSshAuthMode.PASSWORD,
                        onClick = { authMode = LarktunSshAuthMode.PASSWORD },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(stringResource(R.string.connections_larktun_ssh_auth_password)) },
                    )
                    SegmentedButton(
                        selected = authMode == LarktunSshAuthMode.KEY,
                        onClick = { authMode = LarktunSshAuthMode.KEY },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(stringResource(R.string.connections_larktun_ssh_auth_key)) },
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.common_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (authMode) {
                    LarktunSshAuthMode.PASSWORD -> {
                        SavedCredentialMenu(
                            credentials = peerCredentials,
                            expanded = savedMenuExpanded,
                            onExpandedChange = { savedMenuExpanded = it },
                            onSelect = { credential ->
                                username = credential.username
                                password = credential.password
                                rememberPassword = true
                                savedMenuExpanded = false
                            },
                            onForget = { credential ->
                                onForgetCredential(credential)
                                if (credential.username == username) {
                                    password = ""
                                    rememberPassword = false
                                }
                                savedMenuExpanded = false
                            },
                        )
                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.common_password),
                            imeAction = ImeAction.Go,
                            onImeAction = {
                                if (canConnect) onConnect(username.trim(), password, rememberPassword, null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = rememberPassword,
                                onCheckedChange = { rememberPassword = it },
                            )
                            Text(
                                text = stringResource(R.string.connections_larktun_ssh_remember_password),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    LarktunSshAuthMode.KEY -> {
                        KeySelectorMenu(
                            keys = sshKeys,
                            selectedKey = selectedKey,
                            expanded = keyMenuExpanded,
                            onExpandedChange = { keyMenuExpanded = it },
                            onSelect = { key ->
                                selectedKeyId = key.id
                                keyMenuExpanded = false
                            },
                        )
                        if (selectedKey?.isEncrypted == true) {
                            PasswordField(
                                value = keyPassphrase,
                                onValueChange = { keyPassphrase = it },
                                label = stringResource(R.string.connections_larktun_ssh_key_passphrase),
                                imeAction = ImeAction.Go,
                                onImeAction = {
                                    if (canConnect) onConnect(username.trim(), keyPassphrase, false, selectedKey.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canConnect,
                onClick = {
                    when (authMode) {
                        LarktunSshAuthMode.PASSWORD ->
                            onConnect(username.trim(), password, rememberPassword, null)
                        LarktunSshAuthMode.KEY ->
                            selectedKey?.let { onConnect(username.trim(), keyPassphrase, false, it.id) }
                    }
                },
            ) {
                Text(stringResource(R.string.connections_larktun_ssh_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun SavedCredentialMenu(
    credentials: List<LarktunSshCredential>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LarktunSshCredential) -> Unit,
    onForget: (LarktunSshCredential) -> Unit,
) {
    if (credentials.isEmpty()) return
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text(stringResource(R.string.connections_larktun_ssh_saved_account))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            credentials.forEachIndexed { index, credential ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(credential.username)
                            Text(
                                "${credential.host}:${credential.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(credential) },
                    trailingIcon = {
                        IconButton(onClick = { onForget(credential) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.connections_larktun_ssh_forget_saved),
                            )
                        }
                    },
                )
                if (index != credentials.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun KeySelectorMenu(
    keys: List<SshKey>,
    selectedKey: SshKey?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (SshKey) -> Unit,
) {
    if (keys.isEmpty()) {
        Text(
            text = stringResource(R.string.connections_larktun_ssh_no_keys),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Box {
        TextButton(onClick = { onExpandedChange(true) }) {
            Text(selectedKey?.label ?: stringResource(R.string.connections_larktun_ssh_select_key))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(key.label)
                            Text(
                                key.fingerprintSha256,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(key) },
                )
            }
        }
    }
    selectedKey?.let { key ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = key.keyType,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (key.isEncrypted) {
                Text(
                    text = stringResource(R.string.connections_larktun_ssh_key_encrypted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun larktunPingModeLabel(mode: LarktunPingConnectionMode): String =
    when (mode) {
        LarktunPingConnectionMode.DIRECT -> stringResource(R.string.connections_larktun_ping_mode_direct)
        LarktunPingConnectionMode.DERP -> stringResource(R.string.connections_larktun_ping_mode_derp)
        LarktunPingConnectionMode.PEER_RELAY -> stringResource(R.string.connections_larktun_ping_mode_peer_relay)
        LarktunPingConnectionMode.TSMP -> stringResource(R.string.connections_larktun_ping_mode_tsmp)
        LarktunPingConnectionMode.NOT_CONNECTED -> stringResource(R.string.connections_larktun_ping_mode_not_connected)
        LarktunPingConnectionMode.UNKNOWN -> stringResource(R.string.connections_larktun_ping_mode_unknown)
    }

private fun formatLatencyMillis(value: Double): String =
    "${value.roundToInt()} ms"
