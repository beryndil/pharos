package com.beryndil.pharos.medication.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Tests that [MedListPdfExporter] produces a valid PDF for a sample regimen.
 *
 * NOTE: [android.graphics.pdf.PdfDocument] relies on native Android rendering infrastructure
 * that is not available in the JVM unit-test environment (Robolectric's PdfDocument shadow
 * creates an already-closed document handle). These tests are annotated @Ignore and are
 * tracked as device-only items in TODO.md §C. The data-layer logic (DB reads, schedule
 * formatting) is exercised in integration via the full build; the PDF rendering itself is
 * verified on-device.
 *
 * To run these locally with a real Android environment: move to androidTest and run on an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedListPdfExporterTest {

    private lateinit var db: RegimenDatabase
    private lateinit var exporter: MedListPdfExporter

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        exporter = MedListPdfExporter(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun insertMed(
        name: String,
        strength: String = "10 mg",
        prescriber: String? = "Dr. Test",
        status: String = MedicationStatus.ACTIVE.name,
    ): MedicationEntity {
        val med = MedicationEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            rxcui = null,
            ingredientsJson = "[]",
            strength = strength,
            form = MedicationForm.TABLET.name,
            doseAmount = "1 tablet",
            prescriber = prescriber,
            prescriberPhone = null,
            pharmacy = null,
            pharmacyPhone = null,
            purpose = null,
            isFreeText = false,
            status = status,
            startEpochMs = 0L,
            endEpochMs = null,
            createdAtEpochMs = 1_000_000L,
            updatedAtEpochMs = 1_000_000L,
        )
        db.medicationDao().insert(med)
        return med
    }

    private suspend fun insertSchedule(medicationId: String) {
        db.scheduleDao().insert(
            ScheduleEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                type = ScheduleType.FIXED_DAILY.name,
                scheduledTimesJson = "[\"08:00\"]",
                daysOfWeekJson = null,
                intervalHours = null,
                intervalAnchorType = null,
                windowStartTime = null,
                windowEndTime = null,
                dailyMaxDoses = null,
                zoneId = "UTC",
                isActive = true,
                startEpochMs = 0L,
                endEpochMs = null,
                createdAtEpochMs = 1_000_000L,
            ),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────
    // Ignored: PdfDocument requires on-device native rendering (TODO.md §C).

    @Ignore("PdfDocument.startPage requires on-device native rendering — run as androidTest")
    @Test
    fun `empty regimen produces non-empty PDF bytes`() = runTest {
        val out = ByteArrayOutputStream()
        exporter.writeTo(out, exportedAtEpochMs = 1_718_000_000_000L)
        val bytes = out.toByteArray()
        assertTrue("Expected non-empty PDF output", bytes.isNotEmpty())
        assertTrue("Expected PDF magic bytes (%PDF)", String(bytes.take(4).toByteArray()) == "%PDF")
    }

    @Ignore("PdfDocument.startPage requires on-device native rendering — run as androidTest")
    @Test
    fun `single medication produces non-empty PDF`() = runTest {
        insertMed("Metformin", strength = "500 mg")
        val out = ByteArrayOutputStream()
        exporter.writeTo(out, exportedAtEpochMs = 1_718_000_000_000L)
        assertTrue(out.toByteArray().isNotEmpty())
    }

    @Ignore("PdfDocument.startPage requires on-device native rendering — run as androidTest")
    @Test
    fun `multiple medications with schedules produce non-empty PDF`() = runTest {
        val m1 = insertMed("Lisinopril", strength = "5 mg")
        val m2 = insertMed("Atorvastatin", strength = "20 mg", prescriber = null)
        insertSchedule(m1.id)
        insertSchedule(m2.id)
        val out = ByteArrayOutputStream()
        exporter.writeTo(out, exportedAtEpochMs = 1_718_000_000_000L)
        assertTrue(out.toByteArray().isNotEmpty())
    }

    @Ignore("PdfDocument.startPage requires on-device native rendering — run as androidTest")
    @Test
    fun `paused and ended medications are included in the PDF`() = runTest {
        insertMed("PausedMed", status = MedicationStatus.PAUSED.name)
        insertMed("EndedMed", status = MedicationStatus.ENDED.name)
        val out = ByteArrayOutputStream()
        exporter.writeTo(out, exportedAtEpochMs = 1_718_000_000_000L)
        assertTrue(out.toByteArray().isNotEmpty())
    }

    @Ignore("PdfDocument.startPage requires on-device native rendering — run as androidTest")
    @Test
    fun `very long medication name does not throw`() = runTest {
        insertMed("A".repeat(200), strength = "1 mg")
        val out = ByteArrayOutputStream()
        exporter.writeTo(out, exportedAtEpochMs = 1_718_000_000_000L)
        assertTrue(out.toByteArray().isNotEmpty())
    }
}
