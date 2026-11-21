package com.batterypulse.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.batterypulse.app.R
import com.batterypulse.app.data.BatteryEvent
import com.batterypulse.app.data.Capacity
import com.batterypulse.app.data.Fmt
import com.batterypulse.app.data.HOUR_MS
import com.batterypulse.app.data.Kind
import com.batterypulse.app.data.Report
import com.batterypulse.app.data.Session
import com.batterypulse.app.data.XY
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything an export needs, gathered once so all formats show the same numbers. */
data class ExportData(
    val report: Report,
    val events: List<BatteryEvent>,
    val sessions: List<Session>,
    val capacity: Capacity,
    val from: Long,
    val to: Long,
    val rangeLabel: String,
)

/** CSV, XLSX (hand-written OOXML, no dependencies) and PDF (android.graphics.pdf) exporters. */
object Exporter {

    enum class Format(val ext: String, val mime: String) {
        PDF("pdf", "application/pdf"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        CSV_EVENTS("csv", "text/csv"),
        CSV_SESSIONS("csv", "text/csv"),
    }

    fun export(ctx: Context, kind: Format, d: ExportData): File {
        val now = System.currentTimeMillis()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val name = when (kind) {
            Format.PDF -> "batterypulse_report"
            Format.XLSX -> "batterypulse_data"
            Format.CSV_EVENTS -> "batterypulse_events"
            Format.CSV_SESSIONS -> "batterypulse_sessions"
        }
        val file = File(dir, "${name}_$stamp.${kind.ext}")
        when (kind) {
            Format.PDF -> PdfReport(ctx, d).write(file)
            Format.XLSX -> writeXlsx(ctx, file, d)
            Format.CSV_EVENTS -> writeCsv(file, eventHeader(ctx), eventRows(ctx, d))
            Format.CSV_SESSIONS -> writeCsv(file, sessionHeader(ctx), sessionRows(ctx, d))
        }
        return file
    }

    // ------------------------------------------------------------------ shared tables

    private fun stateText(ctx: Context, e: BatteryEvent) =
        if (e.charging) ctx.getString(R.string.state_charging) else ctx.getString(R.string.state_discharging)

    private fun eventHeader(ctx: Context) = listOf(
        R.string.col_no, R.string.col_time, R.string.col_event, R.string.col_level, R.string.col_change,
        R.string.col_step_s, R.string.col_state, R.string.col_plug, R.string.col_temp_c, R.string.col_voltage_v,
        R.string.col_current_ma, R.string.col_screen, R.string.col_session
    ).map { ctx.getString(it) }

    private fun eventRows(ctx: Context, d: ExportData): List<List<Any?>> = d.events.mapIndexed { i, e ->
        listOf(
            i + 1, Fmt.dateTime(e.ts), Fmt.kindLabel(ctx, e.kind), e.level,
            if (e.kind == Kind.LEVEL) e.delta else null,
            if (e.kind == Kind.LEVEL && e.stepMs > 0) e.stepMs / 1000 else null,
            stateText(ctx, e), if (e.charging) Fmt.plug(ctx, e.plug) else "",
            if (e.tempDeci > 0) e.tempDeci / 10.0 else null,
            if (e.voltageMv > 0) e.voltageMv / 1000.0 else null,
            e.currentMa?.let { abs(it) },
            ctx.getString(if (e.screenOn) R.string.screen_on else R.string.screen_off),
            e.sessionId,
        )
    }

    private fun sessionHeader(ctx: Context) = listOf(
        R.string.col_session, R.string.col_type, R.string.col_plug, R.string.col_start, R.string.col_end,
        R.string.col_duration_s, R.string.col_level_start, R.string.col_level_end, R.string.col_speed_pct_h,
        R.string.col_avg_ma, R.string.col_mah, R.string.col_screen_s, R.string.col_max_temp, R.string.col_full_at
    ).map { ctx.getString(it) }

    private fun sessionRows(ctx: Context, d: ExportData): List<List<Any?>> = d.sessions.map { s ->
        val speed = s.speedPctPerHour(d.to)
        listOf(
            s.id,
            ctx.getString(if (s.charge) R.string.session_charge else R.string.session_discharge),
            if (s.charge) Fmt.plug(ctx, s.plug) else "",
            Fmt.dateTime(s.startMs), s.endMs?.let { Fmt.dateTime(it) } ?: "",
            s.durationMs(d.to) / 1000, s.startLevel, s.endLevel,
            speed?.let { Math.round(it * 10) / 10.0 },
            s.avgMa?.let { abs(it) }, s.mAh, s.screenOnMs / 1000,
            if (s.maxTempDeci > 0) s.maxTempDeci / 10.0 else null,
            s.fullMs?.let { Fmt.dateTime(it) } ?: "",
        )
    }

    private fun dailyHeader(ctx: Context) = listOf(
        R.string.col_date, R.string.col_screen_min, R.string.col_discharged_pct, R.string.col_charged_pct
    ).map { ctx.getString(it) }

    private fun dailyRows(d: ExportData): List<List<Any?>> = d.report.days.map {
        listOf(Fmt.date(it.dayStart), it.screenOnMs / 60_000, it.dischargedPct, it.chargedPct)
    }

    private fun summaryRows(ctx: Context, d: ExportData): List<List<Any?>> {
        val r = d.report
        val cap = d.capacity
        fun s(id: Int) = ctx.getString(id)
        return listOf(
            listOf(s(R.string.pdf_period), "${Fmt.dateTime(d.from)}  →  ${Fmt.dateTime(d.to)}"),
            listOf(s(R.string.stat_health), cap.healthPct?.let { "$it%" } ?: "—"),
            listOf(s(R.string.kv_design_capacity), Fmt.mah(cap.designMah)),
            listOf(s(R.string.kv_measured_capacity), Fmt.mah(cap.estimatedMah)),
            listOf(s(R.string.stat_cycles), cap.osCycles?.toString() ?: Fmt.num1(r.equivalentCycles)),
            listOf(s(R.string.kv_avg_speed) + " (" + s(R.string.sec_charging) + ")", Fmt.pctH(r.avgChargePctH)),
            listOf(s(R.string.kv_max_speed) + " (" + s(R.string.sec_charging) + ")", Fmt.pctH(r.maxChargePctH)),
            listOf(s(R.string.kv_avg_current) + " (" + s(R.string.sec_charging) + ")", Fmt.ma(r.avgChargeMa)),
            listOf(s(R.string.kv_avg_to_full), Fmt.durationOrDash(ctx, r.avgTimeTo100Ms)),
            listOf(s(R.string.kv_80_100), Fmt.durationOrDash(ctx, r.avg80to100Ms)),
            listOf(s(R.string.kv_step_avg) + " (" + s(R.string.sec_charging) + ")", Fmt.durationOrDash(ctx, r.chargeStep?.avgMs)),
            listOf(s(R.string.kv_step_avg) + " (" + s(R.string.sec_discharging) + ")", Fmt.durationOrDash(ctx, r.dischargeStep?.avgMs)),
            listOf(s(R.string.kv_idle), Fmt.pctH(r.idle.pctH)),
            listOf(s(R.string.kv_gap_total), Fmt.duration(ctx, r.gapMs)),
            listOf(s(R.string.kv_full_charges), r.fullCharges.toString()),
            listOf(s(R.string.kv_avg_speed) + " (" + s(R.string.sec_discharging) + ")", Fmt.pctH(r.dischargePctH)),
            listOf(s(R.string.kv_speed_screen_on), Fmt.pctH(r.screenOnPctH)),
            listOf(s(R.string.kv_speed_screen_off), Fmt.pctH(r.screenOffPctH)),
            listOf(s(R.string.kv_life_estimate), Fmt.hours(r.fullLifeHours)),
            listOf(s(R.string.kv_screen_total), Fmt.duration(ctx, r.screenOnMs)),
            listOf(s(R.string.kv_screen_daily), Fmt.duration(ctx, r.avgDailyScreenOnMs)),
            listOf(s(R.string.kv_avg_temp), Fmt.tempC(r.avgTempC)),
            listOf(s(R.string.kv_max_temp), Fmt.tempC(r.maxTempC)),
        )
    }

    // ------------------------------------------------------------------ CSV

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun writeCsv(file: File, header: List<String>, rows: List<List<Any?>>) {
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("\uFEFF") // BOM so Excel opens UTF-8 / Arabic correctly
            w.write(header.joinToString(",") { csvEscape(it) })
            w.write("\r\n")
            for (row in rows) {
                w.write(row.joinToString(",") { csvEscape(it?.toString() ?: "") })
                w.write("\r\n")
            }
        }
    }

    // ------------------------------------------------------------------ XLSX

    private class Sheet(val name: String, val header: List<String>, val widths: List<Int>, val rows: List<List<Any?>>)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 32 && ch != '\n' && ch != '\t' && ch != '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun sheetXml(s: Sheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("<cols>")
        s.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData><row r=\"1\">")
        s.header.forEachIndexed { i, h ->
            sb.append("""<c r="${colName(i)}1" t="inlineStr" s="1"><is><t>${esc(h)}</t></is></c>""")
        }
        sb.append("</row>")
        s.rows.forEachIndexed { r, row ->
            val rn = r + 2
            sb.append("""<row r="$rn">""")
            row.forEachIndexed { i, v ->
                val ref = "${colName(i)}$rn"
                when (v) {
                    null -> Unit
                    is Number -> sb.append("""<c r="$ref"><v>$v</v></c>""")
                    else -> sb.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2">
<font><sz val="11"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
</fonts>
<fills count="3">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF0F3D2E"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun writeXlsx(ctx: Context, file: File, d: ExportData) {
        val sheets = listOf(
            Sheet(
                ctx.getString(R.string.sheet_summary),
                listOf(ctx.getString(R.string.col_metric), ctx.getString(R.string.col_value)),
                listOf(44, 48), summaryRows(ctx, d)
            ),
            Sheet(
                ctx.getString(R.string.sheet_events), eventHeader(ctx),
                listOf(6, 21, 20, 8, 9, 12, 14, 14, 10, 10, 12, 12, 9), eventRows(ctx, d)
            ),
            Sheet(
                ctx.getString(R.string.sheet_sessions), sessionHeader(ctx),
                listOf(9, 14, 14, 21, 21, 13, 11, 11, 14, 11, 9, 13, 11, 21), sessionRows(ctx, d)
            ),
            Sheet(ctx.getString(R.string.sheet_daily), dailyHeader(ctx), listOf(14, 16, 18, 16), dailyRows(d)),
        )
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val overrides = sheets.indices.joinToString("") {
                """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>$overrides</Types>"""
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )
            val sheetTags = sheets.mapIndexed { i, s ->
                """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
            }.joinToString("")
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetTags</sheets></workbook>"""
            )
            val rels = sheets.indices.joinToString("") {
                """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>"""
            }
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
            )
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    // ------------------------------------------------------------------ PDF

    private class PdfReport(val ctx: Context, val d: ExportData) {
        val pageW = 595
        val pageH = 842
        val m = 36f
        val contentW = pageW - 2 * m

        val ink = Color.parseColor("#0A1311")
        val gray = Color.parseColor("#64748B")
        val dark = Color.parseColor("#0F3D2E")
        val green = Color.parseColor("#16A34A")
        val amber = Color.parseColor("#D97706")
        val red = Color.parseColor("#DC2626")
        val violet = Color.parseColor("#7C3AED")
        val teal = Color.parseColor("#0D9488")
        val blue = Color.parseColor("#2563EB")
        val grid = Color.parseColor("#E2E8F0")
        val panel = Color.parseColor("#F1F5F9")

        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = 0f
        val c: Canvas get() = page!!.canvas

        fun paint(size: Float, color: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                textAlign = align
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val small = paint(9f, gray)
        val text9 = paint(9f)
        val bold9 = paint(9f, ink, true)

        fun s(id: Int) = ctx.getString(id)

        fun finishPage() {
            val p = page ?: return
            val cv = p.canvas
            stroke.color = grid; stroke.strokeWidth = 0.7f
            cv.drawLine(m, pageH - 34f, pageW - m, pageH - 34f, stroke)
            cv.drawText("BatteryPulse", m, pageH - 20f, small)
            cv.drawText(ctx.getString(R.string.pdf_page, pageNo), pageW - m, pageH - 20f, paint(9f, gray, false, Paint.Align.RIGHT))
            doc.finishPage(p)
            page = null
        }

        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = m
        }

        fun ensure(h: Float) {
            if (y + h > pageH - 46f) newPage()
        }

        fun fit(t: String, p: Paint, w: Float): String {
            if (p.measureText(t) <= w) return t
            val n = p.breakText(t, true, w - p.measureText("…"), null)
            return t.take(n) + "…"
        }

        // ---- building blocks

        fun header() {
            fill.color = dark
            c.drawRect(0f, 0f, pageW.toFloat(), 96f, fill)
            // battery logo
            val lx = m
            val ly = 28f
            stroke.color = Color.WHITE; stroke.strokeWidth = 2.4f
            c.drawRoundRect(RectF(lx, ly, lx + 44f, ly + 24f), 5f, 5f, stroke)
            fill.color = Color.parseColor("#34D399")
            c.drawRoundRect(RectF(lx + 4f, ly + 4f, lx + 30f, ly + 20f), 2.5f, 2.5f, fill)
            fill.color = Color.WHITE
            c.drawRoundRect(RectF(lx + 46f, ly + 7f, lx + 50f, ly + 17f), 1.5f, 1.5f, fill)

            c.drawText(s(R.string.pdf_title), m + 66f, 42f, paint(22f, Color.WHITE, true))
            val dev = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
            c.drawText(dev, m + 66f, 60f, paint(10f, Color.parseColor("#C9F7E3")))
            c.drawText(
                s(R.string.pdf_period) + ": " + Fmt.dateTime(d.from) + "  →  " + Fmt.dateTime(d.to) + "  (" + d.rangeLabel + ")",
                m, 80f, paint(9f, Color.parseColor("#C9F7E3"))
            )
            c.drawText(
                ctx.getString(R.string.pdf_generated, Fmt.dateTime(System.currentTimeMillis())),
                pageW - m, 80f, paint(9f, Color.parseColor("#C9F7E3"), false, Paint.Align.RIGHT)
            )
            y = 96f + 18f
        }

        fun section(title: String) {
            ensure(34f)
            fill.color = green
            c.drawRoundRect(RectF(m, y, m + 4f, y + 14f), 2f, 2f, fill)
            c.drawText(title, m + 10f, y + 11f, paint(12f, ink, true))
            y += 24f
        }

        fun kpiRow(items: List<Triple<String, String, Int>>) {
            ensure(60f)
            val gap = 8f
            val w = (contentW - gap * (items.size - 1)) / items.size
            items.forEachIndexed { i, (label, value, col) ->
                val x = m + i * (w + gap)
                fill.color = panel
                c.drawRoundRect(RectF(x, y, x + w, y + 52f), 8f, 8f, fill)
                fill.color = col
                c.drawRoundRect(RectF(x, y + 8f, x + 3.5f, y + 44f), 2f, 2f, fill)
                c.drawText(fit(label, small, w - 18f), x + 12f, y + 18f, small)
                c.drawText(fit(value, paint(14f, col, true), w - 18f), x + 12f, y + 40f, paint(14f, col, true))
            }
            y += 52f + 10f
        }

        fun twoColumns(lt: String, left: List<Pair<String, String>>, rt: String, right: List<Pair<String, String>>) {
            val rows = maxOf(left.size, right.size)
            ensure(26f + rows * 16f)
            val colW = (contentW - 18f) / 2f
            val top = y
            listOf(Triple(m, lt, left), Triple(m + colW + 18f, rt, right)).forEach { (x, title, list) ->
                c.drawText(title, x, top + 10f, paint(10f, dark, true))
                stroke.color = grid; stroke.strokeWidth = 0.8f
                c.drawLine(x, top + 15f, x + colW, top + 15f, stroke)
                var yy = top + 29f
                list.forEach { (k, v) ->
                    c.drawText(fit(k, text9, colW * 0.62f), x, yy, paint(9f, gray))
                    c.drawText(fit(v, bold9, colW * 0.36f), x + colW, yy, paint(9f, ink, true, Paint.Align.RIGHT))
                    yy += 16f
                }
            }
            y = top + 29f + rows * 16f + 8f
        }

        fun lineChart(
            h: Float, pts: List<XY>, from: Long, to: Long, yMin: Float, yMax: Float, suffix: String,
            color: Int, bands: List<LongRange>, step: Boolean, gaps: List<LongRange> = emptyList()
        ) {
            ensure(h + 8f)
            val left = m + 30f
            val w = contentW - 30f
            val top = y + 4f
            val ph = h - 22f
            val span = (to - from).coerceAtLeast(1L).toFloat()
            fun px(x: Long) = left + ((x - from).coerceIn(0L, to - from)) / span * w
            fun py(v: Float) = top + ph - ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f) * ph
            fill.color = Color.parseColor("#E5E7EB")
            for (g in gaps) {
                val x0 = px(g.first)
                val x1 = px(g.last)
                if (x1 > x0) c.drawRect(x0, top, x1, top + ph, fill)
            }
            fill.color = Color.parseColor("#DCFCE7")
            for (b in bands) {
                val x0 = px(b.first)
                val x1 = px(b.last)
                if (x1 > x0) c.drawRect(x0, top, x1, top + ph, fill)
            }
            val lab = paint(8f, gray)
            stroke.color = grid; stroke.strokeWidth = 0.6f
            for (i in 0..4) {
                val v = yMin + (yMax - yMin) * i / 4f
                val yy = py(v)
                c.drawLine(left, yy, left + w, yy, stroke)
                c.drawText("${v.roundToInt()}$suffix", m, yy + 3f, lab)
            }
            val longSpan = to - from > 36 * HOUR_MS
            for (i in 0..3) {
                val t = from + (to - from) * i / 3
                val label = if (longSpan) Fmt.dayShort(t) else Fmt.hm(t)
                val al = when (i) { 0 -> Paint.Align.LEFT; 3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                c.drawText(label, left + w * i / 3f, top + ph + 13f, paint(8f, gray, false, al))
            }
            val p = pts.filter { it.y.isNaN() || it.x in from..to }.sortedBy { it.x }
            if (p.any { !it.y.isNaN() }) {
                val path = Path()
                var prevY: Float? = null
                for (pt in p) {
                    if (pt.y.isNaN()) {
                        prevY = null
                        continue
                    }
                    if (prevY == null) {
                        path.moveTo(px(pt.x), py(pt.y))
                    } else {
                        if (step) path.lineTo(px(pt.x), py(prevY))
                        path.lineTo(px(pt.x), py(pt.y))
                    }
                    prevY = pt.y
                }
                if (step && prevY != null) path.lineTo(px(to), py(prevY))
                stroke.color = color; stroke.strokeWidth = 1.6f
                stroke.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, stroke)
            } else {
                c.drawText(s(R.string.no_data_yet), left + 8f, top + ph / 2, lab)
            }
            y += h + 6f
        }

        fun barChart(h: Float, values: List<Float>, labels: List<String>, color: Int, fmt: (Float) -> String) {
            ensure(h + 8f)
            val top = y + 14f
            val ph = h - 32f
            val n = values.size
            if (n == 0) {
                c.drawText(s(R.string.no_data_yet), m + 8f, top + ph / 2, paint(9f, gray))
                y += h + 6f
                return
            }
            val max = values.max().coerceAtLeast(0.001f)
            val slot = contentW / n
            val bw = slot * 0.62f
            stroke.color = grid; stroke.strokeWidth = 0.8f
            c.drawLine(m, top + ph, m + contentW, top + ph, stroke)
            val every = ceil(n / 14f).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val bh = values[i] / max * ph
                val x = m + slot * i + (slot - bw) / 2f
                fill.color = color
                if (bh > 0f) c.drawRoundRect(RectF(x, top + ph - bh, x + bw, top + ph), 2.5f, 2.5f, fill)
                if (n <= 16 && values[i] > 0f) {
                    c.drawText(fmt(values[i]), x + bw / 2f, top + ph - bh - 3f, paint(7.5f, ink, false, Paint.Align.CENTER))
                }
                if (i % every == 0 && i < labels.size) {
                    c.drawText(labels[i], x + bw / 2f, top + ph + 12f, paint(7.5f, gray, false, Paint.Align.CENTER))
                }
            }
            y += h + 6f
        }

        fun table(headers: List<String>, widths: List<Float>, rows: List<List<String>>, colorCol: Int, colors: (Int) -> Int) {
            val rowH = 16f
            fun head() {
                ensure(rowH * 3)
                fill.color = dark
                c.drawRoundRect(RectF(m, y, m + contentW, y + rowH + 2f), 4f, 4f, fill)
                var x = m + 4f
                headers.forEachIndexed { i, h ->
                    c.drawText(fit(h, paint(8f, Color.WHITE, true), widths[i] - 4f), x, y + 12f, paint(8f, Color.WHITE, true))
                    x += widths[i]
                }
                y += rowH + 2f
            }
            head()
            rows.forEachIndexed { r, row ->
                if (y + rowH > pageH - 46f) {
                    newPage()
                    head()
                }
                if (r % 2 == 0) {
                    fill.color = Color.parseColor("#F8FAFC")
                    c.drawRect(m, y, m + contentW, y + rowH, fill)
                }
                var x = m + 4f
                row.forEachIndexed { i, v ->
                    val p = if (i == colorCol) paint(8f, colors(r), true) else paint(8f)
                    c.drawText(fit(v, p, widths[i] - 4f), x, y + 11.5f, p)
                    x += widths[i]
                }
                y += rowH
            }
            y += 8f
        }

        // ---- the report

        fun write(file: File) {
            val r = d.report
            val cap = d.capacity
            newPage()
            header()

            section(s(R.string.sec_overview))
            kpiRow(
                listOf(
                    Triple(s(R.string.stat_health), cap.healthPct?.let { "$it%" } ?: "—", teal),
                    Triple(s(R.string.kv_measured_capacity), Fmt.mah(cap.estimatedMah), teal),
                    Triple(s(R.string.stat_cycles), cap.osCycles?.toString() ?: Fmt.num1(r.equivalentCycles), blue),
                    Triple(s(R.string.kv_design_capacity), Fmt.mah(cap.designMah), gray),
                )
            )
            kpiRow(
                listOf(
                    Triple(s(R.string.kv_charge_speed), Fmt.pctH(r.avgChargePctH), green),
                    Triple(s(R.string.kv_discharge_speed), Fmt.pctH(r.dischargePctH), amber),
                    Triple(s(R.string.kv_screen_daily), Fmt.duration(ctx, r.avgDailyScreenOnMs), violet),
                    Triple(s(R.string.kv_max_temp), Fmt.tempC(r.maxTempC), red),
                )
            )

            section(s(R.string.chart_level))
            lineChart(
                150f, r.levelPoints, r.from, r.to, 0f, 100f, "%", dark, r.chargeBands, step = true, gaps = r.gaps
            )
            c.drawText(s(R.string.chart_charging_shaded), m, y + 2f, paint(8f, gray))
            y += 14f

            section(s(R.string.sec_charging) + " / " + s(R.string.sec_discharging))
            twoColumns(
                s(R.string.sec_charging),
                listOf(
                    s(R.string.kv_sessions) to r.chargeSessions.toString(),
                    s(R.string.kv_avg_speed) to Fmt.pctH(r.avgChargePctH),
                    s(R.string.kv_max_speed) to Fmt.pctH(r.maxChargePctH),
                    s(R.string.kv_avg_current) to Fmt.ma(r.avgChargeMa),
                    s(R.string.kv_max_current) to Fmt.ma(r.maxChargeMa),
                    s(R.string.kv_avg_duration) to Fmt.durationOrDash(ctx, r.avgChargeDurMs),
                    s(R.string.kv_avg_to_full) to Fmt.durationOrDash(ctx, r.avgTimeTo100Ms),
                    s(R.string.kv_full_charges) to r.fullCharges.toString(),
                    s(R.string.kv_charged) to "${r.chargedPct}%",
                    s(R.string.kv_80_100) to Fmt.durationOrDash(ctx, r.avg80to100Ms),
                ),
                s(R.string.sec_discharging),
                listOf(
                    s(R.string.kv_sessions) to r.dischargeSessions.toString(),
                    s(R.string.kv_avg_speed) to Fmt.pctH(r.dischargePctH),
                    s(R.string.kv_speed_screen_on) to Fmt.pctH(r.screenOnPctH),
                    s(R.string.kv_speed_screen_off) to Fmt.pctH(r.screenOffPctH),
                    s(R.string.kv_avg_current) to Fmt.ma(r.avgDischargeMa),
                    s(R.string.kv_life_estimate) to Fmt.hours(r.fullLifeHours),
                    s(R.string.kv_life_screen_on) to Fmt.hours(r.screenOnLifeHours),
                    s(R.string.kv_life_screen_off) to Fmt.hours(r.screenOffLifeHours),
                    s(R.string.kv_discharged) to "${r.dischargedPct}%",
                    s(R.string.kv_idle) to Fmt.pctH(r.idle.pctH),
                    s(R.string.kv_idle_hours) to if (r.idle.hours > 0) Fmt.hours(r.idle.hours) else "—",
                )
            )

            // ---- time per 1 %, charging curve, chargers
            newPage()
            section(s(R.string.sec_steps))
            fun stepRows(st: com.batterypulse.app.data.StepStats?): List<Pair<String, String>> =
                if (st == null) listOf(s(R.string.kv_steps_count) to "—") else listOf(
                    s(R.string.kv_steps_count) to st.count.toString(),
                    s(R.string.kv_step_avg) to Fmt.duration(ctx, st.avgMs),
                    s(R.string.kv_step_median) to Fmt.duration(ctx, st.medianMs),
                    s(R.string.kv_step_fastest) to Fmt.durationAt(ctx, st.minMs, st.minTs),
                    s(R.string.kv_step_slowest) to Fmt.durationAt(ctx, st.maxMs, st.maxTs),
                )
            fun band(l: List<Long?>, i: Int) = Fmt.durationOrDash(ctx, l.getOrNull(i))
            twoColumns(
                s(R.string.sec_charging),
                stepRows(r.chargeStep) + listOf(
                    s(R.string.kv_band_low) to band(r.chargeBandMs, 0),
                    s(R.string.kv_band_mid) to band(r.chargeBandMs, 1),
                    s(R.string.kv_band_high) to band(r.chargeBandMs, 2),
                ),
                s(R.string.sec_discharging),
                stepRows(r.dischargeStep) + listOf(
                    s(R.string.kv_band_low) to band(r.dischargeBandMs, 0),
                    s(R.string.kv_band_mid) to band(r.dischargeBandMs, 1),
                    s(R.string.kv_band_high) to band(r.dischargeBandMs, 2),
                    s(R.string.kv_step_screen_on) to Fmt.durationOrDash(ctx, r.screenOnStepMs),
                    s(R.string.kv_step_screen_off) to Fmt.durationOrDash(ctx, r.screenOffStepMs),
                )
            )
            if (r.chargeCurve.any { it > 0f }) {
                section(s(R.string.chart_charge_curve))
                barChart(120f, r.chargeCurve, List(10) { ((it + 1) * 10).toString() }, green) {
                    String.format(Locale.US, "%.1f", it)
                }
            }
            if (r.chargers.isNotEmpty()) {
                section(s(R.string.sec_chargers))
                table(
                    listOf(s(R.string.col_plug), s(R.string.kv_sessions), "%/h", "mA", "W"),
                    listOf(150f, 70f, 100f, 100f, 103f),
                    r.chargers.map {
                        listOf(
                            Fmt.plug(ctx, it.plug), it.sessions.toString(),
                            it.pctH?.let { v -> String.format(Locale.US, "%.1f", v) } ?: "—",
                            it.avgMa?.toString() ?: "—",
                            it.avgW?.let { v -> String.format(Locale.US, "%.1f", v) } ?: "—",
                        )
                    },
                    colorCol = -1, colors = { green }
                )
            }
            section(s(R.string.chart_daily_screen))
            val days = r.days.takeLast(30)
            barChart(120f, days.map { it.screenOnMs / HOUR_MS.toFloat() }, days.map { Fmt.dayShort(it.dayStart) }, violet) {
                String.format(Locale.US, "%.1f", it)
            }
            section(s(R.string.chart_daily_discharge))
            barChart(120f, days.map { it.dischargedPct.toFloat() }, days.map { Fmt.dayShort(it.dayStart) }, amber) {
                String.format(Locale.US, "%.0f", it)
            }
            if (r.chargeSpeeds.isNotEmpty()) {
                section(s(R.string.chart_charge_speed))
                val cs = r.chargeSpeeds.takeLast(30)
                barChart(120f, cs.map { it.y }, cs.map { Fmt.dayShort(it.x) }, green) { String.format(Locale.US, "%.0f", it) }
            }
            section(s(R.string.sec_screen) + " / " + s(R.string.sec_temperature))
            twoColumns(
                s(R.string.sec_screen),
                listOf(
                    s(R.string.kv_screen_total) to Fmt.duration(ctx, r.screenOnMs),
                    s(R.string.kv_screen_daily) to Fmt.duration(ctx, r.avgDailyScreenOnMs),
                    s(R.string.kv_screen_count) to r.screenOnCount.toString(),
                    s(R.string.kv_screen_longest) to Fmt.duration(ctx, r.longestScreenOnMs),
                ),
                s(R.string.sec_temperature),
                listOf(
                    s(R.string.kv_avg_temp) to Fmt.tempC(r.avgTempC),
                    s(R.string.kv_max_temp) to Fmt.tempC(r.maxTempC),
                )
            )
            if (r.tempPoints.size >= 2) {
                val lo = (r.tempPoints.minOf { it.y } - 2f).coerceAtLeast(0f)
                val hi = (r.tempPoints.maxOf { it.y } + 2f).coerceAtLeast(lo + 8f)
                lineChart(110f, r.tempPoints, r.from, r.to, lo, hi, "°", red, emptyList(), step = false)
            }

            // ---- sessions table
            if (d.sessions.isNotEmpty()) {
                newPage()
                section(s(R.string.hist_sessions))
                val widths = listOf(20f, 52f, 88f, 88f, 50f, 56f, 46f, 40f, 40f, 43f)
                val sess = d.sessions.sortedByDescending { it.startMs }.take(300)
                fun sd(ms: Long) = Fmt.dayShort(ms) + " " + Fmt.hm(ms)
                table(
                    listOf("#", s(R.string.col_type), s(R.string.col_start), s(R.string.col_end), s(R.string.kv_duration),
                        s(R.string.col_level), "%/h", "mA", "mAh", s(R.string.col_screen_short)),
                    widths,
                    sess.mapIndexed { i, x ->
                        listOf(
                            (i + 1).toString(),
                            s(if (x.charge) R.string.session_charge else R.string.session_discharge),
                            sd(x.startMs), x.endMs?.let { sd(it) } ?: s(R.string.now_label),
                            Fmt.duration(ctx, x.durationMs(d.to)),
                            "${x.startLevel}→${x.endLevel}%",
                            x.speedPctPerHour(d.to)?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                            x.avgMa?.let { abs(it).toString() } ?: "—",
                            x.mAh?.toString() ?: "—",
                            Fmt.duration(ctx, x.screenOnMs),
                        )
                    },
                    colorCol = 1, colors = { i -> if (sess[i].charge) green else amber }
                )
            }

            // ---- event log (newest part)
            val ev = d.events.filter { it.kind != Kind.START && it.kind != Kind.STOP }.takeLast(400)
            if (ev.isNotEmpty()) {
                newPage()
                section(s(R.string.hist_events))
                c.drawText(ctx.getString(R.string.pdf_events_note, ev.size), m, y - 4f, paint(8f, gray))
                y += 8f
                val widths = listOf(100f, 80f, 36f, 30f, 46f, 60f, 40f, 40f, 91f)
                table(
                    listOf(s(R.string.col_time), s(R.string.col_event), s(R.string.col_level), "Δ", s(R.string.col_step_short),
                        s(R.string.col_state), "°C", "mA", s(R.string.col_screen)),
                    widths,
                    ev.map { e ->
                        listOf(
                            Fmt.dateTime(e.ts), Fmt.kindLabel(ctx, e.kind), "${e.level}%",
                            if (e.kind == Kind.LEVEL) (if (e.delta > 0) "+${e.delta}" else e.delta.toString()) else "",
                            if (e.kind == Kind.LEVEL && e.stepMs > 0) Fmt.duration(ctx, e.stepMs) else "",
                            stateText(ctx, e),
                            if (e.tempDeci > 0) String.format(Locale.US, "%.1f", e.tempDeci / 10.0) else "—",
                            e.currentMa?.let { abs(it).toString() } ?: "—",
                            s(if (e.screenOn) R.string.screen_on else R.string.screen_off),
                        )
                    },
                    colorCol = 5, colors = { i -> if (ev[i].charging) green else amber }
                )
            }

            finishPage()
            file.outputStream().use { doc.writeTo(it) }
            doc.close()
        }
    }
}
