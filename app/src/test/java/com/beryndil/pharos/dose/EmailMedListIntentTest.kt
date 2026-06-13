package com.beryndil.pharos.dose

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the email-med-list intent assembly (F4).
 *
 * Tests the [buildEmailMedListIntent] builder function in isolation — verifies action, MIME type,
 * subject, stream URI, and read-URI flag without exercising the actual share-sheet or FileProvider.
 *
 * Uses Robolectric because [android.content.Intent] is an Android class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailMedListIntentTest {

    private val testUri: Uri = Uri.parse("content://com.example.fileprovider/exports/medication-list.pdf")
    private val testSubject = "My medication list"
    private val testChooserTitle = "Send medication list"

    @Test
    fun `intent action is ACTION_SEND`() {
        val intent = buildEmailMedListIntent(testUri, testSubject)
        assertEquals(Intent.ACTION_SEND, intent.action)
    }

    @Test
    fun `intent type is application-pdf`() {
        val intent = buildEmailMedListIntent(testUri, testSubject)
        assertEquals("application/pdf", intent.type)
    }

    @Test
    fun `intent subject matches`() {
        val intent = buildEmailMedListIntent(testUri, testSubject)
        assertEquals(testSubject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `intent stream URI matches`() {
        val intent = buildEmailMedListIntent(testUri, testSubject)
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(testUri, stream)
    }

    @Test
    fun `intent has FLAG_GRANT_READ_URI_PERMISSION`() {
        val intent = buildEmailMedListIntent(testUri, testSubject)
        assertTrue(
            "Expected FLAG_GRANT_READ_URI_PERMISSION",
            (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun `wrapped chooser intent is not null`() {
        val shareIntent = buildEmailMedListIntent(testUri, testSubject)
        val chooser = Intent.createChooser(shareIntent, testChooserTitle)
        assertNotNull(chooser)
    }
}

/**
 * Builds the ACTION_SEND intent for sharing the medication-list PDF.
 *
 * Extracted as a top-level function so it can be unit-tested independently of the Compose
 * [LaunchedEffect] that drives it in [com.beryndil.pharos.dose.ui.TodayScreen].
 *
 * @param pdfUri   FileProvider URI granted read access to the receiving app.
 * @param subject  EXTRA_SUBJECT for the email (the "My medication list" string).
 */
fun buildEmailMedListIntent(pdfUri: Uri, subject: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, pdfUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
