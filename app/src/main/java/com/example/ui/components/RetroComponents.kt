package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.RetroBlack
import com.example.ui.theme.RetroBorder
import com.example.ui.theme.RetroPaper

@Composable
fun RetroTile(
    modifier: Modifier = Modifier,
    background: Color = RetroBlack,
    contentColor: Color = RetroPaper,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier.border(1.dp, RetroBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = background,
        contentColor = contentColor,
        onClick = onClick ?: {}
    ) {
        Box(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
fun RetroMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    RetroTile(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, color = RetroPaper.copy(alpha = 0.55f))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        }
    }
}

@Composable
fun RetroControlTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    RetroTile(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).background(RetroPaper, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = RetroBlack, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                supportingText?.let { Text(it, fontSize = 10.sp, color = RetroPaper.copy(alpha = 0.55f)) }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun RetroSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}
