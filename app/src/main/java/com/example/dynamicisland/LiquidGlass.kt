package com.example.dynamicisland

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import android.graphics.RenderEffect as AndroidRenderEffect

private val LiquidMaxRadius = 36.dp

/**
 * Gelişmiş AGSL Shader:
 * Bevel ve bend değerleri adanın anlık boyutuna (compact veya expanded) göre 
 * dinamik ölçeklenir, böylece her iki durumda da cam kırılması mükemmel görünür.
 */
private val LIQUID_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float bend;
uniform float disp;

half4 main(float2 fragCoord) {
    float2 hs = size * 0.5;
    float2 p = fragCoord - hs;
    float2 q = abs(p) - (hs - float2(radius));
    float dist = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float depth = -dist; // Kenara olan mesafe
    
    // Kenara yakın bantta pürüzsüz mercek bükülmesi
    float t = clamp(depth / bevel, 0.0, 1.0);
    t = 1.0 - (t * t * (3.0 - 2.0 * t));

    float2 dir = length(p) > 0.001 ? p / length(p) : float2(0.0);

    float2 off = dir * bend * t;
    half4 cr = content.eval(fragCoord - off * (1.0 + disp));
    half4 cg = content.eval(fragCoord - off);
    half4 cb = content.eval(fragCoord - off * (1.0 - disp));
    half a = max(max(cr.a, cg.a), cb.a);
    return half4(min(cr.r, a), min(cg.g, a), min(cb.b, a), a);
}
"""

fun liquidRefractionSupported(): Boolean = Build.VERSION.SDK_INT >= 33

@SuppressLint("NewApi")
@Composable
fun Modifier.liquidRefraction(refraction: Float, dispersion: Float): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val shader = remember { RuntimeShader(LIQUID_AGSL) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    return this
        .onSizeChanged { sz = it }
        .graphicsLayer {
            val w = sz.width.toFloat()
            val h = sz.height.toFloat()
            if (w < 2f || h < 2f) return@graphicsLayer
            val r = min(LiquidMaxRadius.toPx(), h / 2f)
            shader.setFloatUniform("size", w, h)
            shader.setFloatUniform("radius", r)
            
            // DİNAMİK ÖLÇEKLEME: Bevel ve bend, adanın o anki boyutuna (küçük veya büyük) oranlıdır.
            val minDim = min(w, h)
            shader.setFloatUniform("bevel", minDim * 0.22f)
            shader.setFloatUniform("bend", refraction.coerceIn(0f, 1.5f) * (minDim * 0.035f))
            shader.setFloatUniform("disp", dispersion.coerceIn(0f, 1f) * 0.4f)
            
            renderEffect = AndroidRenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
}

fun DrawScope.drawLiquidBase(specular: Float) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val k = specular.coerceIn(0f, 1.5f)
    val r = min(LiquidMaxRadius.toPx(), h / 2f)

    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(0.20f * k),
            0.38f to Color.White.copy(0.05f * k),
            0.70f to Color.Transparent,
            1f to Color.Black.copy(0.22f),
        )
    )

    val shadowRings = 7
    val sStep = 1.3.dp.toPx()
    for (i in 0 until shadowRings) {
        val inset = i * sStep + sStep / 2f
        val a = 0.26f * (1f - i / shadowRings.toFloat())
        drawRoundRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(a)),
            topLeft = Offset(inset, inset),
            size = Size((w - 2 * inset).coerceAtLeast(0f), (h - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
            style = Stroke(width = sStep),
        )
    }

    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.26f * k),
            0.55f to Color.White.copy(0.06f * k),
            1f to Color.Transparent,
            center = Offset(w * 0.18f, h * 0.02f),
            radius = max(w * 0.55f, h),
        )
    )

    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.14f * k),
            1f to Color.Transparent,
            center = Offset(w * 0.86f, h * 1.02f),
            radius = max(w * 0.32f, h * 0.9f),
        )
    )

    val rings = 6
    val step = 1.4.dp.toPx()
    for (i in 0 until rings) {
        val inset = i * step + step / 2f
        val a = 0.13f * k * (1f - i / rings.toFloat())
        drawRoundRect(
            color = Color.White.copy(alpha = a),
            topLeft = Offset(inset, inset),
            size = Size((w - 2 * inset).coerceAtLeast(0f), (h - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
            style = Stroke(width = step),
        )
    }
}

fun DrawScope.drawLiquidRim(specular: Float) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val k = specular.coerceIn(0f, 1.5f)
    val r = min(LiquidMaxRadius.toPx(), h / 2f)

    val sw = 1.4.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy((0.95f * k).coerceAtMost(1f)),
            0.28f to Color.White.copy(0.22f * k),
            0.62f to Color.White.copy(0.07f * k),
            1f to Color.White.copy((0.65f * k).coerceAtMost(1f)),
            start = Offset.Zero, end = Offset(w, h),
        ),
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
        style = Stroke(width = sw),
    )

    val sw2 = 0.8.dp.toPx()
    val off = sw + sw2 / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f * k),
        topLeft = Offset(off, off),
        size = Size((w - 2 * off).coerceAtLeast(0f), (h - 2 * off).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - off).coerceAtLeast(0f)),
        style = Stroke(width = sw2),
    )

    val arcW = 2.2.dp.toPx()
    val ar = (r - 1.5.dp.toPx()).coerceAtLeast(2f)
    drawArc(
        brush = Brush.linearGradient(
            0f to Color.White.copy((0.95f * k).coerceAtMost(1f)), 1f to Color.Transparent,
            start = Offset(0f, 0f), end = Offset(ar * 1.6f, ar * 1.6f),
        ),
        startAngle = 180f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()), size = Size(2 * ar, 2 * ar),
        style = Stroke(width = arcW, cap = StrokeCap.Round),
    )
    drawArc(
        brush = Brush.linearGradient(
            0f to Color.Transparent, 1f to Color.White.copy(0.55f * k),
            start = Offset(w - ar * 1.6f, h - ar * 1.6f), end = Offset(w, h),
        ),
        startAngle = 0f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(w - 2 * ar - 1.5.dp.toPx(), h - 2 * ar - 1.5.dp.toPx()), size = Size(2 * ar, 2 * ar),
        style = Stroke(width = arcW, cap = StrokeCap.Round),
    )

    if (w > 2.2f * r) {
        val x0 = r * 0.9f; val x1 = w - r * 0.9f
        val yTop = 1.9.dp.toPx()
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.30f to Color.White.copy((0.60f * k).coerceAtMost(1f)),
                0.65f to Color.White.copy(0.28f * k),
                1f to Color.Transparent,
                startX = x0, endX = x1,
            ),
            start = Offset(x0, yTop), end = Offset(x1, yTop),
            strokeWidth = 1.1.dp.toPx(), cap = StrokeCap.Round,
        )
        val yBot = h - 1.9.dp.toPx()
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.55f to Color.White.copy(0.10f * k),
                0.85f to Color.White.copy(0.32f * k),
                1f to Color.Transparent,
                startX = x0, endX = x1,
            ),
            start = Offset(x0, yBot), end = Offset(x1, yBot),
            strokeWidth = 0.9.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}
