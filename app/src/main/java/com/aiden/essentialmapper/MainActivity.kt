package com.aiden.essentialmapper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap)

private suspend fun loadLaunchableApps(ctx: Context): List<AppInfo> =
    withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { ri ->
                AppInfo(
                    pkg = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
                )
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { ConfigScreen() } }
    }
}

@Composable
private fun ConfigScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyMap by Config.flow(ctx).collectAsState(initial = KeyMap())
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) } // null while loading
    var picker by remember { mutableStateOf<TapSlot?>(null) }

    LaunchedEffect(Unit) {
        Config.seedDefaultsIfEmpty(ctx)
        apps = loadLaunchableApps(ctx)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EssentialMapper", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Assign an app to each Essential Key tap count.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { openAccessibilitySettings(ctx) }) {
                Text("Open Accessibility settings")
            }
            HorizontalDivider()

            TapSlot.entries.forEach { slot ->
                val value = keyMap.forSlot(slot)
                SlotRow(
                    slot = slot,
                    valueLabel = slotDisplay(value, apps),
                    icon = apps?.find { it.pkg == value }?.icon,
                    enabled = apps != null,
                    onClick = { picker = slot },
                )
            }

            if (apps == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading apps…")
                }
            }
        }
    }

    val slot = picker
    val loaded = apps
    if (slot != null && loaded != null) {
        AppPickerDialog(
            title = slot.label,
            apps = loaded,
            current = keyMap.forSlot(slot),
            onPick = { pkg ->
                scope.launch { Config.set(ctx, slot, pkg) }
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

/** Resolve a stored slot value to a human label. */
private fun slotDisplay(value: String?, apps: List<AppInfo>?): String = when {
    value == null -> "None"
    value == Config.ACTION_FLASHLIGHT -> "Flashlight (toggle)"
    else -> apps?.find { it.pkg == value }?.label ?: value
}

@Composable
private fun SlotRow(
    slot: TapSlot,
    valueLabel: String,
    icon: ImageBitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.labelMedium)
                Text(valueLabel, style = MaterialTheme.typography.bodyLarge)
            }
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<AppInfo>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        PickRow("None", null, selected = current == null) { onPick(null) }
                    }
                    item {
                        PickRow(
                            "Flashlight (toggle)",
                            null,
                            selected = current == Config.ACTION_FLASHLIGHT,
                        ) { onPick(Config.ACTION_FLASHLIGHT) }
                    }
                    items(apps, key = { it.pkg }) { a ->
                        PickRow(a.label, a.icon, selected = current == a.pkg) { onPick(a.pkg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(
    label: String,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
