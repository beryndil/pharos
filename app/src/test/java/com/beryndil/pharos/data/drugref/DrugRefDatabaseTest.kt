package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.drugref.entity.DbMetaEntity
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity
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
 * Unit tests for [DrugRefDatabase] v2 entities and the bundled asset loader.
 *
 * The bundled asset (`drug_ref.db`) is accessible in Robolectric because
 * `android { testOptions { unitTests { isIncludeAndroidResources = true } } }` is set.
 *
 * Test data: assertions use STABLE entries from the bundled DB (1,640 drugs from the
 * June 2026 RxNav build). Counts are never asserted exactly — only known-present entries
 * are checked so the tests remain deterministic across DB rebuilds.
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

    // ── Bundled asset loader ──────────────────────────────────────────────

    @Test
    fun fixtureLoadsDrugSearchRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()
        assertNotNull("Asset load must not return null", data)
        assertTrue("drug_search must have rows", data!!.drugs.isNotEmpty())
    }

    @Test
    fun fixtureLoadsIngredientEdges() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()
        assertNotNull(data)
        assertTrue("ingredient_map must have edges", data!!.ingredientEdges.isNotEmpty())
    }

    @Test
    fun fixtureLoadsDbMetaEntries() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()
        assertNotNull(data)
        assertTrue("db_meta must have entries", data!!.metaEntries.isNotEmpty())
    }

    @Test
    fun fixtureRoundTripsIntoRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!

        db.drugSearchDao().insertAll(data.drugs)
        db.ingredientMapDao().insertAll(data.ingredientEdges)
        db.dbMetaDao().insertAll(data.metaEntries)

        assertEquals(
            "All drug_search rows must be in Room",
            data.drugs.size,
            db.drugSearchDao().count(),
        )
        assertEquals(
            "All ingredient_map edges must be in Room",
            data.ingredientEdges.size,
            db.ingredientMapDao().count(),
        )
    }

    // ── Stable real-data assertions ───────────────────────────────────────

    @Test
    fun search_metoprolol_returnsRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!
        db.drugSearchDao().insertAll(data.drugs)

        val results = db.drugSearchDao().searchByName("metoprolol")
        assertTrue("Search for 'metoprolol' must return rows", results.isNotEmpty())
        assertTrue(
            "At least one result must contain 'metoprolol'",
            results.any { it.name.contains("metoprolol", ignoreCase = true) },
        )
    }

    @Test
    fun ingredientMap_acetaminophenCodeine_hasTwoIngredients() = runTest {
        // acetaminophen / codeine (rxcui 817579) has 2 ingredient edges in the bundled DB.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!
        db.ingredientMapDao().insertAll(data.ingredientEdges)

        val edges = db.ingredientMapDao().ingredientsForDrug("817579")
        assertEquals(
            "acetaminophen/codeine (rxcui 817579) must have exactly 2 ingredient edges",
            2,
            edges.size,
        )
        assertTrue(
            "Must include acetaminophen (ingredient_rxcui 161)",
            edges.any { it.ingredientRxcui == "161" },
        )
        assertTrue(
            "Must include codeine (ingredient_rxcui 2670)",
            edges.any { it.ingredientRxcui == "2670" },
        )
    }

    @Test
    fun dbMeta_hasSourceAndBuiltKeys() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = BundledDrugRefLoader(context).load()!!
        db.dbMetaDao().insertAll(data.metaEntries)

        val source = db.dbMetaDao().get("source")
        assertNotNull("db_meta must have 'source' key", source)
        assertTrue("source value must be non-blank", source!!.value.isNotBlank())

        val built = db.dbMetaDao().get("built")
        assertNotNull("db_meta must have 'built' key", built)
        assertTrue("built value must be non-blank", built!!.value.isNotBlank())
    }

    // ── Entity round-trips ────────────────────────────────────────────────

    @Test
    fun insertAndQueryDrugSearch() = runTest {
        val drug = DrugSearchEntity(rxcui = "161", name = "Acetaminophen", nameLower = "acetaminophen", tty = "IN")
        db.drugSearchDao().insertAll(listOf(drug))
        val found = db.drugSearchDao().getByRxcui("161")
        assertNotNull(found)
        assertEquals("Acetaminophen", found!!.name)
        assertEquals("IN", found.tty)
    }

    @Test
    fun insertAndQueryIngredientMap() = runTest {
        val edge = IngredientMapEntity(drugRxcui = "209387", ingredientRxcui = "161", ingredientName = "Acetaminophen")
        db.ingredientMapDao().insertAll(listOf(edge))
        val edges = db.ingredientMapDao().ingredientsForDrug("209387")
        assertEquals(1, edges.size)
        assertEquals("161", edges.first().ingredientRxcui)
        assertEquals("Acetaminophen", edges.first().ingredientName)
    }

    @Test
    fun insertAndQueryDbMeta() = runTest {
        val entry = DbMetaEntity(key = "source", value = "Test source")
        db.dbMetaDao().insertAll(listOf(entry))
        val found = db.dbMetaDao().get("source")
        assertNotNull(found)
        assertEquals("Test source", found!!.value)
    }

    @Test
    fun drugSearch_searchByName_prefixFirst() = runTest {
        // Insert two drugs: one prefix match and one contains-only match.
        val prefix = DrugSearchEntity(rxcui = "1", name = "metoprolol succinate", nameLower = "metoprolol succinate", tty = "PIN")
        val contains = DrugSearchEntity(rxcui = "2", name = "Lopressor metoprolol tartrate", nameLower = "lopressor metoprolol tartrate", tty = "BN")
        db.drugSearchDao().insertAll(listOf(contains, prefix)) // insert contains first
        val results = db.drugSearchDao().searchByName("metoprolol")
        assertEquals(2, results.size)
        // Prefix match must come first
        assertTrue(
            "Prefix match must rank before contains-only",
            results.indexOfFirst { it.rxcui == "1" } < results.indexOfFirst { it.rxcui == "2" },
        )
    }

    // ── Seed callback (regression: raw INSERT column names must match the Room schema) ──────────

    /**
     * Builds the DB through the REAL [DrugRefDatabaseFactory.build] so the SeedCallback's raw
     * execSQL runs against the actual Room-generated schema. Verifies the seeding inserts
     * populate all three tables without a schema-mismatch error.
     */
    @Test
    fun factoryBuild_seedCallbackPopulatesAllTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getDatabasePath(DrugRefDatabaseFactory.DATABASE_NAME).delete()
        val realDb = DrugRefDatabaseFactory.build(context)
        try {
            assertTrue("SeedCallback must populate drug_search", realDb.drugSearchDao().count() > 0)
            assertTrue("SeedCallback must populate ingredient_map", realDb.ingredientMapDao().count() > 0)
            val allMeta = realDb.dbMetaDao().all()
            assertTrue("SeedCallback must populate db_meta", allMeta.isNotEmpty())
        } finally {
            realDb.close()
            context.getDatabasePath(DrugRefDatabaseFactory.DATABASE_NAME).delete()
        }
    }

    // ── Newer-schema guard ────────────────────────────────────────────────

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
