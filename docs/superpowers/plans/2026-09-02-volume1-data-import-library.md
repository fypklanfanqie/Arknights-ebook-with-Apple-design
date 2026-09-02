# Arknights Reader — Volume 1: Data layer + Importer + Library

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the installable foundation of the reader: a Room-backed book/chapter/block/position data model, a secure SAF import pipeline (TXT/Markdown/EPUB parsers), a handwritten EPUB parser, and a Compose bookshelf that shows imported and debug-preinstalled books with progress, search, and filters.

**Architecture:** New pure-Kotlin `data/model` and `data/database` modules (Room), `format/api` + `format/text` + `format/epub` modules, and `feature/importer` + `feature/library` modules. Existing `reader/turn`/`turngl` remain untouched. The bookshelf becomes the debug default launcher; `CurlLabActivity` stays as a separate diagnostic entry.

**Tech Stack:** Kotlin 2.0 / AGP 8.7 / JDK 17 / compileSdk 35 / minSdk 26 / Room / DataStore / Compose BOM 2024.12 / JUnit5 + kotlin-test.

## Global Constraints

- `reader:turn` must stay pure JVM (zero Android dependencies); do not modify its behavior.
- Drag frames must never query Room, paginate, rebuild bitmaps, or upload full textures (this volume installs the repository pattern that later keeps those invariants; the reader itself is Volume 2).
- Non-DRM only; encrypted EPUB must be explicitly rejected.
- Import security: no `MANAGE_EXTERNAL_STORAGE`; use `ACTION_OPEN_DOCUMENT` + persistable URI + private copy; guard ZIP slip and XXE.
- minSdk 26, compileSdk 35, JDK 17. Use the existing version catalog (`gradle/libs.versions.toml`).
- TDD end-to-end: every behavior test runs RED first.
- Preinstalled Arknights content is debug-only, flagged non-official.
- No new third-party parser/runtime deps beyond Room/DataStore/Compose/Backdrop/Shapes (Backdrop/Shapes are Apache-2.0, archived in NOTICE).

---

### Task 1: Module scaffolding + pure model

**Files:**
- Create: `data/model/build.gradle.kts`, `data/database/build.gradle.kts`, `format/api/build.gradle.kts`, `format/text/build.gradle.kts`, `format/epub/build.gradle.kts`, `feature/importer/build.gradle.kts`, `feature/library/build.gradle.kts`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml` (add room, datastore, androidx-lifecycle-viewmodel-compose, junit, robolectric, compose test)
- Create: `data/model/src/main/kotlin/com/lfq06/arknightsreader/model/*.kt` (pure data classes: Book, Chapter, ContentBlock, Locator, ReadingPosition, Bookmark, Annotation, BookSettings, Persona)

**Interfaces:**
- Produces: `Book(id,title,author,source,format,formatVersion,coverPath,addedAt,lastOpenedAt,progressPct,capabilities)`, `Chapter(id,bookId,orderIndex,title,spineId,href)`, `ContentBlock(id,chapterId,orderIndex,kind,text,imageRef)`, `Locator(bookId,chapterId,blockId,charOffset,progression)`, `ReadingPosition`, `Bookmark`, `Annotation`, `BookSettings`, `LayoutFingerprint`.

- [ ] **Step 1: settings.gradle.kts includes the 7 new modules.**
- [ ] **Step 2: Pure model data classes compile with a trivial unit test (RED: modules absent → then GREEN after creation).**
- [ ] **Step 3: `./gradlew :data:model:test :format:api:test`** — pure JVM, no Android deps.
- [ ] **Step 4: Commit.**

### Task 2: Room schema + DAOs

**Files:**
- Create: `data/database/src/main/kotlin/com/lfq06/arknightsreader/db/Entities.kt`, `AppDatabase.kt`, `Dao/*.kt`
- Test: `data/database/src/test/.../*DaoTest.kt` (Robolectric, Room in-memory)

**Interfaces:**
- Consumes: `data/model` pure classes.
- Produces: `AppDatabase` (Room), DAOs: `BookDao`, `ChapterDao`, `BlockDao`, `PositionDao`, `BookmarkDao`, `AnnotationDao`, `SettingsDao`, `BookSearchDao` (FTS4).

- [ ] **Step 1: Failing DAO contract tests** (insert/query/delete/ordering/by-book/orphan-annotation query).
- [ ] **Step 2: RED run** (unresolved references).
- [ ] **Step 3: Implement entities, database, DAOs, FTS4 FtsEntry.**
- [ ] **Step 4: GREEN.** Report Room schema version 1 and migration train header.

### Task 3: Format API + TXT parser

**Files:**
- Create: `format/api/.../FormatModule.kt`, `Publication.kt`, `ContentBlock.kt`, `Locator.kt`, `ReadingCapabilities.kt`, `ParseException.kt`
- Create: `format/text/.../TextTxtModule.kt`, `EncodingDetector.kt`, `ChapterSplitter.kt`
- Test: `format/text/.../TxtModuleTest.kt`, `EncodingDetectorTest.kt`, `ChapterSplitterTest.kt`

**Interfaces:**
- Produces: `FormatModule.probe(source,size)`, `parse(source, bookId) -> List<Chapter>` (each with `List<ContentBlock>` with stable `blockId`), `capabilities()`, `close()`.

- [ ] **Step 1: Failing tests** — UTF-8/16/GB18030 detection, BOM, CJK/English chapter regex (`第.章/回/卷`, numbered), default single chapter for files without headings, block splitting, `ParseException` on invalid encoding.
- [ ] **Step 2: GREEN** TXT parser.
- [ ] **Step 3: GREEN** Markdown parser (headings→chapters, paragraphs/quotes/rules/images→blocks).
- [ ] **Step 4: Commit.**

### Task 4: Handwritten EPUB parser (secure, prose-first)

**Files:**
- Create: `format/epub/.../EpubModule.kt`, `SafeZip.kt`, `EpubXml.kt`, `OpfParser.kt`, `NcxNavParser.kt`, `XhtmlSanitizer.kt`, `ResourceResolver.kt`
- Test: `format/epub/.../EpubModuleTest.kt`, `SafeZipTest.kt`, `EpubXmlTest.kt`, `SanitizerTest.kt`

**Interfaces:**
- Produces: `EpubModule.parse(epubStream, bookId) -> List<Chapter>` honoring `container.xml -> OPF (spine+manifest+metadata/toc)`; `capabilities()` reports reflow/font/background/search/annotate/turn all true (prose).

- [ ] **Step 1: Failing security tests**: reject encrypted/DRM (encryption.xml or non-ZIP), reject ZIP slip path (`../`), reject excessive decompressed size, reject XXE (DOCTYPE/entity).
- [ ] **Step 2: Failing parse tests**: minimal valid EPUB fixture → expected chapters ordered by spine; NAV/NCX toc maps chapter titles; XHTML sanitizer strips script/style/link/remote URL, keeps paragraph/heading/em/strong, image only if packaged.
- [ ] **Step 3: Implement SafeZip (per-entry limits + canonical containment), EpubXml (DTD/XXE disabled), parsers, sanitizer, resource resolver (image size cap + downsample later in reader).**
- [ ] **Step 4: GREEN.**

### Task 5: SAF import pipeline

**Files:**
- Create: `feature/importer/.../ImportContract.kt`, `SourceManager.kt`, `ImportService.kt`, `ImportWorker.kt`, `CleanupHelper.kt`
- Test: `feature/importer/.../ImportServiceTest.kt`, `SourceManagerTest.kt`

**Interfaces:**
- Produces: `ImportService.import(uri: Uri): Flow<ImportProgress>`, `SourceManager.openPersistable(uri)`, `cleanupOnFailure(bookId)`, duplicate detection by SHA-256.

- [ ] **Step 1: Failing tests**: hash/dedupe, persistable-URI resolution, private-copy fallback on non-persistable, cleanup on parse failure, size limit.
- [ ] **Step 2: GREEN.**

### Task 6: Library screen (Compose)

**Files:**
- Create: `feature/library/.../LibraryScreen.kt`, `BookCard.kt`, `LibraryViewModel.kt`, `ImportTab.kt`, `FilterSort.kt`, `SearchBar.kt`
- Create: `app/src/debug/.../PreloadSample.kt` + assets for 1–2 small preinstalled Arknights-markdown files (non-official label)
- Modify: `app` to make Library the debug launcher; keep CurlLabActivity as separate entry
- Test: `feature/library/.../LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: Room DAOs + ImportService + FormatModules.
- Produces: `LibraryViewModel` exposing `books: StateFlow<List<BookUi>>`, `openImport()`, `search(q)`, `filter(cat)`, `remove(book)`.

- [ ] **Step 1: Failing ViewModel tests** (load list, filter recent/fav/all, search title/author, remove).
- [ ] **Step 2: GREEN.**
- [ ] **Step 3: Wire ImportTab via ACTION_OPEN_DOCUMENT + progress; bookshelf shows preinstalled (non-official tag) + imported books with progress/cover/capability labels.**
- [ ] **Step 4: `:app:assembleDebug` builds; manual smoke via README-run instructions.**

---

## Verification (Volume 1 gate)

- `./gradlew :data:model:test :format:api:test :format:text:test :format:epub:test :data:database:testDebugUnitTest :feature:importer:test :feature:library:test :app:assembleDebug`
- All pure-JVM modules (data/model, format/*) have zero Android imports.
- Import a TXT, a Markdown file, and a small EPUB in the running app; each appears in the library with capability labels.
- A deliberately malicious ZIP (path traversal / XXE / oversized) is rejected with a clear user message, not a crash.
- Preinstalled content shows the non-official tag.
