package com.lfq06.arknightsreader.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.lfq06.arknightsreader.design.GlassMode
import com.lfq06.arknightsreader.design.GlassPreset
import com.lfq06.arknightsreader.design.ReaderShapes
import com.lfq06.arknightsreader.design.ReaderSurface
import com.lfq06.arknightsreader.design.ReaderThemeId

/** Which auxiliary panel is open over the page. */
enum class ReaderPanel { NONE, TOC, SEARCH, DISPLAY }

/**
 * Full-screen reader: page canvas with tap-to-toggle chrome, a glass bottom
 * progress pill, and TOC/search/display panels. Turn-engine integration
 * (physical curl on drag) rides on the same [PageMap] and page bitmaps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderViewModel.OpenBook?,
    currentPage: Int,
    paperColor: Int,
    proseColor: Int,
    textSizePx: Int,
    glassMode: GlassMode,
    glassPreset: GlassPreset,
    themeId: ReaderThemeId,
    chapters: List<Pair<String, Int>>, // (title, chapterIndex)
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onJumpChapter: (Int) -> Unit,
    onJumpPage: (Int) -> Unit,
    onSearchQuery: suspend (String) -> List<Pair<String, Int>>, // (blockId, chapterIndex)
    onSearchJump: (String, Int) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onMarginChange: (Int) -> Unit,
    onThemeChange: (ReaderThemeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chromeVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(ReaderPanel.NONE) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The page itself: tap toggles chrome; side taps turn pages.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { chromeVisible = !chromeVisible },
        ) {
            state?.let { s ->
                s.pageMap.pages.getOrNull(currentPage)?.let { page ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val bitmap = remember(page, textSizePx, paperColor, proseColor) {
                        PageDraw.renderPage(
                            context = context,
                            page = page,
                            widthPx = 1080,
                            heightPx = 1920,
                            textSizePx = textSizePx,
                            paperColor = paperColor,
                            proseColor = proseColor,
                        )
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "第 ${page.index + 1} 页",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (chromeVisible) {
            // Top bar (glass).
            ReaderSurface(
                mode = glassMode,
                preset = glassPreset,
                shape = ReaderShapes.toolbar,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("返回", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onBack() })
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = state?.let { "第${it.chapterIndex + 1}/${it.chapterCount}章" } ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text("目录", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { panel = if (panel == ReaderPanel.TOC) ReaderPanel.NONE else ReaderPanel.TOC })
                    Spacer(Modifier.width(16.dp))
                    Text("搜索", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { panel = if (panel == ReaderPanel.SEARCH) ReaderPanel.NONE else ReaderPanel.SEARCH })
                    Spacer(Modifier.width(16.dp))
                    Text("设置", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { panel = if (panel == ReaderPanel.DISPLAY) ReaderPanel.NONE else ReaderPanel.DISPLAY })
                }
            }

            // Bottom progress pill (glass).
            ReaderSurface(
                mode = glassMode,
                preset = glassPreset,
                shape = ReaderShapes.capsule,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .fillMaxWidth(0.7f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("上一页", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { onPrev() })
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${currentPage + 1} / ${state?.pageMap?.pages?.size ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("下一页", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { onNext() })
                }
            }
        }

        when (panel) {
            ReaderPanel.TOC -> TocPanel(chapters, onJumpChapter = { onJumpChapter(it); panel = ReaderPanel.NONE })
            ReaderPanel.SEARCH -> SearchPanel(
                onSearch = onSearchQuery,
                onJump = { blockId, chapterIndex -> onSearchJump(blockId, chapterIndex); panel = ReaderPanel.NONE },
                onDismiss = { panel = ReaderPanel.NONE },
            )
            ReaderPanel.DISPLAY -> DisplayPanel(
                textSizePx = textSizePx,
                themeId = themeId,
                onFontSizeChange = onFontSizeChange,
                onThemeChange = onThemeChange,
                onDismiss = { panel = ReaderPanel.NONE },
            )
            ReaderPanel.NONE -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocPanel(chapters: List<Pair<String, Int>>, onJumpChapter: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = {}) {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp).padding(16.dp)) {
            items(chapters, key = { it.second }) { (title, index) ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpChapter(index) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(
    onSearch: suspend (String) -> List<Pair<String, Int>>,
    onJump: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("书内搜索") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(onClick = {
                results = kotlinx.coroutines.runBlocking { onSearch(query) }
            }) { Text("搜索") }
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(results, key = { it.first }) { (blockId, chapterIndex) ->
                    Text(
                        text = "命中 $blockId（第${chapterIndex + 1}章）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(blockId, chapterIndex) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayPanel(
    textSizePx: Int,
    themeId: ReaderThemeId,
    onFontSizeChange: (Int) -> Unit,
    onThemeChange: (ReaderThemeId) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("显示设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("字号 ${textSizePx}px", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = textSizePx.toFloat(),
                onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = 28f..60f,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (t in ReaderThemeId.entries) {
                    Text(
                        text = when (t) {
                            ReaderThemeId.PARCHMENT -> "羊皮纸"
                            ReaderThemeId.DARK -> "夜间"
                            ReaderThemeId.EYE_COMFORT -> "护眼"
                            ReaderThemeId.PURE_BLACK -> "纯黑"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t == themeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clickable { onThemeChange(t) }
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
