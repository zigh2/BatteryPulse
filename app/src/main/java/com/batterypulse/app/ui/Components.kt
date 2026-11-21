package com.batterypulse.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.XY
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Wall-clock "now" that ticks every second. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun StatCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).background(accent, RoundedCornerShape(2.dp)))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth()
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
        )
        Text(
            value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}

/** Big circular gauge with the battery level in the middle. */
@Composable
fun BatteryGauge(level: Int, charging: Boolean, subtitle: String, modifier: Modifier = Modifier, dimension: Dp = 210.dp) {
    val target = if (level < 0) 0f else level / 100f
    val sweep by animateFloatAsState(target, tween(700), label = "gauge")
    val color = levelColor(level, charging)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(dimension), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (sweep > 0.001f) {
                drawArc(
                    color = color, startAngle = 135f, sweepAngle = 270f * sweep, useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (level < 0) "—" else "$level%",
                fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
            )
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/**
 * Line chart over time. [bands] are time ranges shaded behind the line (used for charging periods).
 * With [step] the line holds its value until the next point, which is the right look for battery level.
 */
@Composable
fun LineChart(
    points: List<XY>,
    fromX: Long,
    toX: Long,
    yMin: Float,
    yMax: Float,
    ySuffix: String,
    color: Color,
    modifier: Modifier = Modifier,
    bands: List<LongRange> = emptyList(),
    gaps: List<LongRange> = emptyList(),
    step: Boolean = true,
    xLabel: (Long) -> String = { Fmt.hm(it) },
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val left = 38.dp.toPx()
        val bottom = 18.dp.toPx()
        val top = 6.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom - top
        val span = (toX - fromX).coerceAtLeast(1L).toFloat()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = label.toArgb()
            textSize = 10.sp.toPx()
        }
        fun px(x: Long) = left + ((x - fromX).coerceIn(0L, toX - fromX).toFloat() / span) * w
        fun py(y: Float) = top + h - ((y - yMin) / (yMax - yMin)).coerceIn(0f, 1f) * h

        for (b in bands) {
            val x0 = px(b.first)
            val x1 = px(b.last)
            if (x1 > x0) drawRect(Brand.Charge.copy(alpha = 0.13f), Offset(x0, top), Size(x1 - x0, h))
        }
        for (g in gaps) {
            val x0 = px(g.first)
            val x1 = px(g.last)
            if (x1 > x0) drawRect(label.copy(alpha = 0.16f), Offset(x0, top), Size(x1 - x0, h))
        }
        for (i in 0..4) {
            val v = yMin + (yMax - yMin) * i / 4f
            val y = py(v)
            drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
            paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText("${v.roundToInt()}$ySuffix", 0f, y + 4.dp.toPx(), paint)
        }
        for (i in 0..3) {
            val x = left + w * i / 3f
            paint.textAlign = when (i) {
                0 -> android.graphics.Paint.Align.LEFT
                3 -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(xLabel(fromX + ((toX - fromX) * i / 3)), x, size.height - 3.dp.toPx(), paint)
        }

        // A NaN point splits the line into segments (tracking was off in between).
        val pts = points.filter { it.y.isNaN() || it.x in fromX..toX }.sortedBy { it.x }
        val segments = ArrayList<List<XY>>()
        var seg = ArrayList<XY>()
        for (p in pts) {
            if (p.y.isNaN()) {
                if (seg.isNotEmpty()) segments.add(seg)
                seg = ArrayList()
            } else {
                seg.add(p)
            }
        }
        if (seg.isNotEmpty()) segments.add(seg)
        val endsBroken = pts.lastOrNull()?.y?.isNaN() == true
        segments.forEachIndexed { si, sp ->
            val extend = step && si == segments.lastIndex && !endsBroken
            val line = Path()
            val fill = Path()
            line.moveTo(px(sp[0].x), py(sp[0].y))
            fill.moveTo(px(sp[0].x), top + h)
            fill.lineTo(px(sp[0].x), py(sp[0].y))
            for (i in 1 until sp.size) {
                if (step) {
                    line.lineTo(px(sp[i].x), py(sp[i - 1].y)); fill.lineTo(px(sp[i].x), py(sp[i - 1].y))
                }
                line.lineTo(px(sp[i].x), py(sp[i].y)); fill.lineTo(px(sp[i].x), py(sp[i].y))
            }
            val endX = if (extend) px(toX) else px(sp.last().x)
            if (extend) {
                line.lineTo(endX, py(sp.last().y)); fill.lineTo(endX, py(sp.last().y))
            }
            fill.lineTo(endX, top + h)
            fill.close()
            drawPath(fill, color.copy(alpha = 0.10f))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { it.roundToInt().toString() },
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val bottom = 18.dp.toPx()
        val top = 16.dp.toPx()
        val h = size.height - bottom - top
        val n = values.size
        if (n == 0) return@Canvas
        val max = values.max().coerceAtLeast(0.001f)
        val slot = size.width / n
        val barW = slot * 0.62f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = label.toArgb()
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawLine(grid, Offset(0f, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        val every = ceil(n / 7f).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val bh = values[i] / max * h
            val x = slot * i + (slot - barW) / 2f
            if (bh > 0f) {
                drawRoundRect(color, Offset(x, top + h - bh), Size(barW, bh), CornerRadius(4.dp.toPx()))
            }
            if (n <= 10 && values[i] > 0f) {
                drawContext.canvas.nativeCanvas.drawText(valueLabel(values[i]), x + barW / 2f, top + h - bh - 4.dp.toPx(), paint)
            }
            if (i % every == 0 && i < labels.size) {
                drawContext.canvas.nativeCanvas.drawText(labels[i], x + barW / 2f, size.height - 3.dp.toPx(), paint)
            }
        }
    }
}
