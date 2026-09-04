package com.lfq06.arknightsreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.lfq06.arknightsreader.database.AppDatabase
import com.lfq06.arknightsreader.importer.ImportService
import com.lfq06.arknightsreader.importer.ResolverDocuments
import com.lfq06.arknightsreader.library.LibraryScreen
import com.lfq06.arknightsreader.library.LibraryViewModel
import com.lfq06.arknightsreader.preload.PreloadManager
import com.lfq06.arknightsreader.ui.theme.ArknightsReaderTheme
import java.io.File
import kotlinx.coroutines.launch

/**
 * Bookshelf host: the app's default launcher. Owns the SAF document picker
 * and the preinstalled-content preload, and shows the Compose library.
 * Page Curl Lab remains available as a separate launcher icon.
 */
class MainActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.build(applicationContext) }
    private val coversDir by lazy { File(filesDir, "covers").apply { mkdirs() } }
    private val importer by lazy {
        ImportService(applicationContext, db, ResolverDocuments(contentResolver))
    }

    private var importEvents by mutableStateOf<String?>(null)

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Take a persistable grant when offered so the source stays
            // readable across reboots; the importer also keeps a hash-based
            // identity so re-imports dedupe.
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
            ArknightsReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel = remember { LibraryViewModel(db, coversDir) }
                    val scope = rememberCoroutineScope()
                    val refresh = { scope.launch { viewModel.refresh() } }

                    // One-time preinstalled-content load (debug fixtures).
                    LaunchedEffect(Unit) {
                        val loaded = PreloadManager.ensureLoaded(applicationContext, db)
                        if (loaded > 0) {
                            Toast.makeText(
                                this@MainActivity,
                                "已载入 $loaded 本内置内容（非官方，仅供本地阅读交流）",
                                Toast.LENGTH_LONG,
                            ).show()
                            refresh()
                        }
                    }

                    LibraryScreen(
                        viewModel = viewModel,
                        onBookClick = { bookId ->
                            // The reader screen lands in the next volume; for
                            // now report the selection so wiring is verifiable.
                            Toast.makeText(this, "打开阅读器：$bookId（下一版本接入）", Toast.LENGTH_SHORT).show()
                        },
                        onImportClick = {
                            openDocument.launch(arrayOf("text/*", "application/epub+zip", "application/octet-stream"))
                        },
                    )
                }
            }
        }
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            importEvents = "导入中…"
            importer.import(uri.toString()).collect { event ->
                importEvents = event.toString().substringAfterLast('.').removeSuffix(")")
                if (event is ImportService.ImportProgress.Done) {
                    Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
                    importEvents = null
                } else if (event is ImportService.ImportProgress.Failed) {
                    Toast.makeText(this@MainActivity, "导入失败：${event.reason}", Toast.LENGTH_LONG).show()
                    importEvents = null
                }
            }
        }
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }
}
