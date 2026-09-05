package com.lfq06.arknightsreader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.database.Mappers.toModel
import com.lfq06.arknightsreader.design.ReaderPalettes
import com.lfq06.arknightsreader.design.ReaderThemeId
import com.lfq06.arknightsreader.importer.ImportService
import com.lfq06.arknightsreader.importer.ResolverDocuments
import com.lfq06.arknightsreader.library.LibraryScreen
import com.lfq06.arknightsreader.library.LibraryViewModel
import com.lfq06.arknightsreader.preload.PreloadManager
import com.lfq06.arknightsreader.reader.ReaderScreen
import com.lfq06.arknightsreader.reader.ReaderViewModel
import com.lfq06.arknightsreader.reader.LayoutSpec
import com.lfq06.arknightsreader.reader.Paginator
import com.lfq06.arknightsreader.reader.BookSearcher
import com.lfq06.arknightsreader.settings.GlassMode
import com.lfq06.arknightsreader.settings.GlassPreset
import com.lfq06.arknightsreader.settings.ReaderPrefs
import com.lfq06.arknightsreader.settings.ReaderPrefsStore
import com.lfq06.arknightsreader.settings.ThemeId
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * App shell: bookshelf (default) <-> reader, with the global prefs store
 * driving typography/theme/glass settings end to end.
 */
class MainActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.build(applicationContext) }
    private val coversDir by lazy { File(filesDir, "covers").apply { mkdirs() } }
    private val prefsStore by lazy { ReaderPrefsStore(applicationContext) }
    private val importer by lazy {
        ImportService(applicationContext, db, ResolverDocuments(contentResolver))
    }

    /** Which screen is showing; null = library. */
    private var openBookId by mutableStateOf<String?>(null)

    // Reader runtime (created on open).
    private var readerVm: ReaderViewModel? = null
    private var readerCurrentPage by mutableStateOf(0)
    private var readerChapters by mutableStateOf<List<Pair<String, Int>>>(emptyList())

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            importFrom(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by prefsStore.prefs.collectAsState(ReaderPrefs())
            val readerTheme = when (prefs.themeId) {
                ThemeId.PARCHMENT -> ReaderThemeId.PARCHMENT
                ThemeId.DARK -> ReaderThemeId.DARK
                ThemeId.EYE_COMFORT -> ReaderThemeId.EYE_COMFORT
                ThemeId.PURE_BLACK -> ReaderThemeId.PURE_BLACK
            }
            com.lfq06.arknightsreader.design.ArknightsReaderTheme(readerTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val bookId = openBookId
                    if (bookId == null) {
                        LibraryHost(prefs)
                    } else {
                        ReaderHost(bookId, prefs)
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryHost(prefs: ReaderPrefs) {
        val viewModel = remember { LibraryViewModel(db, coversDir) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            val loaded = PreloadManager.ensureLoaded(applicationContext, db)
            if (loaded > 0) {
                Toast.makeText(
                    this@MainActivity,
                    "已载入 $loaded 本内置内容（非官方，仅供本地阅读交流）",
                    Toast.LENGTH_LONG,
                ).show()
            }
            viewModel.refresh()
        }

        LibraryScreen(
            viewModel = viewModel,
            onBookClick = { id ->
                openBookId = id
            },
            onImportClick = {
                openDocument.launch(arrayOf("text/*", "application/epub+zip", "application/octet-stream"))
            },
        )
    }

    @Composable
    private fun ReaderHost(bookId: String, prefs: ReaderPrefs) {
        val palette = ReaderPalettes.forId(readerThemeOf(prefs.themeId))
        val vm = remember(bookId) {
            ReaderViewModel(db) { chapterIndex, spec ->
                val chapters = runBlocking {
                    val ids = db.chapterDao().queryByBookOrdered(bookId)
                    val cid = ids.getOrNull(chapterIndex)?.id ?: return@runBlocking com.lfq06.arknightsreader.reader.PageMap(emptyList(), spec)
                    val blocks = db.blockDao().queryByChapterOrdered(cid).map { it.toModel() }
                    Paginator.paginate(applicationContext, blocks, spec)
                }
                chapters
            }
        }
        val scope = rememberCoroutineScope()

        LaunchedEffect(bookId) {
            vm.open(
                bookId,
                LayoutSpec(
                    pageWidthPx = resources.displayMetrics.widthPixels,
                    pageHeightPx = resources.displayMetrics.heightPixels,
                    marginsPx = prefs.typography.marginDp * 3, // dp -> px at ~xxxhdpi
                    textSizePx = prefs.typography.fontSizeSp * 3,
                    lineHeightFactor = prefs.typography.lineHeightFactor,
                    mode = com.lfq06.arknightsreader.model.LayoutMode.SINGLE,
                ),
            )
            readerChapters = db.chapterDao().queryByBookOrdered(bookId)
                .map { (it.toModel().title ?: "") to it.toModel().orderIndex }
        }

        ReaderScreen(
            state = vm.state.collectAsState().value,
            currentPage = vm.currentPage,
            paperColor = palette.paper.hashCode(),
            proseColor = palette.prose.hashCode(),
            textSizePx = prefs.typography.fontSizeSp * 3,
            glassMode = when (prefs.glassMode) {
                GlassMode.FULL -> com.lfq06.arknightsreader.design.GlassMode.FULL
                GlassMode.SIMPLIFIED -> com.lfq06.arknightsreader.design.GlassMode.SIMPLIFIED
                GlassMode.OFF -> com.lfq06.arknightsreader.design.GlassMode.OFF
            },
            glassPreset = when (prefs.glassPreset) {
                GlassPreset.RESTRAINED -> com.lfq06.arknightsreader.design.GlassPreset.RESTRAINED
                GlassPreset.CLEAR -> com.lfq06.arknightsreader.design.GlassPreset.CLEAR
                GlassPreset.SOFT -> com.lfq06.arknightsreader.design.GlassPreset.SOFT
            },
            themeId = readerThemeOf(prefs.themeId),
            chapters = readerChapters,
            onBack = {
                scope.launch { vm.saveNow() }
                openBookId = null
            },
            onNext = { scope.launch { if (vm.nextPage()) readerCurrentPage = vm.currentPage } },
            onPrev = { scope.launch { if (vm.prevPage()) readerCurrentPage = vm.currentPage } },
            onJumpChapter = { idx ->
                scope.launch {
                    vm.jumpToChapter(idx)
                    readerCurrentPage = vm.currentPage
                }
            },
            onJumpPage = { page ->
                scope.launch {
                    vm.jumpToPage(page)
                    readerCurrentPage = vm.currentPage
                }
            },
            onSearchQuery = { query ->
                val hits = BookSearcher(db).search(query)
                hits.map { it.blockId to chapterIndexForBlock(it.blockId) }
            },
            onSearchJump = { blockId, chapterIndex ->
                scope.launch {
                    vm.jumpToBlock(blockId, chapterIndex)
                    readerCurrentPage = vm.currentPage
                }
            },
            onFontSizeChange = { sp ->
                scope.launch {
                    prefsStore.setTypography(prefs.typography.copy(fontSizeSp = sp))
                }
            },
            onLineHeightChange = { f ->
                scope.launch {
                    prefsStore.setTypography(prefs.typography.copy(lineHeightFactor = f))
                }
            },
            onMarginChange = { m ->
                scope.launch {
                    prefsStore.setTypography(prefs.typography.copy(marginDp = m))
                }
            },
            onThemeChange = { t ->
                scope.launch {
                    prefsStore.setThemeId(
                        when (t) {
                            ReaderThemeId.PARCHMENT -> ThemeId.PARCHMENT
                            ReaderThemeId.DARK -> ThemeId.DARK
                            ReaderThemeId.EYE_COMFORT -> ThemeId.EYE_COMFORT
                            ReaderThemeId.PURE_BLACK -> ThemeId.PURE_BLACK
                        },
                    )
                }
            },
        )
    }

    private fun readerThemeOf(id: ThemeId): ReaderThemeId = when (id) {
        ThemeId.PARCHMENT -> ReaderThemeId.PARCHMENT
        ThemeId.DARK -> ReaderThemeId.DARK
        ThemeId.EYE_COMFORT -> ReaderThemeId.EYE_COMFORT
        ThemeId.PURE_BLACK -> ReaderThemeId.PURE_BLACK
    }

    private fun chapterIndexForBlock(blockId: String): Int =
        blockId.substringAfter("-c").substringBefore("-b").toIntOrNull() ?: 0

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            importer.import(uri.toString()).collect { event ->
                when (event) {
                    is ImportService.ImportProgress.Done ->
                        Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
                    is ImportService.ImportProgress.Failed ->
                        Toast.makeText(this@MainActivity, "导入失败：${event.reason}", Toast.LENGTH_LONG).show()
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }
}
