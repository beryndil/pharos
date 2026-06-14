package com.beryndil.pharos.medication.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.settings.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Renders the current medication list as a structured A4 PDF.
 *
 * Layout per medication entry:
 *  - Full-width hairline separator
 *  - Medication name (bold, 14 sp) with status badge right-aligned
 *  - Strength · Form · Dose amount (11 sp, muted)
 *  - Two-column label / value rows for selected optional fields:
 *      SCHEDULE  |  value
 *      PRESCRIBER|  name  ·  phone
 *      PHARMACY  |  name  ·  phone
 *      PURPOSE   |  value
 *      SUPPLY    |  qty unit, refill by date
 *
 * Laws:
 *  - Law 4: only writes bytes; callers decide the destination.
 *  - Law 3: PDF header contains the standard disclaimer; no advice language anywhere.
 */
class MedListPdfExporter(private val db: RegimenDatabase) {

    /**
     * Renders the medication list to [outputStream] as an A4 PDF.
     *
     * Runs on [Dispatchers.IO]. Caller is responsible for closing [outputStream].
     *
     * @param exportedAtEpochMs Epoch-ms stamped in the PDF header as the export date.
     * @param options           Controls which fields and medications appear in the output.
     *   Defaults to "include everything, active + paused medications."
     */
    suspend fun writeTo(
        outputStream: OutputStream,
        exportedAtEpochMs: Long = Instant.now().toEpochMilli(),
        options: PdfExportOptions = PdfExportOptions(),
        userProfile: UserProfile? = null,
    ) = withContext(Dispatchers.IO) {

        // ── Data load ─────────────────────────────────────────────────────
        val allMedications = db.medicationDao().getAll()
        val medications = allMedications.filter { med ->
            when (options.statusFilter) {
                PdfStatusFilter.ACTIVE_ONLY       -> med.status == MedicationStatus.ACTIVE.name
                PdfStatusFilter.ACTIVE_AND_PAUSED -> med.status == MedicationStatus.ACTIVE.name ||
                                                     med.status == MedicationStatus.PAUSED.name
                PdfStatusFilter.ALL               -> true
            }
        }

        val scheduleMap  = db.scheduleDao().getAll().groupBy { it.medicationId }
        val latestRefill = db.refillRecordDao().getAll().groupBy { it.medicationId }
            .mapValues { (_, records) -> records.maxByOrNull { it.createdAtEpochMs } }

        // ── Page geometry ─────────────────────────────────────────────────
        val pageWidth  = 595   // A4 pt
        val pageHeight = 842
        val margin     = 40f
        val contentW   = (pageWidth - margin * 2).toInt()
        val labelColW  = 84f   // width of the left "SCHEDULE" / "PRESCRIBER" etc column
        val valueX     = margin + labelColW + 8f

        // ── Paints ────────────────────────────────────────────────────────
        val titlePaint    = makePaint(18f, Color.BLACK, bold = true)
        val headerSubPaint= makePaint(10f, Color.DKGRAY)
        val disclaimerP   = makePaint(8f,  Color.GRAY)
        val separatorPaint= makePaint(0f,  Color.LTGRAY).apply { strokeWidth = 0.75f; style = Paint.Style.STROKE }
        val medNamePaint  = makePaint(14f, Color.BLACK, bold = true)
        val strengthPaint = makePaint(10f, Color.DKGRAY)
        val labelPaint    = makePaint(8f,  Color.GRAY,  bold = true)
        val valuePaint    = makePaint(10f, Color.BLACK)
        val badgePaint    = makePaint(8f,  Color.DKGRAY, bold = true)

        // ── State ─────────────────────────────────────────────────────────
        val pdfDoc    = PdfDocument()
        var pageNum   = 1
        var page      = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas: Canvas = page.canvas
        var y         = margin

        // ── Helpers ───────────────────────────────────────────────────────

        fun newPageIfNeeded(neededHeight: Float): Boolean {
            if (y + neededHeight > pageHeight - margin) {
                pdfDoc.finishPage(page)
                pageNum++
                page   = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y      = margin
                return true
            }
            return false
        }

        /** Draws text in the label column (CAPS label). Returns the text's measured height. */
        fun drawLabel(text: String, atY: Float): Float {
            canvas.drawText(text, margin, atY + labelPaint.textSize, labelPaint)
            return labelPaint.textSize
        }

        /** Draws wrapped text in the value column. Returns height consumed. */
        fun drawValue(text: String, atY: Float, paint: Paint = valuePaint): Float {
            val avail = (pageWidth - margin - valueX).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), avail).build()
            canvas.save()
            canvas.translate(valueX, atY)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }

        /** Draws a label + value pair. Advances [y] by the row height + small gap. */
        fun drawRow(label: String, value: String) {
            val rowH = maxOf(labelPaint.textSize, drawValue(value, y).also { canvas.save() })
            // Redraw label at correct baseline
            canvas.drawText(label, margin, y + labelPaint.textSize, labelPaint)
            y += rowH + 5f
        }

        /** Draws wrapped text spanning the full content width. Returns height used. */
        fun drawFull(text: String, atY: Float, paint: Paint): Float {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), contentW).build()
            canvas.save()
            canvas.translate(margin, atY)
            layout.draw(canvas)
            canvas.restore()
            return layout.height.toFloat()
        }

        // ── Page header ───────────────────────────────────────────────────
        // User profile block (if any profile data was provided)
        if (userProfile != null && !userProfile.isEmpty()) {
            if (!userProfile.name.isNullOrBlank()) {
                canvas.drawText(userProfile.name, margin, y + titlePaint.textSize, titlePaint)
                y += titlePaint.textSize + 4f
            }
            listOfNotNull(
                userProfile.dateOfBirth?.let { "DOB: $it" },
                userProfile.phone,
                userProfile.address,
                userProfile.allergies?.let { "Allergies: $it" },
            ).forEach { line ->
                y += drawFull(line, y, headerSubPaint) + 3f
            }
            y += 8f
            canvas.drawLine(margin, y, pageWidth - margin, y, separatorPaint)
            y += 12f
        }

        val listTitle = if (userProfile != null && !userProfile.name.isNullOrBlank())
            "Medication List" else "Pharos — Medication List"
        canvas.drawText(listTitle, margin, y + titlePaint.textSize, titlePaint)
        y += titlePaint.textSize + 4f
        val dateStr = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(exportedAtEpochMs))
        canvas.drawText("Exported $dateStr", margin, y + headerSubPaint.textSize, headerSubPaint)
        y += headerSubPaint.textSize + 6f
        y += drawFull(
            "This list is for reference only. Check with your doctor or pharmacist before making any changes.",
            y, disclaimerP,
        )
        y += 10f
        // Full-width separator under header
        canvas.drawLine(margin, y, pageWidth - margin, y, separatorPaint)
        y += 12f

        // ── Medication entries ────────────────────────────────────────────
        for (med in medications) {

            // Estimate minimum block height to decide if we need a page break before starting.
            // Minimum: separator (1) + med name line + strength line + at least one field row.
            newPageIfNeeded(60f)

            // ── Separator line ────────────────────────────────────────────
            canvas.drawLine(margin, y, pageWidth - margin, y, separatorPaint)
            y += 10f

            // ── Medication name + status badge ────────────────────────────
            val statusLabel = when (med.status) {
                MedicationStatus.ACTIVE.name -> ""
                MedicationStatus.PAUSED.name -> "PAUSED"
                MedicationStatus.ENDED.name  -> "ENDED"
                else                         -> ""
            }
            // Draw name; right-align status badge if present.
            canvas.drawText(med.name, margin, y + medNamePaint.textSize, medNamePaint)
            if (statusLabel.isNotEmpty()) {
                val badgeW = badgePaint.measureText(statusLabel)
                canvas.drawText(statusLabel, pageWidth - margin - badgeW, y + badgePaint.textSize + 3f, badgePaint)
            }
            y += medNamePaint.textSize + 5f

            // ── Strength · Form · Dose amount ─────────────────────────────
            val strengthLine = buildList {
                add("${med.strength} · ${med.form.lowercase().replaceFirstChar { it.uppercase() }}")
                if (options.includeDoseAmount && med.doseAmount.isNotBlank()) {
                    add(med.doseAmount)
                }
            }.joinToString("  ·  ")
            y += drawFull(strengthLine, y, strengthPaint) + 8f

            // ── Optional field rows ───────────────────────────────────────
            val activeSchedules = scheduleMap[med.id]?.filter { it.isActive } ?: emptyList()
            if (options.includeSchedule && activeSchedules.isNotEmpty()) {
                val sched = activeSchedules.joinToString("  /  ") { it.toDisplayString() }
                newPageIfNeeded(24f)
                drawRow("SCHEDULE", sched)
            }

            if (options.includePrescriber && !med.prescriber.isNullOrBlank()) {
                val value = buildString {
                    append(med.prescriber)
                    if (!med.prescriberPhone.isNullOrBlank()) append("  ·  ${med.prescriberPhone}")
                }
                newPageIfNeeded(24f)
                drawRow("PRESCRIBER", value)
            }

            if (options.includePharmacy && !med.pharmacy.isNullOrBlank()) {
                val value = buildString {
                    append(med.pharmacy)
                    if (!med.pharmacyPhone.isNullOrBlank()) append("  ·  ${med.pharmacyPhone}")
                }
                newPageIfNeeded(24f)
                drawRow("PHARMACY", value)
            }

            if (options.includePurpose && !med.purpose.isNullOrBlank()) {
                newPageIfNeeded(24f)
                drawRow("PURPOSE", med.purpose)
            }

            if (options.includeSupply) {
                latestRefill[med.id]?.let { refill ->
                    val supplyStr = buildString {
                        append("${refill.quantityOnHand} ${refill.quantityUnit} on hand")
                        refill.refillByEpochMs?.let { epochMs ->
                            val d = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(epochMs))
                            append("  ·  refill by $d")
                        }
                    }
                    newPageIfNeeded(24f)
                    drawRow("SUPPLY", supplyStr)
                }
            }

            y += 8f // gap after each medication block
        }

        // Trailing separator and footer
        canvas.drawLine(margin, y, pageWidth - margin, y, separatorPaint)
        y += 8f
        val countStr = "${medications.size} medication${if (medications.size == 1) "" else "s"}"
        canvas.drawText(countStr, margin, y + disclaimerP.textSize, disclaimerP)
        val footerStr = "Printed with Pharos · github.com/beryndil/pharos"
        val footerW = disclaimerP.measureText(footerStr)
        canvas.drawText(footerStr, pageWidth - margin - footerW, y + disclaimerP.textSize, disclaimerP)

        pdfDoc.finishPage(page)
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun makePaint(textSize: Float, color: Int, bold: Boolean = false): Paint =
        Paint().apply {
            this.textSize = textSize
            this.color    = color
            this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias   = true
        }

    private fun ScheduleEntity.toDisplayString(): String = when (
        runCatching { ScheduleType.valueOf(type) }.getOrElse { ScheduleType.FIXED_DAILY }
    ) {
        ScheduleType.FIXED_DAILY  -> scheduledTimesDisplay()
        ScheduleType.DAYS_OF_WEEK -> scheduledTimesDisplay()
        ScheduleType.INTERVAL     -> intervalHours?.let { "Every ${it}h" } ?: "Interval"
        ScheduleType.DOSE_WINDOW  -> windowRange()
        ScheduleType.PRN          -> "As needed"
        ScheduleType.TEMPORARY    -> scheduledTimesDisplay()
        ScheduleType.TAPER        -> "Taper schedule"
    }

    /**
     * Builds a human-readable time list from [ScheduleEntity.scheduledTimesJson].
     * Falls back to "Daily" when the JSON can't be parsed.
     */
    private fun ScheduleEntity.scheduledTimesDisplay(): String =
        try {
            val times = kotlinx.serialization.json.Json.decodeFromString<List<String>>(scheduledTimesJson ?: "[]")
            if (times.isEmpty()) "Daily"
            else "Daily at ${times.joinToString(", ")}"
        } catch (_: Exception) {
            "Daily"
        }

    private fun ScheduleEntity.windowRange(): String {
        val start = windowStartTime ?: return "Time window"
        val end   = windowEndTime   ?: return "Time window"
        return "Between $start and $end"
    }
}
