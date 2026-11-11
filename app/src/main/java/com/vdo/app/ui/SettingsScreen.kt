package com.vdo.app.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vdo.app.data.Store
import com.vdo.app.download.Downloads

private val engines = listOf(
    "Google" to "https://www.google.com/search?q=",
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Bing" to "https://www.bing.com/search?q=",
)

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    var pinDialog by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(Store.searchEngine) }
    var hasPin by remember { mutableStateOf(Store.hasPin) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Section("Player")
        Toggle("Resume where you left off", "Continue videos and audio from the last position", Store.resumePlayback) { Store.resumePlayback = it }
        Toggle("Background play for videos", "Keep the sound playing when you leave the player", Store.backgroundVideo) { Store.backgroundVideo = it }
        Toggle("Automatic picture-in-picture", "Shrink the video into a floating window when you press Home", Store.autoPip) { Store.autoPip = it }
        Toggle("Swipe gestures", "Swipe for brightness, volume and seeking", Store.gestures) { Store.gestures = it }
        Stepper("Double-tap seek", Store.seekSeconds, 5..60, 5, { "$it s" }) { Store.seekSeconds = it }

        Section("Downloads")
        Stepper("Simultaneous downloads", Store.maxConcurrent, 1..5, 1, { "$it" }) { Store.maxConcurrent = it; Downloads.pump() }
        Stepper("Connections per download", Store.threads, 1..12, 1, { "$it" }) { Store.threads = it }
        Toggle("Download on Wi-Fi only", "Pause downloads on mobile data", Store.wifiOnly) { Store.wifiOnly = it; Downloads.pump() }
        Toggle("Notify when a download finishes", null, Store.notifyDone) { Store.notifyDone = it }

        Section("Browser")
        Text("Search engine", color = Color.White, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.padding(horizontal = 8.dp)) {
            engines.forEach { (name, url) ->
                TextButton(onClick = { engine = url; Store.searchEngine = url }) {
                    Text(name, color = if (engine == url) VdoColors.RoseGold else VdoColors.Muted,
                        fontWeight = if (engine == url) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Toggle("Block ads and trackers", "Skips requests to common ad networks", Store.adBlock) { Store.adBlock = it }
        Toggle("Save browsing history", null, Store.saveHistory) { Store.saveHistory = it }
        Action("Clear browsing data", "History, cookies and site storage") {
            Store.clearHistory()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            Toast.makeText(ctx, "Browsing data cleared", Toast.LENGTH_SHORT).show()
        }

        Section("Privacy")
        Action(if (hasPin) "Change private folder PIN" else "Set private folder PIN", "Protects downloads saved to the private folder") { pinDialog = true }

        Section("About")
        Text(
            "VDO 1.0.0 — Play · Stream · Download\n\nOnly download content you own or have permission to save. " +
                "VDO does not bypass DRM and does not download from services that prohibit it.",
            color = VdoColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (pinDialog) {
        var old by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text(if (hasPin) "Change PIN" else "Set PIN") },
            text = {
                Column {
                    if (hasPin) PinField("Current PIN", old) { old = it }
                    PinField("New PIN (4–8 digits)", new) { new = it }
                    error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        hasPin && !Store.checkPin(old) -> error = "Current PIN is wrong"
                        new.length < 4 -> error = "Use at least 4 digits"
                        else -> { Store.setPin(new); hasPin = true; pinDialog = false }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(8)) }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        title.uppercase(), color = VdoColors.RoseGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().clickable { checked = !checked; onChange(checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White)
            if (subtitle != null) Text(subtitle, color = VdoColors.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}

@Composable
private fun Stepper(title: String, initial: Int, range: IntRange, step: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(initial.coerceIn(range.first, range.last)) }
    fun set(v: Int) { value = v.coerceIn(range.first, range.last); onChange(value) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        IconButton(onClick = { set(value - step) }, enabled = value > range.first) {
            Icon(Icons.Rounded.Remove, "Less", tint = if (value > range.first) VdoColors.RoseGold else VdoColors.Muted)
        }
        Text(label(value), color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
        IconButton(onClick = { set(value + step) }, enabled = value < range.last) {
            Icon(Icons.Rounded.Add, "More", tint = if (value < range.last) VdoColors.RoseGold else VdoColors.Muted)
        }
    }
}

@Composable
private fun Action(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = Color.White)
        Text(subtitle, color = VdoColors.Muted, fontSize = 12.sp)
    }
}
