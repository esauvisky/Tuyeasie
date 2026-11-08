package com.emi.tuyeasie.ui

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.emi.tuyeasie.data.NearbyNetwork
import com.emi.tuyeasie.hass.HomeAssistantProtocol
import com.emi.tuyeasie.ui.theme.WorkingTint
import kotlinx.coroutines.delay

enum class RowTone { NEUTRAL, WORKING, OK, FAILED }

@Composable
fun HaCard(
    title: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                actions()
            }
        }
        Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
    }
}

@Composable
fun CardText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun CardAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EntityIcon(icon: ImageVector, tone: RowTone, imageUrl: String? = null, busy: Boolean = false) {
    val (bg, fg) = when (tone) {
        RowTone.NEUTRAL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        RowTone.WORKING -> MaterialTheme.colorScheme.surfaceVariant to WorkingTint
        RowTone.OK -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary
        RowTone.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.size(28.dp))
            } else {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            }
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 2.dp)
    }
}

// Home Assistant's entity row: round tinted icon, name, state line, trailing action.
@Composable
fun EntityRow(
    icon: @Composable () -> Unit,
    title: String,
    state: String?,
    tone: RowTone = RowTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val stateColor = when (tone) {
        RowTone.FAILED -> MaterialTheme.colorScheme.error
        RowTone.OK -> MaterialTheme.colorScheme.tertiary
        RowTone.NEUTRAL, RowTone.WORKING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { if (tone == RowTone.WORKING) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = stateColor) }
        }
        trailing()
    }
}

// The cloud reports the device's public WAN address in `ip`; only a private address is worth showing.
fun lanAddress(ip: String?): String? = ip?.takeIf(HomeAssistantProtocol::isRfc1918Address)

fun categoryIcon(category: String?): ImageVector = when (category) {
    "sp" -> Icons.Outlined.Videocam
    "cz", "pc" -> Icons.Outlined.Power
    "dj", "dd", "fwd", "xdd" -> Icons.Outlined.Lightbulb
    "kg", "tdq" -> Icons.Outlined.ToggleOn
    "wsdcg", "co2bj", "pir", "mcs", "ywbj", "rqbj" -> Icons.Outlined.Sensors
    else -> Icons.Outlined.Devices
}

@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun StatusLine(text: String, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Disclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    if (expanded) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun CopyField(label: String, value: String, onCopy: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        readOnly = true,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label") }
        }
    )
}

@Composable
fun SecretField(label: String, value: String, visible: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        readOnly = true,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Hide $label" else "Show $label"
                    )
                }
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label") }
            }
        }
    )
}

fun sensitiveClip(label: String, value: String): ClipData {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    return clip
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val NETWORK_REFRESH_MILLIS = 10_000L

// Polls nearby Wi-Fi while visible; 5 GHz-only networks stay listed but disabled when 2.4 GHz is required.
@Composable
fun NetworkList(
    networks: List<NearbyNetwork>,
    error: String?,
    selectedSsid: String?,
    require24Ghz: Boolean,
    enabled: Boolean,
    onRefresh: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh(false)
            delay(NETWORK_REFRESH_MILLIS)
        }
    }
    Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Nearby Wi-Fi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = { onRefresh(true) }) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh nearby networks")
        }
    }
    error?.let { InlineError(it) }
    if (error == null && networks.isEmpty()) CardText("No networks found yet. Scanning every 10 seconds.")
    networks.forEach { network ->
        val unsupported = require24Ghz && !network.has24Ghz
        val selected = network.ssid == selectedSsid
        EntityRow(
            icon = { EntityIcon(Icons.Outlined.Wifi, if (selected) RowTone.OK else RowTone.NEUTRAL) },
            title = network.ssid,
            state = when {
                unsupported -> "${network.signalDbm} dBm · 5 GHz only, Tuya needs 2.4 GHz"
                selected -> "${network.signalDbm} dBm · selected"
                else -> "${network.signalDbm} dBm"
            },
            tone = if (unsupported) RowTone.FAILED else RowTone.NEUTRAL,
            enabled = enabled && !unsupported,
            onClick = { onSelect(network.ssid) }
        )
    }
}
