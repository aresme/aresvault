package com.ares.vault.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Password text field with show/hide toggle. Autofill/IME learning is disabled for it. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    trailingExtra: @Composable (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            androidx.compose.foundation.layout.Row {
                trailingExtra?.invoke()
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (visible) "Hide password" else "Show password",
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** zxcvbn score bar (0–4). */
@Composable
fun ScoreBar(score: Int, label: String?, modifier: Modifier = Modifier) {
    val color = when (score) {
        0, 1 -> Color(0xFFE5484D)
        2 -> Color(0xFFF5A524)
        3 -> Color(0xFF8BD34E)
        else -> Color(0xFF30C48D)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { (score + 1) / 5f },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun scoreLabel(score: Int) = when (score) {
    0 -> "Very weak"
    1 -> "Weak"
    2 -> "Fair"
    3 -> "Strong"
    else -> "Very strong"
}

/** App icon if available, otherwise a coloured letter (no favicon fetching — no network). */
@Composable
fun EntryAvatar(title: String, icon: ImageBitmap?, size: Dp = 40.dp) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)))
        return
    }
    val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    val hue = (title.lowercase().hashCode() and 0x7fffffff) % 360
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.hsl(hue.toFloat(), 0.45f, 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Formats a lockout countdown, e.g. "1:05". */
fun formatCountdown(millis: Long): String {
    val total = (millis + 999) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
