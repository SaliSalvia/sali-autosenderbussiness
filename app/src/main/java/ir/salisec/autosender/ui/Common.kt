package ir.salisec.autosender.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wraps technical LTR tokens (domains, emails, PDF filenames, hostnames) so RTL BiDi never scrambles them. */
fun ltrIsolate(text: String): String = if (text.isBlank()) text else "\u2066$text\u2069"

@Composable
fun ScreenScaffold(
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    val mod = if (scrollable) {
        base.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)
    } else {
        base.padding(horizontal = 16.dp, vertical = 14.dp)
    }
    Column(
        modifier = mod,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
    }
}

@Composable
fun HeroBanner(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val c1 = MaterialTheme.colorScheme.primaryContainer
    val c2 = MaterialTheme.colorScheme.surface
    Surface(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(c1, c2)))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
fun SubTitle(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
fun Section(
    header: String,
    icon: ImageVector? = null,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    header,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!badge.isNullOrBlank()) {
                    Chip(badge, badgeColor)
                }
            }
            content()
        }
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    multiline: Boolean = false,
    singleLine: Boolean = !multiline,
    placeholder: String = "",
    isSecret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    var secretVisible by remember(isSecret) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (placeholder.isNotBlank()) {
                { Text(placeholder, style = MaterialTheme.typography.bodySmall) }
            } else null,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 10 else 1,
            singleLine = singleLine && !multiline,
            keyboardOptions = keyboardOptions,
            visualTransformation = if (isSecret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isSecret) {
                {
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(
                            imageVector = if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (secretVisible) "مخفی کردن رمز" else "نمایش رمز"
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

@Composable
fun BigButton(
    text: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .minimumInteractiveComponentSize(),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Chip(
    text: String,
    color: Color = GreenOk,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.5.sp
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
            modifier = modifier,
            content = content
        )
    } else {
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
            modifier = modifier,
            content = content
        )
    }
}

@Composable
fun LoadingBox(text: String = "لطفاً صبر کنید...") {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
