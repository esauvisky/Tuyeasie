package com.emi.tuyeasie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alibaba.fastjson.JSON
import com.emi.tuyeasie.data.ManualDataPoint
import com.emi.tuyeasie.data.TuyaDataPointSchema
import com.emi.tuyeasie.ui.theme.LocalHaBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPointsScreen(state: OnboardingUiState, viewModel: OnboardingViewModel, onBack: () -> Unit) {
    val device = checkNotNull(state.activatedDevice) { "Data points without a device" }
    val points = checkNotNull(state.capabilities) { "Data points without a device schema" }.dataPoints
    val bar = LocalHaBar.current
    val anyBusy = state.manualDataPoints.values.any { it.reading || it.writing }
    var editing by remember { mutableStateOf<TuyaDataPointSchema?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(device.deviceId) { viewModel.refreshDataPoints() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bar.container, titleContentColor = bar.content,
                    navigationIconContentColor = bar.content, actionIconContentColor = bar.content),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text("Data points")
                        Text(device.name, style = MaterialTheme.typography.bodyMedium, color = bar.subtitle)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshDataPoints, enabled = !anyBusy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Read all data points")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val (controls, status) = points.sortedBy { it.id.toInt() }.partition { it.writable }
            if (controls.isNotEmpty()) DataPointSection("Controls", controls, state, viewModel) { editing = it }
            if (status.isNotEmpty()) DataPointSection("Status", status, state, viewModel) { editing = it }
        }
    }
    editing?.let { schema -> EditDataPointDialog(schema, checkNotNull(state.manualDataPoints[schema.id]), viewModel) { editing = null } }
}

@Composable
private fun DataPointSection(
    title: String,
    schemas: List<TuyaDataPointSchema>,
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onEdit: (TuyaDataPointSchema) -> Unit
) {
    HaCard(title = title) {
        schemas.forEachIndexed { index, schema ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DataPointRow(schema, checkNotNull(state.manualDataPoints[schema.id]) { "No state for data point ${schema.id}" }, viewModel, onEdit)
        }
    }
}

@Composable
private fun DataPointRow(schema: TuyaDataPointSchema, point: ManualDataPoint, viewModel: OnboardingViewModel, onEdit: (TuyaDataPointSchema) -> Unit) {
    val busy = point.reading || point.writing
    val editor = schema.editorType
    var enumOpen by remember { mutableStateOf(false) }
    val onClick: (() -> Unit)? = when {
        busy || point.value == null && editor != "string" -> null
        editor == "boolean" -> { { viewModel.setDataPoint(schema.id, (point.value != true).toString()) } }
        editor == "enum" -> { { enumOpen = true } }
        editor != null -> { { onEdit(schema) } }
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(schema.id, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.weight(1f)) {
            Text(displayName(schema), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                point.error ?: listOf(schema.code, schema.type).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (point.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.widthIn(max = 160.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                editor == "boolean" && point.value is Boolean ->
                    Switch(checked = point.value, onCheckedChange = { viewModel.setDataPoint(schema.id, it.toString()) })
                editor == "enum" -> Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ValueText(formatValue(schema, point.value), MaterialTheme.colorScheme.primary)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = enumOpen, onDismissRequest = { enumOpen = false }) {
                        enumRange(schema).forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                enumOpen = false
                                viewModel.setDataPoint(schema.id, option)
                            })
                        }
                    }
                }
                editor != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueText(formatValue(schema, point.value), MaterialTheme.colorScheme.primary)
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                else -> ValueText(formatValue(schema, point.value), MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ValueText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
}

@Composable
private fun EditDataPointDialog(schema: TuyaDataPointSchema, point: ManualDataPoint, viewModel: OnboardingViewModel, onDismiss: () -> Unit) {
    val editor = checkNotNull(schema.editorType) { "Data point ${schema.id} has no editor" }
    val property = JSON.parseObject(schema.propertyJson)
    val numeric = editor == "integer" || editor == "number"
    var text by remember(schema.id) { mutableStateOf(point.value?.toString().orEmpty()) }
    val hint = if (numeric) listOfNotNull("${property.get("min")} to ${property.get("max")}", property.getString("unit")?.takeIf { it.isNotBlank() }).joinToString(" ") else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayName(schema)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(schema.code) },
                supportingText = hint?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); viewModel.setDataPoint(schema.id, text.trim()) }, enabled = text.isNotBlank()) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// The schema's name is often just the code; a code like "basic_flip" reads better as "Basic flip".
private fun displayName(schema: TuyaDataPointSchema): String =
    if (schema.name.isNotBlank() && schema.name != schema.code) schema.name
    else schema.code.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun enumRange(schema: TuyaDataPointSchema): List<String> {
    val range = checkNotNull(JSON.parseObject(schema.propertyJson).getJSONArray("range")) { "Enum DP ${schema.id} has no range" }
    return (0 until range.size).map(range::getString)
}

private fun formatValue(schema: TuyaDataPointSchema, value: Any?): String {
    if (value == null) return "Not read"
    if (value is Boolean) return if (value) "On" else "Off"
    val unit = JSON.parseObject(schema.propertyJson).getString("unit")?.takeIf { it.isNotBlank() }
    return if (unit != null && value is Number) "$value $unit" else value.toString()
}
