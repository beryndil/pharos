package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.drugref.entity.IngredientEntity
import com.beryndil.pharos.data.drugref.entity.ProductEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DrugRefDatabase] entities and the bundled fixture loader.
 *
 * The fixture asset (drug_ref_fixture.db) is accessible in Robolectric because
 * [android { testOptions { unitTests { isIncludeAndroidResources = true } } }] is set.
 */
@RunWith(RobolectricTestRunner::class)
class DrugRefDatabaseTest {

    private lateinit var db: DrugRefDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DrugRefDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── fixture loader ────────────────────────────────────────────────────

    @Test
    fun fixtureLoadsIngredients() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()
        assertNotNull("Fixture data must not be null (asset present?)", data)
        assertTrue("Fixture must contain at least one ingredient", data!!.ingredients.isNotEmpty())
    }

    @Test
    fun fixtureLoadsProducts() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()
        assertNotNull(data)
        assertTrue("Fixture must contain at least one product", data!!.products.isNotEmpty())
    }

    @Test
    fun fixtureRoundTripsIntoRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!

        db.ingredientDao().insertAll(data.ingredients)
        db.productDao().insertAll(data.products)

        assertEquals(
            "All fixture ingredients must be in Room",
            data.ingredients.size,
            db.ingredientDao().count(),
        )
        assertEquals(
            "All fixture products must be in Room",
            data.products.size,
            db.productDao().count(),
        )
    }

    @Test
    fun fixtureContainsAcetaminophen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!
        db.ingredientDao().insertAll(data.ingredients)

        val acetaminophen = db.ingredientDao().getByRxcui("161")
        assertNotNull("Acetaminophen (RxCUI 161) must be in fixture", acetaminophen)
        assertEquals("161", acetaminophen!!.rxcui)
        assertTrue(acetaminophen.name.contains("Acetaminophen", ignoreCase = true))
    }

    @Test
    fun ingredientSearchByNameReturnsResults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!
        db.ingredientDao().insertAll(data.ingredients)

        val results = db.ingredientDao().searchByName("Aceta")
        assertFalse("Search for 'Aceta' must return results", results.isEmpty())
        assertTrue(results.any { it.name.contains("Acetaminophen", ignoreCase = true) })
    }

    // ── entity round-trip ─────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveIngredient() = runTest {
        val ing = IngredientEntity(rxcui = "999", name = "TestDrug", tty = "IN")
        db.ingredientDao().insertAll(listOf(ing))
        val retrieved = db.ingredientDao().getByRxcui("999")
        assertEquals(ing, retrieved)
    }

    @Test
    fun insertAndRetrieveProduct() = runTest {
        val prod = ProductEntity(
            rxcui = "99901",
            name = "TestDrug 10 mg Tablet",
            ingredientsJson = """["999"]""",
            form = "Tablet",
            strength = "10 mg",
        )
        db.productDao().insertAll(listOf(prod))
        val retrieved = db.productDao().getByRxcui("99901")
        assertEquals(prod, retrieved)
    }

    // ── newer-schema guard ────────────────────────────────────────────────

    @Test
    fun newerSchemaGuard_deletesFileForNewerVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val dbPath = context.getDatabasePath(DrugRefDatabaseFactory.DATABASE_NAME)
        dbPath.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbPath.path, null).use { it.setVersion(999) }

        assertTrue("File must exist before guard runs", dbPath.exists())
        DrugRefDatabaseFactory.handleNewerSchema(context)
        assertFalse("File must be deleted after guard detects newer schema", dbPath.exists())
    }

    @Test
    fun newerSchemaGuard_keepsFileForCurrentVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val dbPath = context.getDatabasePath(DrugRefDatabaseFactory.DATABASE_NAME)
        dbPath.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbPath.path, null)
            .use { it.setVersion(DrugRefDatabaseFactory.CURRENT_VERSION) }

        DrugRefDatabaseFactory.handleNewerSchema(context)
        assertTrue("File must be kept for current-version schema", dbPath.exists())
        dbPath.delete()
    }
}
