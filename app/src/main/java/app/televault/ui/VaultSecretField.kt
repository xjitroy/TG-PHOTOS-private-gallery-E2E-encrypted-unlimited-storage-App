package app.televault.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.televault.crypto.PatternRecovery

/** The pattern is the portable recovery credential, unrelated to the phone's lock. */
@Composable internal fun VaultSecretField(value: String, changed: (String) -> Unit, title: String, enabled: Boolean = true, newPassword: Boolean = false, error: Boolean = false, fixedPattern: Boolean? = null) {
    val hostView = LocalView.current.rootView
    DisposableEffect(hostView) {
        // Includes Compose dialog windows on older Android versions.
        hostView.filterTouchesWhenObscured = true
        onDispose { /* Keep safe touch filtering for the lifetime of this window. */ }
    }
    var revealed by remember { mutableStateOf(false) }
    var pattern by remember(fixedPattern) { mutableStateOf(fixedPattern ?: (PatternRecovery.decode(value) != null)) }
    var nodes by remember { mutableStateOf(PatternRecovery.decode(value).orEmpty()) }
    LaunchedEffect(value) {
        PatternRecovery.decode(value)?.let {
            if (fixedPattern != false) {
                pattern = true
                nodes = it
            }
        }
        if (value.isEmpty()) nodes = ""
    }
    Column(Modifier.fillMaxWidth()) {
        if (fixedPattern == null && !newPassword) {
            Row {
                TextButton({
                    pattern = false
                    nodes = ""
                    changed("")
                }, enabled = enabled) { Text(if (!pattern) label("✓ Password", "✓ পাসওয়ার্ড") else label("Password", "পাসওয়ার্ড")) }
                TextButton({
                    pattern = true
                    nodes = ""
                    changed("")
                }, enabled = enabled) { Text(if (pattern) label("✓ Old pattern", "✓ পুরোনো প্যাটার্ন") else label("Recover old pattern", "পুরোনো প্যাটার্ন রিকভারি")) }
            }
        }
        if (!pattern) {
            OutlinedTextField(value, { changed(it.take(512)) }, Modifier.fillMaxWidth().semantics { contentType = if (newPassword) ContentType.NewPassword else ContentType.Password }, label = { Text(title) }, enabled = enabled, singleLine = true, isError = error, visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ revealed = !revealed }) { Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, label("Show / hide password", "পাসওয়ার্ড দেখুন / লুকান")) } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        } else {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(label("Legacy recovery only. Unlock, then replace this pattern with a strong password in Settings. New patterns are no longer supported.", "শুধু পুরোনো Drive রিকভারির জন্য। খুলে Settings থেকে শক্ত পাসওয়ার্ড সেট করুন। নতুন প্যাটার্ন বন্ধ।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PatternPad(nodes, enabled) { next ->
                nodes = next
                changed(if (PatternRecovery.valid(next)) PatternRecovery.encode(next) else "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${nodes.length}/9", style = MaterialTheme.typography.labelMedium)
                TextButton({
                    nodes = ""
                    changed("")
                }, enabled = enabled) { Text(label("Clear pattern", "প্যাটার্ন মুছুন")) }
            }
            if (error) Text(label("Patterns do not match", "প্যাটার্ন দুটি মেলেনি"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun PatternPad(nodes: String, enabled: Boolean, changed: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val callback by rememberUpdatedState(changed)
    val current by rememberUpdatedState(nodes)
    Box(
        Modifier.fillMaxWidth().height(210.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var drawing = ""
            fun add(point: Offset) {
                val x = (point.x / size.width * 3).toInt().coerceIn(0, 2)
                val y = (point.y / size.height * 3).toInt().coerceIn(0, 2)
                val center = Offset(size.width * (x + .5f) / 3, size.height * (y + .5f) / 3)
                if ((point - center).getDistance() < minOf(size.width / 3f, size.height / 3f) * .46f) {
                    drawing = PatternRecovery.append(drawing, y * 3 + x + 1)
                    callback(drawing)
                }
            }
            detectDragGestures(onDragStart = {
                drawing = current
                add(it)
            }, onDrag = { change, _ ->
                add(change.position)
                change.consume()
            })
        }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val positions = nodes.map { it - '1' }.map { Offset(size.width * (it % 3 + .5f) / 3, size.height * (it / 3 + .5f) / 3) }
            positions.zipWithNext().forEach { (from, to) -> drawLine(accent.copy(alpha = .6f), from, to, 5.dp.toPx()) }
        }
        Column(Modifier.fillMaxSize()) {
            repeat(3) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(3) { column ->
                        val dot = row * 3 + column + 1
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled) { callback(PatternRecovery.append(current, dot)) }, contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = if (('0' + dot) in nodes) accent else MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text("$dot", Modifier.padding(10.dp), color = if (('0' + dot) in nodes) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
