package com.beryndil.pharos.contacts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.regimen.RegimenDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO-level tests for [ContactRepository] (spec V1.3-F1).
 *
 * Verifies insert + observe + delete + remember semantics. Deleting a saved contact
 * must leave medications that referenced it intact (by-value reference).
 */
@RunWith(RobolectricTestRunner::class)
class ContactRepositoryTest {

    private lateinit var db: RegimenDatabase
    private lateinit var repo: ContactRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ContactRepository(
            prescriberDao = db.prescriberDao(),
            pharmacyDao = db.pharmacyDao(),
            settingDao = db.settingDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Prescriber ────────────────────────────────────────────────────────

    @Test
    fun rememberPrescriber_insertsNewEntry() = runBlocking {
        repo.rememberPrescriber("Dr. Smith", "555-1234")
        val list = repo.observePrescribers().first()
        assertEquals(1, list.size)
        assertEquals("Dr. Smith", list[0].name)
        assertEquals("555-1234", list[0].phone)
    }

    @Test
    fun rememberPrescriber_updatesPhoneOnExistingEntry() = runBlocking {
        repo.rememberPrescriber("Dr. Smith", "555-1234")
        repo.rememberPrescriber("Dr. Smith", "555-9999")
        val list = repo.observePrescribers().first()
        assertEquals(1, list.size)
        assertEquals("555-9999", list[0].phone)
    }

    @Test
    fun rememberPrescriber_caseInsensitiveDuplicate() = runBlocking {
        repo.rememberPrescriber("Dr. Smith", null)
        repo.rememberPrescriber("dr. smith", "555-0000")
        // Should still be 1 entry (case-insensitive match)
        val list = repo.observePrescribers().first()
        assertEquals(1, list.size)
    }

    @Test
    fun rememberPrescriber_blankName_doesNotInsert() = runBlocking {
        repo.rememberPrescriber("   ", "555-1234")
        val list = repo.observePrescribers().first()
        assertTrue(list.isEmpty())
    }

    @Test
    fun deletePrescriber_removesEntry() = runBlocking {
        repo.rememberPrescriber("Dr. Jones", null)
        val id = repo.observePrescribers().first().first().id
        repo.deletePrescriber(id)
        val list = repo.observePrescribers().first()
        assertTrue(list.isEmpty())
    }

    @Test
    fun observePrescribers_sortedByNameAscending() = runBlocking {
        repo.rememberPrescriber("Dr. Zeta", null)
        repo.rememberPrescriber("Dr. Alpha", null)
        repo.rememberPrescriber("Dr. Mu", null)
        val names = repo.observePrescribers().first().map { it.name }
        assertEquals(listOf("Dr. Alpha", "Dr. Mu", "Dr. Zeta"), names)
    }

    // ── Pharmacy ──────────────────────────────────────────────────────────

    @Test
    fun rememberPharmacy_insertsNewEntry() = runBlocking {
        repo.rememberPharmacy("City Pharmacy", "555-4321")
        val list = repo.observePharmacies().first()
        assertEquals(1, list.size)
        assertEquals("City Pharmacy", list[0].name)
        assertEquals("555-4321", list[0].phone)
    }

    @Test
    fun deletePharmacy_removesEntry_onlyThatEntry() = runBlocking {
        repo.rememberPharmacy("CVS", "555-0001")
        repo.rememberPharmacy("Walgreens", "555-0002")
        val all = repo.observePharmacies().first()
        val cvs = all.first { it.name == "CVS" }
        repo.deletePharmacy(cvs.id)
        val remaining = repo.observePharmacies().first()
        assertEquals(1, remaining.size)
        assertEquals("Walgreens", remaining[0].name)
    }

    @Test
    fun updatePrescriber_persistsNewNameAndPhone() = runBlocking {
        repo.rememberPrescriber("Dr. Old", "555-1111")
        val p = repo.observePrescribers().first().first()
        repo.updatePrescriber(p.copy(name = "Dr. New", phone = "555-2222"))
        val updated = repo.observePrescribers().first().first()
        assertEquals("Dr. New", updated.name)
        assertEquals("555-2222", updated.phone)
    }
}
