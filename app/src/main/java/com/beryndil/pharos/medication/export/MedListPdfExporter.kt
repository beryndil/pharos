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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Renders the current medication list as a human-readable PDF.
 *
 * Extracted from the Backup module so both the Backup export screen (SAF URI path) and the
 * Today-screen "Email to doctor" action (cache-file path) share the same rendering logic
 * without duplication.
 *
 * Laws:
 *  - Law 4: this class only writes bytes; callers decide the destination. No off-device
 *    transmission occurs here — that is the caller's responsibility with a user-initiated intent.
 *  - Law 3: the PDF header contains the standard disclaimer. No advice language.
 */
class MedListPdfExporter(private val db: RegimenDatabase) {

    /**
     * Renders the medication list to [outputStream] as an A4 PDF.
     *
     * Runs on [Dispatchers.IO]. Caller is responsible for closing [outputStream].
     *
     * @param exportedAtEpochMs Epoch-ms stamped in the PDF header as the export date.
     */
    suspend fun writeTo(
        outputStream: OutputStream,
        exportedAtEpochMs: Long = Instant.now().toEpochMilli(),
    ) = withContext(Dispatchers.IO) {
        val medications = db.medicationDao().getAll()
        val schedules   = db.scheduleDao().getAll()
        val refills     = db.refillRecordDao().getAll()

        val scheduleMap  = schedules.groupBy { it.medicationId }
        val latestRefill = refills.groupBy { it.medicationId }
            .mapValues { (_, records) -> records.maxByOrNull { it.createdAtEpochMs } }

        val pdfDoc     = PdfDocument()
        val pageWidth  = 595   // A4 points
        val pageHeight = 842
        val margin     = 40f
        val lineHeight = 18f

        var pageNum = 1
        var page   = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas: Canvas = page.canvas
        var y = margin + 20f

        val titlePaint      = buildPaint(18f, Color.BLACK,  bold = true)
        val subtitlePaint   = buildPaint(11f, Color.DKGRAY)
        val medNamePaint    = buildPaint(13f, Color.BLACK,  bold = true)
        val detailPaint     = buildPaint(11f, Color.DKGRAY)
        val disclaimerPaint = buildPaint(9f,  Color.GRAY)

        // Starts a new page if [requiredY] would overflow the current one; returns the new y.
        fun newPageIfNeeded(requiredY: Float): Float =
            if (requiredY > pageHeight - margin - 30f) {
                pdfDoc.finishPage(page)
                pageNum++
                page   = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                margin + 20f
            } else {
                requiredY
            }

        // Renders multi-line text within page margins (wraps long strings). Returns y past last line.
        val maxTextWidth = (pageWidth - margin * 2).toInt()
        fun drawWrapped(text: String, startY: Float, paint: Paint): Float {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), maxTextWidth).build()
            val currentY = newPageIfNeeded(startY + layout.height)
            canvas.save()
            canvas.translate(margin, currentY)
            layout.draw(canvas)
            canvas.restore()
            return currentY + layout.height
        }

        // ── Header ───────────────────────────────────────────────────────
        canvas.drawText("Pharos — Medication List", margin, y, titlePaint)
        y += lineHeight + 4f
        val dateStr = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(exportedAtEpochMs))
        canvas.drawText("Exported $dateStr", margin, y, subtitlePaint)
        y += lineHeight + 4f
        // Disclaimer — Law 3: reference only, never advice.
        y = drawWrapped(
            "This list is for reference only. Check with your doctor or pharmacist before making any changes.",
            y, disclaimerPaint,
        )
        y += 12f

        // ── Medication rows ───────────────────────────────────────────────
        for (med in medications) {
            y = newPageIfNeeded(y + lineHeight * 3)
            canvas.drawText(med.name, margin, y, medNamePaint)
            y += lineHeight
            canvas.drawText("${med.strength} · ${med.form}", margin, y, detailPaint)
            y += lineHeight

            val activeSchedules = scheduleMap[med.id]?.filter { it.isActive } ?: emptyList()
            if (activeSchedules.isNotEmpty()) {
                y = drawWrapped("Schedule: ${activeSchedules.joinToString("; ") { it.toDisplayString() }}", y, detailPaint)
            }
            if (!med.prescriber.isNullOrBlank()) {
                y = drawWrapped("Prescriber: ${med.prescriber}", y, detailPaint)
            }
            if (!med.pharmacy.isNullOrBlank()) {
                y = drawWrapped("Pharmacy: ${med.pharmacy}", y, detailPaint)
            }
            latestRefill[med.id]?.let { refill ->
                canvas.drawText("Supply: ${refill.quantityOnHand} ${refill.quantityUnit}", margin, y, detailPaint)
                y += lineHeight
            }
            val statusLabel = when (med.status) {
                MedicationStatus.PAUSED.name -> " (paused)"
                MedicationStatus.ENDED.name  -> " (ended)"
                else                         -> ""
            }
            if (statusLabel.isNotEmpty()) {
                canvas.drawText("Status$statusLabel", margin, y, detailPaint)
                y += lineHeight
            }
            y += 8f
        }

        pdfDoc.finishPage(page)
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildPaint(textSize: Float, color: Int, bold: Boolean = false): Paint =
        Paint().apply {
            this.textSize  = textSize
            this.color     = color
            this.typeface  = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun ScheduleEntity.toDisplayString(): String = when (
        runCatching { ScheduleType.valueOf(type) }.getOrElse { ScheduleType.FIXED_DAILY }
    ) {
        ScheduleType.FIXED_DAILY  -> "Daily"
        ScheduleType.DAYS_OF_WEEK -> "Selected days"
        ScheduleType.INTERVAL     -> intervalHours?.let { "Every ${it}h" } ?: "Interval"
        ScheduleType.DOSE_WINDOW  -> "Time window"
        ScheduleType.PRN          -> "As needed"
        ScheduleType.TEMPORARY    -> "Course"
        ScheduleType.TAPER        -> "Taper"
    }
}
