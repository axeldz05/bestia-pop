package com.bestiapop.android.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bestiapop.android.domain.util.GenreTaxonomy
import com.bestiapop.android.testutil.MediumTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Category(MediumTest::class)
class GenreTaxonomyRepositoryTest {
    private lateinit var app: Application
    private lateinit var repository: GenreTaxonomyRepository
    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        cacheFile = File(app.filesDir, "genre_taxonomy_cache.json")
        cacheFile.delete()
        GenreTaxonomy.clearDynamicTranslations()
        repository = GenreTaxonomyRepository(app)
    }

    @After
    fun tearDown() {
        cacheFile.delete()
        GenreTaxonomy.clearDynamicTranslations()
    }

    @Test
    fun loadBundledAsset_registersGenres() =
        runTest {
            repository.loadBundledAsset()
            assertTrue(GenreTaxonomy.hasMapping("shoegaze"))
            assertTrue(GenreTaxonomy.hasMapping("ambient"))
            assertTrue(GenreTaxonomy.matchesGenre("Rock Psicodélico", "psychedelic rock"))
            assertTrue(GenreTaxonomy.matchesGenre("Ambiental", "ambient"))
        }

    @Test
    fun dynamicTranslation_registersAndExpands() =
        runTest {
            assertFalse(GenreTaxonomy.hasMapping("dream pop custom"))

            GenreTaxonomy.registerDynamicTranslation("dream pop custom", "pop ensueño")
            assertTrue(GenreTaxonomy.hasMapping("dream pop custom"))
            assertTrue(GenreTaxonomy.matchesGenre("Dream Pop Custom", "pop ensueño"))
            assertTrue(GenreTaxonomy.matchesGenre("Pop Ensueño", "dream pop custom"))
        }

    @Test
    fun persistedCache_survivesReload() =
        runTest {
            // Write to cache file
            val json = """{"niche metal": ["metal de nicho"]}"""
            cacheFile.writeText(json)

            repository.loadPersistedCache()
            assertTrue(GenreTaxonomy.hasMapping("niche metal"))
            assertTrue(GenreTaxonomy.matchesGenre("Niche Metal", "metal de nicho"))
        }
}
