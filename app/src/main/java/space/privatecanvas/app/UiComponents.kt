package space.privatecanvas.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun OrganicBackground(
    theme: CanvasTheme,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val highContrast = LocalHighContrast.current
    Box(modifier.background(theme.backgroundColor())) {
        Canvas(Modifier.matchParentSize().alpha(if (highContrast) .3f else .58f)) {
            drawCircle(
                color = theme.accentColor().copy(alpha = .12f),
                radius = size.minDimension * .23f,
                center = Offset(-size.minDimension * .02f, size.height * .23f),
            )
            drawCircle(
                color = theme.accentColor().copy(alpha = .10f),
                radius = size.minDimension * .22f,
                center = Offset(size.width * .92f, size.height * .78f),
            )
            drawCircle(
                color = Color.White.copy(alpha = .42f),
                radius = size.minDimension * .32f,
                center = Offset(size.width * .50f, size.height * .45f),
            )
        }
        content()
    }
}

@Composable
fun AcrylicSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AcrylicShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val highContrast = LocalHighContrast.current
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = if (highContrast) .96f else .68f), shape)
            .border(if (highContrast) 1.5.dp else 1.dp, if (highContrast) MutedInk else Color.White.copy(alpha = .86f), shape)
            .padding(1.dp),
        content = content,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, CircleShape))
}

@Composable
fun StatusPill(label: String, color: Color = Sage, modifier: Modifier = Modifier) {
    val highContrast = LocalHighContrast.current
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = if (highContrast) .98f else .72f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(if (highContrast) 1.5.dp else 1.dp, if (highContrast) Ink else Color.White.copy(alpha = .9f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color)
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Ink)
        }
    }
}

@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Sage,
            contentColor = Color.White,
            disabledContainerColor = Sage.copy(alpha = .38f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalHighContrast.current
    Surface(
        modifier = modifier.size(46.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (highContrast) .98f else .64f),
        border = androidx.compose.foundation.BorderStroke(if (highContrast) 1.5.dp else 1.dp, if (highContrast) Ink else Color.White.copy(alpha = .9f)),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, description, tint = Ink, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
fun DockAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .then(if (selected) Modifier.background(Sage.copy(alpha = .16f), CircleShape) else Modifier),
    ) {
        Icon(icon, description, tint = if (enabled) Ink else MutedInk.copy(alpha = .45f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val highContrast = LocalHighContrast.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.White.copy(alpha = if (highContrast && enabled) .98f else if (enabled) .68f else .34f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(if (highContrast) 1.5.dp else 1.dp, if (highContrast) MutedInk else Color.White.copy(alpha = .9f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (enabled) Ink else MutedInk, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = if (enabled) Ink else MutedInk)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                }
            }
            trailing?.invoke(this)
        }
    }
}
