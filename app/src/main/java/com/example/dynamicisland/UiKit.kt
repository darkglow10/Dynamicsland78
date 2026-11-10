package com.example.dynamicisland

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            if (subtitle != null) Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        content()
    }
}

@Composable
fun SubHeader(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)

@Composable
fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline)

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = cs.onSurface, fontSize = 14.sp)
            Text(valueText, color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
fun RadioRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outline, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) cs.primaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}
