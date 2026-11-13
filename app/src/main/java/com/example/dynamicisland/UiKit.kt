package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val TR = Locale("tr", "TR")

/** "BOYUT" gibi tamamı büyük harfli başlıkları "Boyut" şekline çevirir; zaten düzgün yazılmışsa dokunmaz. */
private fun softCase(s: String): String =
    if (s.length > 1 && s.any { it.isLetter() } && s == s.uppercase(TR))
        s.lowercase(TR).replaceFirstChar { it.titlecase(TR) }
    else s

// ---------------------------------------------------------------- Yapı
/**
 * Ayar grubu: başlık kartın ÜSTÜNDE sakin bir etiket olarak durur, satırlar tek bir yüzey içinde toplanır.
 * İçerik açılıp kapanınca yüzey akıcı biçimde büyür / küçülür.
 */
@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(softCase(title), color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(tween(260, easing = FastOutSlowInEasing))
                .clip(shape).background(cs.surface).border(1.dp, cs.outlineVariant, shape)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/** Kartlar arasında bölüm başlığı (ör. "Yerleşim", "Görünüm", "Hareket"). */
@Composable
fun GroupLabel(text: String) =
    Text(
        softCase(text), color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
    )

@Composable
fun SubHeader(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

@Composable
fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

// ---------------------------------------------------------------- Satırlar
@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        if (checked) Color(0xFF4CD964) else Color(0xFFE4E1DE), tween(200), label = "switchTrack",
    )
    // Düğme: 64 dp çubuk - 2x4 dp kenar boşluğu - 26 dp düğme = 30 dp yol
    val knobX by animateDpAsState(
        if (checked) 30.dp else 0.dp,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium), label = "switchKnob",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Box(
            Modifier.width(64.dp).height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .padding(4.dp),
        ) {
            Box(
                Modifier.offset(x = knobX).size(26.dp)
                    .shadow(2.dp, CircleShape)          // gölge, arka plandan ÖNCE verilmeli
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val inactive = Color.White.copy(alpha = 0.22f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Text(valueText, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFD2BBA6),
                activeTrackColor = Color.White,
                inactiveTrackColor = inactive,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = Color(0xFFD2BBA6),
                disabledActiveTrackColor = Color.White,
                disabledInactiveTrackColor = inactive,
            ),
            thumb = {
                Box(
                    Modifier.size(20.dp)
                        .shadow(2.dp, CircleShape)
                        .background(Color(0xFFD2BBA6), CircleShape)
                        .border(3.dp, Color(0xFF5F4A3B), CircleShape),
                )
            },
        )
    }
}

@Composable
fun RadioRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(if (selected) cs.primaryContainer.copy(alpha = 0.45f) else Color.Transparent, tween(200), label = "radioBg")
    val edge by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant, tween(200), label = "radioEdge")
    val ring by animateColorAsState(if (selected) cs.primary else cs.outline, tween(200), label = "radioRing")
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "radioDot")
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot.coerceAtLeast(0.dp)).background(cs.primary, CircleShape))
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- Sekmeler (kayan seçici)
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outlineVariant, shape).padding(4.dp),
    ) {
        val segW = maxWidth / labels.size.coerceAtLeast(1)
        val x by animateDpAsState(
            segW * selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "tabX",
        )
        Box(Modifier.offset(x = x).width(segW).height(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val sel = i == selected
                val fg by animateColorAsState(if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant, tween(220), label = "tabFg$i")
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .selectable(selected = sel, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = fg, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Eylem düğmesi
@Composable
fun IslandActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(shape)
            .background(if (tonal) cs.surfaceVariant else cs.primary)
            .border(1.dp, if (tonal) cs.outline else Color.Transparent, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (tonal) cs.onSurface else cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
