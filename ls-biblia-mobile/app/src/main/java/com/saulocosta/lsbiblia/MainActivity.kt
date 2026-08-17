package com.saulocosta.lsbiblia

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {
    private val catalogClient = JwCatalogClient()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var currentBook: BookDetail? = null
    private var currentChapter: Chapter? = null
    private val selectedVerses = linkedSetOf<Int>()
    private var selectedQuality: String? = null
    private var currentStep = 0
    private var activeDownloader: VideoDownloader? = null
    private var downloadToken = 0
    private var downloadError: String? = null
    private var player: ExoPlayer? = null
    private var editorVideo: VideoFile? = null
    private var editorVideoFile: File? = null
    private var editorSelectionKey: String? = null
    private var editState: EditState? = null
    private var selectedSpeedRegionId: Long? = null
    private var selectedZoomRegionId: Long? = null
    private var zoomGestureEditing = false
    private var activeExporter: VideoExporter? = null
    private var exportToken = 0
    private var showingCache = false
    private var cacheReturnStep = 0
    private val previewHandler = Handler(Looper.getMainLooper())
    private val cacheManager by lazy { CacheManager(this) }
    private lateinit var bookContent: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBackNavigation()
            },
        )
        showBookScreen()
    }

    override fun onDestroy() {
        downloadToken++
        activeDownloader?.cancel()
        exportToken++
        activeExporter?.cancel()
        activeExporter = null
        releasePlayer()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        if (showingCache) {
            closeCacheScreen()
            return
        }
        if (activeExporter != null) {
            cancelExportAndReturn()
            return
        }
        if (activeDownloader != null) {
            cancelDownloadAndReturn()
            return
        }
        when (currentStep) {
            4 -> currentChapter?.let(::showQualityScreen) ?: showBookScreen()
            3 -> currentChapter?.let(::showVerseScreen) ?: showBookScreen()
            2 -> currentBook?.let(::showChapterScreen) ?: showBookScreen()
            1 -> showBookScreen()
            else -> finish()
        }
    }

    private fun showBookScreen() {
        releasePlayer()
        currentStep = 0
        currentBook = null
        currentChapter = null
        selectedVerses.clear()
        selectedQuality = null
        editorVideo = null
        editorVideoFile = null
        editorSelectionKey = null
        editState = null
        setContentView(createBookScreen())
    }

    private fun createBookScreen(): View {
        val root = baseRoot()
        root.addView(createHeader(activeStep = 0), matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(34)) }

        content.addView(label("COMECE POR AQUI", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(
            label("Escolha o livro", 27f, TEXT, Typeface.BOLD),
            matchWrap().apply { topMargin = dp(6) },
        )
        content.addView(
            label("Bíblia em Língua de Sinais Brasileira (LSB)", 14f, MUTED),
            matchWrap().apply {
                topMargin = dp(5)
                bottomMargin = dp(20)
            },
        )

        val search = EditText(this).apply {
            isSingleLine = true
            hint = "Buscar livro, por nome ou número"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 15f
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(PANEL_2, LINE, 12)
        }
        content.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(10)
            },
        )

        bookContent = column()
        content.addView(bookContent, matchWrap())
        renderBooks("")
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    renderBooks(value?.toString().orEmpty())
                }
                override fun afterTextChanged(value: Editable?) = Unit
            },
        )

        scroll.addView(content, matchMatch())
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun createHeader(activeStep: Int): View {
        val header = column().apply {
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(PANEL)
        }
        val brandRow = row()
        brandRow.addView(
            label("LS", 15f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(ACCENT, ACCENT, 10)
            },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
        brandRow.addView(
            column().apply {
                addView(label("LS Bíblia", 18f, TEXT, Typeface.BOLD))
                addView(label("Bíblia em língua de sinais", 12f, MUTED))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(11)
            },
        )
        brandRow.addView(
            action("Cache").apply {
                textSize = 11f
                setPadding(dp(11), dp(8), dp(11), dp(8))
                setOnClickListener { if (!showingCache) showCacheScreen() }
            },
            wrapWrap(),
        )
        header.addView(brandRow)

        val steps = row().apply { setPadding(0, dp(11), 0, 0) }
        listOf("Livro", "Capítulo", "Versículos", "Qualidade", "Editor").forEachIndexed { index, name ->
            val active = index == activeStep
            val step = label(
                "${index + 1}\n$name",
                10f,
                if (active) Color.WHITE else MUTED,
                if (active) Typeface.BOLD else Typeface.NORMAL,
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(1), dp(6), dp(1), dp(6))
                background = rounded(if (active) ACTIVE_PANEL else PANEL, if (active) ACCENT else LINE, 10)
                if (index == 0 && activeStep > 0) {
                    setTextColor(TEXT)
                    isClickable = true
                    setOnClickListener { showBookScreen() }
                } else if (index == 1 && activeStep > 1 && currentBook != null) {
                    setTextColor(TEXT)
                    isClickable = true
                    setOnClickListener { currentBook?.let(::showChapterScreen) }
                } else if (index == 2 && activeStep > 2 && currentChapter != null) {
                    setTextColor(TEXT)
                    isClickable = true
                    setOnClickListener { currentChapter?.let(::showVerseScreen) }
                } else if (index == 3 && activeStep > 3 && currentChapter != null) {
                    setTextColor(TEXT)
                    isClickable = true
                    setOnClickListener { currentChapter?.let(::showQualityScreen) }
                }
            }
            steps.addView(
                step,
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) leftMargin = dp(4) },
            )
        }
        header.addView(steps, matchWrap())
        return header
    }

    private fun renderBooks(query: String) {
        bookContent.removeAllViews()
        val normalizedQuery = query.normalized()
        val matches = BOOK_NAMES.indices.filter { index ->
            normalizedQuery.isEmpty() ||
                BOOK_NAMES[index].normalized().contains(normalizedQuery) ||
                (index + 1).toString() == normalizedQuery
        }
        val hebrew = matches.filter { it < 39 }
        val greek = matches.filter { it >= 39 }
        if (matches.isEmpty()) {
            bookContent.addView(
                label("Nenhum livro encontrado para “${query.trim()}”.", 14f, MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(38), dp(20), dp(38))
                },
                matchWrap(),
            )
            return
        }
        addBookGroup("ESCRITURAS HEBRAICAS", hebrew)
        addBookGroup("ESCRITURAS GREGAS CRISTÃS", greek)
    }

    private fun addBookGroup(title: String, books: List<Int>) {
        if (books.isEmpty()) return
        bookContent.addView(
            label(title, 11f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f },
            matchWrap().apply {
                topMargin = dp(20)
                bottomMargin = dp(9)
            },
        )
        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        books.forEachIndexed { position, bookIndex ->
            grid.addView(createBookCard(bookIndex), bookGridParams(position))
        }
        bookContent.addView(grid, matchWrap())
    }

    private fun createBookCard(index: Int): View = column().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(PANEL, LINE, 12)
        isClickable = true
        isFocusable = true
        addView(label(String.format(Locale.ROOT, "%02d", index + 1), 11f, MUTED, Typeface.BOLD))
        addView(
            label(BOOK_NAMES[index], 15f, TEXT, Typeface.BOLD).apply { maxLines = 2 },
            matchWrap().apply { topMargin = dp(2) },
        )
        setOnClickListener { loadBook(index) }
    }

    private fun loadBook(index: Int, force: Boolean = false) {
        val bookName = BOOK_NAMES[index]
        currentStep = 1
        setContentView(createLoadingScreen(bookName))
        networkExecutor.execute {
            runCatching { catalogClient.getBook(this, index + 1, bookName, force) }
                .onSuccess { detail -> runOnUiThread { showChapterScreen(detail) } }
                .onFailure { error ->
                    val message = (error as? JwCatalogClient.CatalogException)?.message
                        ?: "Não foi possível consultar o catálogo."
                    runOnUiThread { showCatalogError(index, message) }
                }
        }
    }

    private fun createLoadingScreen(bookName: String): View = baseRoot().apply {
        addView(createHeader(activeStep = 1), matchWrap())
        addView(
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                addView(
                    ProgressBar(this@MainActivity).apply {
                        indeterminateTintList = ColorStateList.valueOf(ACCENT)
                    },
                    LinearLayout.LayoutParams(dp(44), dp(44)),
                )
                addView(
                    label("Consultando $bookName", 20f, TEXT, Typeface.BOLD).apply { gravity = Gravity.CENTER },
                    wrapWrap().apply { topMargin = dp(20) },
                )
                addView(
                    label("Buscando os capítulos disponíveis em LSB…", 14f, MUTED).apply {
                        gravity = Gravity.CENTER
                    },
                    wrapWrap().apply { topMargin = dp(7) },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    private fun showChapterScreen(detail: BookDetail) {
        releasePlayer()
        currentStep = 1
        currentBook = detail
        currentChapter = null
        selectedVerses.clear()
        selectedQuality = null
        val root = baseRoot().apply { addView(createHeader(activeStep = 1), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(34)) }
        content.addView(label("PASSO 2 DE 5", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(label(detail.name, 27f, TEXT, Typeface.BOLD), matchWrap().apply { topMargin = dp(6) })
        val chapterCount = if (detail.chapters.size == 1) "1 capítulo disponível"
            else "${detail.chapters.size} capítulos disponíveis"
        content.addView(
            label("$chapterCount em Língua de Sinais Brasileira.", 14f, MUTED),
            matchWrap().apply {
                topMargin = dp(5)
                bottomMargin = dp(18)
            },
        )

        content.addView(
            row().apply {
                addView(action("‹  Livros").apply { setOnClickListener { showBookScreen() } }, wrapWrap())
                addView(
                    action("Atualizar catálogo", primary = true).apply {
                        setOnClickListener { loadBook(detail.bookNumber - 1, force = true) }
                    },
                    wrapWrap().apply { leftMargin = dp(9) },
                )
            },
            matchWrap().apply { bottomMargin = dp(22) },
        )
        content.addView(
            label("ESCOLHA O CAPÍTULO", 11f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f },
            matchWrap().apply { bottomMargin = dp(10) },
        )

        val grid = GridLayout(this).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        detail.chapters.forEachIndexed { position, chapter ->
            grid.addView(createChapterCard(chapter), chapterGridParams(position))
        }
        content.addView(grid, matchWrap())
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun createChapterCard(chapter: Chapter): View = column().apply {
        gravity = Gravity.CENTER
        background = rounded(PANEL, LINE, 12)
        isClickable = true
        isFocusable = true
        addView(label(chapter.track.toString(), 19f, TEXT, Typeface.BOLD).apply { gravity = Gravity.CENTER })
        val info = if (chapter.verses.isEmpty()) formatDuration(chapter.duration) else "${chapter.verses.size} vers."
        addView(
            label(info, 10f, MUTED).apply { gravity = Gravity.CENTER },
            wrapWrap().apply { topMargin = dp(4) },
        )
        setOnClickListener {
            currentChapter = chapter
            selectedVerses.clear()
            selectedQuality = null
            showVerseScreen(chapter)
        }
    }

    private fun showVerseScreen(chapter: Chapter) {
        releasePlayer()
        val book = currentBook ?: return showBookScreen()
        currentStep = 2
        currentChapter = chapter

        val root = baseRoot().apply { addView(createHeader(activeStep = 2), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(28)) }
        content.addView(label("PASSO 3 DE 5", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(
            label("${book.name} ${chapter.track}", 27f, TEXT, Typeface.BOLD),
            matchWrap().apply { topMargin = dp(6) },
        )
        content.addView(
            label(
                if (chapter.verses.isEmpty()) {
                    "Este capítulo não possui marcadores e será usado por inteiro."
                } else {
                    "Marque os versículos. Sem seleção, o capítulo inteiro será usado."
                },
                14f,
                MUTED,
            ),
            matchWrap().apply {
                topMargin = dp(5)
                bottomMargin = dp(18)
            },
        )

        val back = action("‹  Capítulos").apply {
            setOnClickListener { currentBook?.let(::showChapterScreen) }
        }
        val selectAll = action("Selecionar todos")
        content.addView(
            row().apply {
                addView(back, wrapWrap())
                if (chapter.verses.isNotEmpty()) {
                    addView(selectAll, wrapWrap().apply { leftMargin = dp(9) })
                }
            },
            matchWrap().apply { bottomMargin = dp(18) },
        )

        val selectionSummary = column().apply {
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(ACTIVE_PANEL, ACCENT, 12)
        }
        val selectionTitle = label("", 15f, TEXT, Typeface.BOLD)
        val selectionDuration = label("", 12f, MUTED)
        selectionSummary.addView(selectionTitle)
        selectionSummary.addView(selectionDuration, matchWrap().apply { topMargin = dp(3) })
        content.addView(selectionSummary, matchWrap().apply { bottomMargin = dp(20) })

        val verseCards = linkedMapOf<Int, TextView>()
        lateinit var updateSelection: () -> Unit
        if (chapter.verses.isNotEmpty()) {
            content.addView(
                label("VERSÍCULOS", 11f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f },
                matchWrap().apply { bottomMargin = dp(10) },
            )
            val grid = GridLayout(this).apply {
                columnCount = 5
                alignmentMode = GridLayout.ALIGN_BOUNDS
            }
            chapter.verses.forEachIndexed { position, verse ->
                val card = label(verse.number.toString(), 17f, TEXT, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (!selectedVerses.add(verse.number)) selectedVerses.remove(verse.number)
                        updateSelection()
                    }
                }
                verseCards[verse.number] = card
                grid.addView(card, verseGridParams(position))
            }
            content.addView(grid, matchWrap())
        }

        updateSelection = {
            verseCards.forEach { (number, card) ->
                val selected = number in selectedVerses
                card.background = rounded(if (selected) ACTIVE_PANEL else PANEL, if (selected) ACCENT else LINE, 11)
                card.setTextColor(if (selected) Color.WHITE else TEXT)
            }
            if (selectedVerses.isEmpty()) {
                selectionTitle.text = "Capítulo inteiro"
                selectionDuration.text = "Duração aproximada: ${formatDuration(chapter.duration)}"
            } else {
                selectionTitle.text = if (selectedVerses.size == 1) "1 versículo selecionado"
                    else "${selectedVerses.size} versículos selecionados"
                val duration = chapter.verses
                    .filter { it.number in selectedVerses }
                    .sumOf { (it.end - it.start).coerceAtLeast(0.0) }
                selectionDuration.text = "Duração aproximada: ${formatDuration(duration)}"
            }
            selectAll.text = if (selectedVerses.size == chapter.verses.size) "Limpar seleção" else "Selecionar todos"
        }
        selectAll.setOnClickListener {
            if (selectedVerses.size == chapter.verses.size) {
                selectedVerses.clear()
            } else {
                selectedVerses.clear()
                selectedVerses.addAll(chapter.verses.map { it.number })
            }
            updateSelection()
        }
        updateSelection()

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            bottomAction("Continuar para qualidade") { showQualityScreen(chapter) },
            matchWrap(),
        )
        setContentView(root)
    }

    private fun showQualityScreen(chapter: Chapter) {
        releasePlayer()
        val book = currentBook ?: return showBookScreen()
        currentStep = 3
        currentChapter = chapter
        val orderedFiles = listOf("240p", "360p", "480p", "720p").mapNotNull(chapter.files::get)
        if (selectedQuality !in chapter.files) {
            selectedQuality = chapter.files["720p"]?.quality ?: orderedFiles.lastOrNull()?.quality
        }

        val root = baseRoot().apply { addView(createHeader(activeStep = 3), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(22), dp(18), dp(28)) }
        content.addView(label("PASSO 4 DE 5", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(
            label("Escolha a qualidade", 27f, TEXT, Typeface.BOLD),
            matchWrap().apply { topMargin = dp(6) },
        )
        content.addView(
            label("${book.name} ${chapter.track} • o capítulo é baixado uma única vez.", 14f, MUTED),
            matchWrap().apply {
                topMargin = dp(5)
                bottomMargin = dp(18)
            },
        )
        content.addView(
            action("‹  Versículos").apply { setOnClickListener { showVerseScreen(chapter) } },
            wrapWrap().apply { bottomMargin = dp(22) },
        )

        val qualityCards = linkedMapOf<String, LinearLayout>()
        lateinit var updateQuality: () -> Unit
        orderedFiles.forEach { file ->
            val card = row().apply {
                setPadding(dp(16), dp(15), dp(16), dp(15))
                isClickable = true
                isFocusable = true
                addView(
                    column().apply {
                        addView(label(file.quality, 21f, TEXT, Typeface.BOLD))
                        addView(
                            label("${file.width} × ${file.height}  •  ${formatFileSize(file.fileSize)}", 12f, MUTED),
                            matchWrap().apply { topMargin = dp(3) },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    label("✓", 18f, Color.WHITE, Typeface.BOLD).apply {
                        tag = "check"
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(dp(34), dp(34)),
                )
                setOnClickListener {
                    selectedQuality = file.quality
                    updateQuality()
                }
            }
            qualityCards[file.quality] = card
            content.addView(card, matchWrap().apply { bottomMargin = dp(10) })
        }

        downloadError?.let { message ->
            content.addView(
                label(message, 13f, DANGER).apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(DANGER_PANEL, DANGER, 10)
                },
                matchWrap().apply {
                    topMargin = dp(4)
                    bottomMargin = dp(10)
                },
            )
        }

        updateQuality = {
            qualityCards.forEach { (quality, card) ->
                val selected = quality == selectedQuality
                card.background = rounded(if (selected) ACTIVE_PANEL else PANEL, if (selected) ACCENT else LINE, 12)
                card.findViewWithTag<TextView>("check").apply {
                    visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    background = rounded(ACCENT, ACCENT, 99)
                }
            }
        }
        updateQuality()
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            bottomAction("Baixar e abrir o editor") { startDownload(book, chapter) },
            matchWrap(),
        )
        setContentView(root)
    }

    private fun startDownload(book: BookDetail, chapter: Chapter) {
        val video = selectedQuality?.let(chapter.files::get) ?: return
        downloadError = null
        val token = ++downloadToken
        val downloader = VideoDownloader()
        activeDownloader = downloader

        val root = baseRoot().apply { addView(createHeader(activeStep = 3), matchWrap()) }
        val center = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val title = label("Baixando ${book.name} ${chapter.track}", 22f, TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        val detail = label("Qualidade ${video.quality}", 14f, MUTED).apply { gravity = Gravity.CENTER }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
        }
        val progressText = label("Preparando…", 13f, MUTED).apply { gravity = Gravity.CENTER }
        val cancel = action("Cancelar download").apply { setOnClickListener { cancelDownloadAndReturn() } }

        center.addView(title, matchWrap())
        center.addView(detail, matchWrap().apply {
            topMargin = dp(7)
            bottomMargin = dp(24)
        })
        center.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        center.addView(progressText, matchWrap().apply {
            topMargin = dp(12)
            bottomMargin = dp(22)
        })
        center.addView(cancel, wrapWrap())
        root.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        networkExecutor.execute {
            try {
                val target = downloader.download(this, book.bookNumber, chapter, video) { state ->
                    runOnUiThread {
                        if (downloadToken != token) return@runOnUiThread
                        progress.progress = state.percent
                        progressText.text = "${formatFileSize(state.receivedBytes)} de ${formatFileSize(state.totalBytes)}  •  ${state.percent}%"
                    }
                }
                runOnUiThread {
                    if (downloadToken != token) return@runOnUiThread
                    activeDownloader = null
                    showEditorScreen(book, chapter, video, target)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (downloadToken != token) return@runOnUiThread
                    activeDownloader = null
                    if (error !is VideoDownloader.DownloadCancelledException) {
                        downloadError = error.message ?: "O download não pôde ser concluído."
                        showQualityScreen(chapter)
                    }
                }
            }
        }
    }

    private fun cancelDownloadAndReturn() {
        downloadToken++
        activeDownloader?.cancel()
        activeDownloader = null
        currentChapter?.let(::showQualityScreen) ?: showBookScreen()
    }

    private fun showEditorScreen(
        book: BookDetail,
        chapter: Chapter,
        video: VideoFile,
        videoFile: File,
        resumeEditTime: Double = 0.0,
        autoPlay: Boolean = false,
    ) {
        releasePlayer()
        currentStep = 4
        currentBook = book
        currentChapter = chapter

        val chosenVerses = chapter.verses.filter { it.number in selectedVerses }.sortedBy(Verse::number)
        val selectionKey = if (chosenVerses.isEmpty()) "chapter" else chosenVerses.joinToString(",") { it.number.toString() }
        val sourceChanged = editorVideoFile?.absolutePath != videoFile.absolutePath ||
            editorSelectionKey != selectionKey || editState == null
        editorVideo = video
        editorVideoFile = videoFile
        editorSelectionKey = selectionKey
        if (sourceChanged) {
            val ranges = if (chosenVerses.isEmpty()) {
                listOf(SourceRange(0.0, chapter.duration))
            } else {
                TimelineMath.versesToRanges(chosenVerses)
            }
            editState = EditState(ranges)
            selectedSpeedRegionId = null
            selectedZoomRegionId = null
            zoomGestureEditing = false
        }
        val edit = editState ?: return
        val ranges = TimelineMath.normalizeRanges(edit.ranges)
        val editDuration = TimelineMath.editDuration(ranges)
        val outputDuration = TimelineMath.outputDuration(TimelineMath.buildAtoms(ranges, edit.speedRegions))

        val root = baseRoot().apply { addView(createHeader(activeStep = 4), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(17), dp(18), dp(26)) }
        content.addView(label("PASSO 5 DE 5", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(label("Editor", 27f, TEXT, Typeface.BOLD), matchWrap().apply { topMargin = dp(5) })
        val selectedCount = selectedVerses.size
        val selectionLabel = when (selectedCount) {
            0 -> "capítulo inteiro"
            1 -> "1 versículo"
            else -> "$selectedCount versículos"
        }
        content.addView(
            label("${book.name} ${chapter.track}  •  $selectionLabel  •  ${video.quality}", 13f, MUTED),
            matchWrap().apply {
                topMargin = dp(3)
                bottomMargin = dp(12)
            },
        )

        val playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            clipChildren = true
        }
        val previewFrame = FrameLayout(this).apply {
            clipChildren = true
            background = rounded(Color.BLACK, LINE, 11)
            addView(
                playerView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        content.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer
        val mediaItems = ranges.mapIndexed { index, range ->
            MediaItem.Builder()
                .setMediaId("range-$index")
                .setUri(Uri.fromFile(videoFile))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((range.start * 1_000).toLong())
                        .setEndPositionMs((range.end * 1_000).toLong())
                        .build(),
                )
                .build()
        }
        exoPlayer.setMediaItems(mediaItems)
        seekEditor(exoPlayer, ranges, resumeEditTime.coerceIn(0.0, editDuration))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = autoPlay

        val zoomModeButton = action(if (zoomGestureEditing) "Concluir enquadramento" else "Enquadrar zoom").apply {
            textSize = 11f
            visibility = if (edit.zoomRegions.any { it.id == selectedZoomRegionId }) View.VISIBLE else View.GONE
        }
        val zoomGestureHint = label("Use dois dedos para o zoom e arraste para enquadrar", 11f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.argb(205, 15, 17, 21), LINE, 8)
            visibility = if (zoomGestureEditing) View.VISIBLE else View.GONE
        }
        previewFrame.addView(
            zoomModeButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(8)
            },
        )
        previewFrame.addView(
            zoomGestureHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) },
        )

        fun selectedZoomRegion(): ZoomRegion? = edit.zoomRegions.firstOrNull { it.id == selectedZoomRegionId }
        fun refreshZoomGestureUi() {
            val region = selectedZoomRegion()
            zoomModeButton.visibility = if (region == null) View.GONE else View.VISIBLE
            zoomGestureHint.visibility = if (region != null && zoomGestureEditing) View.VISIBLE else View.GONE
            zoomModeButton.text = if (zoomGestureEditing && region != null) {
                "Concluir • ${String.format(Locale.ROOT, "%.1f", region.zoom)}×"
            } else {
                "Enquadrar zoom"
            }
        }
        zoomModeButton.setOnClickListener {
            val region = selectedZoomRegion() ?: return@setOnClickListener
            if (zoomGestureEditing) {
                zoomGestureEditing = false
                redrawEditor(editorPosition(exoPlayer, ranges))
            } else {
                zoomGestureEditing = true
                seekEditor(exoPlayer, ranges, (region.start + region.end) / 2.0)
                refreshZoomGestureUi()
            }
        }

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val region = selectedZoomRegion() ?: return false
                    region.zoom = (region.zoom * detector.scaleFactor).coerceIn(1.05f, 3f)
                    val clamped = TimelineMath.clampCenter(ZoomValue(region.zoom, region.centerX, region.centerY))
                    region.centerX = clamped.centerX
                    region.centerY = clamped.centerY
                    applyPreviewZoom(
                        playerView,
                        TimelineMath.zoomAt(editorPosition(exoPlayer, ranges), edit.zoomRegions),
                    )
                    refreshZoomGestureUi()
                    return true
                }
            },
        )
        var lastDragX = 0f
        var lastDragY = 0f
        playerView.setOnTouchListener { view, event ->
            if (!zoomGestureEditing || selectedZoomRegion() == null) return@setOnTouchListener false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)
            val region = selectedZoomRegion() ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastDragX = event.x
                    lastDragY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val deltaX = event.x - lastDragX
                    val deltaY = event.y - lastDragY
                    lastDragX = event.x
                    lastDragY = event.y
                    val next = TimelineMath.clampCenter(
                        ZoomValue(
                            region.zoom,
                            region.centerX - deltaX / (view.width.coerceAtLeast(1) * region.zoom),
                            region.centerY - deltaY / (view.height.coerceAtLeast(1) * region.zoom),
                        ),
                    )
                    region.centerX = next.centerX
                    region.centerY = next.centerY
                    applyPreviewZoom(
                        playerView,
                        TimelineMath.zoomAt(editorPosition(exoPlayer, ranges), edit.zoomRegions),
                    )
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        lastDragX = event.getX(remainingIndex)
                        lastDragY = event.getY(remainingIndex)
                    }
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        refreshZoomGestureUi()

        val playPause = action("▶  Reproduzir").apply {
            contentDescription = "Reproduzir vídeo"
            setOnClickListener {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }
        }
        content.addView(
            playPause,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(9)
            },
        )

        val timeLabel = label("", 13f, TEXT, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        content.addView(timeLabel, matchWrap().apply {
            topMargin = dp(11)
            bottomMargin = dp(5)
        })

        content.addView(
            label(
                "Arraste numa pista vazia para criar. Arraste o meio da faixa para mover ou as bordas brancas para ajustar.",
                12f,
                MUTED,
            ),
            matchWrap().apply { bottomMargin = dp(5) },
        )

        val timeline = EditorTimelineView(this).apply {
            durationSeconds = editDuration
            speedRegions = edit.speedRegions.toList()
            zoomRegions = edit.zoomRegions.toList()
            selectedSpeedRegionId = this@MainActivity.selectedSpeedRegionId
            selectedZoomRegionId = this@MainActivity.selectedZoomRegionId
            var accumulated = 0.0
            cutPositions = ranges.dropLast(1).map { range ->
                accumulated += range.end - range.start
                accumulated
            }
            onSeek = { seekEditor(exoPlayer, ranges, it) }
            onSelectRegion = { lane, id ->
                if (lane == EditorTimelineView.Lane.SPEED) {
                    this@MainActivity.selectedSpeedRegionId = id
                    this@MainActivity.selectedZoomRegionId = null
                    zoomGestureEditing = false
                } else {
                    this@MainActivity.selectedZoomRegionId = id
                    this@MainActivity.selectedSpeedRegionId = null
                    zoomGestureEditing = false
                }
                refreshZoomGestureUi()
            }
            onCreateRegion = createRegion@{ lane, start, end ->
                val others = if (lane == EditorTimelineView.Lane.SPEED) {
                    edit.speedRegions.map { it.start to it.end }
                } else {
                    edit.zoomRegions.map { it.start to it.end }
                }
                if (!canPlaceRegion(start, end, others)) {
                    Toast.makeText(this@MainActivity, "Essa faixa sobrepõe outra região.", Toast.LENGTH_SHORT).show()
                    return@createRegion
                }
                if (lane == EditorTimelineView.Lane.SPEED) {
                    val region = SpeedRegion(System.nanoTime(), start, end)
                    edit.speedRegions += region
                    edit.speedRegions.sortBy(SpeedRegion::start)
                    this@MainActivity.selectedSpeedRegionId = region.id
                    this@MainActivity.selectedZoomRegionId = null
                    zoomGestureEditing = false
                    redrawEditor((start + end) / 2.0, autoPlay = false)
                } else {
                    val region = ZoomRegion(System.nanoTime(), start, end)
                    edit.zoomRegions += region
                    edit.zoomRegions.sortBy(ZoomRegion::start)
                    this@MainActivity.selectedZoomRegionId = region.id
                    this@MainActivity.selectedSpeedRegionId = null
                    zoomGestureEditing = true
                    redrawEditor((start + end) / 2.0, autoPlay = false)
                }
            }
            onUpdateRegion = updateRegion@{ lane, id, start, end ->
                if (lane == EditorTimelineView.Lane.SPEED) {
                    val region = edit.speedRegions.firstOrNull { it.id == id } ?: return@updateRegion false
                    val others = edit.speedRegions.filterNot { it.id == id }.map { it.start to it.end }
                    if (!canPlaceRegion(start, end, others)) return@updateRegion false
                    region.start = start
                    region.end = end
                    edit.speedRegions.sortBy(SpeedRegion::start)
                } else {
                    val region = edit.zoomRegions.firstOrNull { it.id == id } ?: return@updateRegion false
                    val others = edit.zoomRegions.filterNot { it.id == id }.map { it.start to it.end }
                    if (!canPlaceRegion(start, end, others)) return@updateRegion false
                    region.start = start
                    region.end = end
                    edit.zoomRegions.sortBy(ZoomRegion::start)
                }
                true
            }
            onInteractionFinished = {
                redrawEditor(editorPosition(exoPlayer, ranges), autoPlay = false)
            }
            contentDescription = "Linha do tempo do vídeo"
        }
        content.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)))

        content.addView(
            column().apply {
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = rounded(PANEL, LINE, 11)
                addView(label("Resultado", 13f, TEXT, Typeface.BOLD))
                addView(
                    label(
                        "${formatDurationPrecise(outputDuration)}  •  ${edit.speedRegions.size} câmera lenta  •  ${edit.zoomRegions.size} zoom",
                        12f,
                        MUTED,
                    ),
                    matchWrap().apply { topMargin = dp(3) },
                )
            },
            matchWrap().apply {
                topMargin = dp(9)
                bottomMargin = dp(14)
            },
        )

        if (edit.speedRegions.isNotEmpty()) {
            content.addView(label("CÂMERA LENTA", 11f, SLOW, Typeface.BOLD), matchWrap().apply { bottomMargin = dp(8) })
            edit.speedRegions.toList().forEach { region -> addSpeedRegionCard(content, region) }
        }
        if (edit.zoomRegions.isNotEmpty()) {
            content.addView(
                label("ZOOM  •  pinça para ampliar e arraste para enquadrar", 11f, ZOOM, Typeface.BOLD),
                matchWrap().apply {
                    topMargin = dp(7)
                    bottomMargin = dp(8)
                },
            )
            edit.zoomRegions.toList().forEach { region -> addZoomRegionCard(content, region) }
        }

        val updater = object : Runnable {
            override fun run() {
                if (player !== exoPlayer) return
                val current = editorPosition(exoPlayer, ranges).coerceIn(0.0, editDuration)
                timeline.positionSeconds = current
                val nextTimeText = "${formatDurationPrecise(current)}  /  ${formatDurationPrecise(editDuration)}"
                if (timeLabel.text.toString() != nextTimeText) timeLabel.text = nextTimeText
                val speed = TimelineMath.speedAt(current, edit.speedRegions)
                if (exoPlayer.playbackParameters.speed != speed) {
                    exoPlayer.playbackParameters = PlaybackParameters(speed)
                }
                val nextPlayText = if (exoPlayer.isPlaying) "❚❚  Pausar" else "▶  Reproduzir"
                if (playPause.text.toString() != nextPlayText) playPause.text = nextPlayText
                playPause.contentDescription = if (exoPlayer.isPlaying) "Pausar vídeo" else "Reproduzir vídeo"
                applyPreviewZoom(playerView, TimelineMath.zoomAt(current, edit.zoomRegions))
                if (exoPlayer.isPlaying) previewHandler.postDelayed(this, 50)
            }
        }
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    previewHandler.removeCallbacks(updater)
                    previewHandler.post(updater)
                }
            },
        )
        previewHandler.post(updater)

        content.addView(
            action("‹  Voltar à qualidade").apply {
                setOnClickListener { showQualityScreen(chapter) }
            },
            wrapWrap().apply { topMargin = dp(8) },
        )
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            bottomAction("Exportar vídeo") { startExport(book, chapter, video, videoFile, edit) },
            matchWrap(),
        )
        setContentView(root)
    }

    private fun overlaps(start: Double, end: Double, otherStart: Double, otherEnd: Double): Boolean =
        start < otherEnd - 0.0001 && end > otherStart + 0.0001

    private fun canPlaceRegion(start: Double, end: Double, others: List<Pair<Double, Double>>): Boolean =
        end - start >= 0.25 && others.none { overlaps(start, end, it.first, it.second) }

    private fun addSpeedRegionCard(parent: LinearLayout, region: SpeedRegion) {
        val edit = editState ?: return
        val card = column().apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(SLOW_PANEL, if (selectedSpeedRegionId == region.id) SLOW else LINE, 11)
            isClickable = true
            setOnClickListener {
                selectedSpeedRegionId = region.id
                selectedZoomRegionId = null
                zoomGestureEditing = false
                redrawEditor(editorPosition())
            }
        }
        card.addView(
            row().apply {
                addView(
                    label("${region.speed}×  •  ${formatDurationPrecise(region.start)} → ${formatDurationPrecise(region.end)}", 13f, TEXT, Typeface.BOLD),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(action("Apagar").apply {
                    textSize = 11f
                    setOnClickListener {
                        edit.speedRegions.removeAll { it.id == region.id }
                        selectedSpeedRegionId = null
                        redrawEditor(editorPosition())
                    }
                }, wrapWrap())
            },
            matchWrap(),
        )
        val speedRow = row()
        listOf(0.25f, 0.5f, 0.75f).forEachIndexed { index, speed ->
            speedRow.addView(
                action("${speed}×").apply {
                    if (region.speed == speed) {
                        background = rounded(ACTIVE_PANEL, SLOW, 9)
                        setTextColor(Color.WHITE)
                    }
                    setOnClickListener {
                        region.speed = speed
                        selectedSpeedRegionId = region.id
                        selectedZoomRegionId = null
                        zoomGestureEditing = false
                        redrawEditor(editorPosition())
                    }
                },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index > 0) leftMargin = dp(6) },
            )
        }
        card.addView(speedRow, matchWrap().apply { topMargin = dp(9) })
        parent.addView(card, matchWrap().apply { bottomMargin = dp(9) })
    }

    private fun addZoomRegionCard(parent: LinearLayout, region: ZoomRegion) {
        val edit = editState ?: return
        val card = column().apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(ZOOM_PANEL, if (selectedZoomRegionId == region.id) ZOOM else LINE, 11)
            isClickable = true
            setOnClickListener {
                selectedZoomRegionId = region.id
                selectedSpeedRegionId = null
                zoomGestureEditing = true
                redrawEditor((region.start + region.end) / 2.0, autoPlay = false)
            }
        }
        card.addView(
            row().apply {
                addView(
                    label("${String.format(Locale.ROOT, "%.1f", region.zoom)}×  •  ${formatDurationPrecise(region.start)} → ${formatDurationPrecise(region.end)}", 13f, TEXT, Typeface.BOLD),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(action("Apagar").apply {
                    textSize = 11f
                    setOnClickListener {
                        edit.zoomRegions.removeAll { it.id == region.id }
                        selectedZoomRegionId = null
                        zoomGestureEditing = false
                        redrawEditor(editorPosition())
                    }
                }, wrapWrap())
            },
            matchWrap(),
        )
        val zoomRow = row()
        listOf(1.5f, 1.8f, 2.2f).forEachIndexed { index, zoom ->
            zoomRow.addView(
                action("${zoom}×").apply {
                    if (region.zoom == zoom) {
                        background = rounded(ACTIVE_PANEL, ZOOM, 9)
                        setTextColor(Color.WHITE)
                    }
                    setOnClickListener {
                        region.zoom = zoom
                        selectedZoomRegionId = region.id
                        selectedSpeedRegionId = null
                        zoomGestureEditing = true
                        redrawEditor((region.start + region.end) / 2.0, autoPlay = false)
                    }
                },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index > 0) leftMargin = dp(6) },
            )
        }
        card.addView(zoomRow, matchWrap().apply { topMargin = dp(9) })
        card.addView(
            label("Foco: ${(region.centerX * 100).toInt()}% × ${(region.centerY * 100).toInt()}%", 11f, MUTED),
            matchWrap().apply { topMargin = dp(7) },
        )
        parent.addView(card, matchWrap().apply { bottomMargin = dp(9) })
    }

    private fun redrawEditor(position: Double, autoPlay: Boolean = player?.isPlaying == true) {
        val book = currentBook ?: return
        val chapter = currentChapter ?: return
        val video = editorVideo ?: return
        val file = editorVideoFile?.takeIf(File::isFile) ?: return showQualityScreen(chapter)
        showEditorScreen(book, chapter, video, file, position, autoPlay)
    }

    private fun editorPosition(
        exoPlayer: ExoPlayer? = player,
        ranges: List<SourceRange> = editState?.ranges.orEmpty(),
    ): Double {
        if (exoPlayer == null || ranges.isEmpty()) return 0.0
        val normalized = TimelineMath.normalizeRanges(ranges)
        val index = exoPlayer.currentMediaItemIndex.coerceIn(0, normalized.lastIndex)
        val before = normalized.take(index).sumOf { it.end - it.start }
        return before + (exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000.0)
    }

    private fun seekEditor(exoPlayer: ExoPlayer, ranges: List<SourceRange>, editTime: Double) {
        val normalized = TimelineMath.normalizeRanges(ranges)
        if (normalized.isEmpty()) return
        var remaining = editTime.coerceAtLeast(0.0)
        normalized.forEachIndexed { index, range ->
            val duration = range.end - range.start
            if (remaining < duration || index == normalized.lastIndex) {
                exoPlayer.seekTo(index, (remaining.coerceIn(0.0, duration) * 1_000).toLong())
                return
            }
            remaining -= duration
        }
    }

    private fun applyPreviewZoom(playerView: PlayerView, value: ZoomValue) {
        val surface = playerView.videoSurfaceView ?: return
        if (surface.width <= 0 || surface.height <= 0) return
        surface.pivotX = 0f
        surface.pivotY = 0f
        surface.scaleX = value.zoom
        surface.scaleY = value.zoom
        surface.translationX = (0.5f - value.centerX * value.zoom) * surface.width
        surface.translationY = (0.5f - value.centerY * value.zoom) * surface.height
    }

    private fun startExport(
        book: BookDetail,
        chapter: Chapter,
        video: VideoFile,
        videoFile: File,
        edit: EditState,
    ) {
        releasePlayer()
        val token = ++exportToken
        val root = baseRoot().apply { addView(createHeader(activeStep = 4), matchWrap()) }
        val center = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        val expected = TimelineMath.outputDuration(TimelineMath.buildAtoms(edit.ranges, edit.speedRegions))
        val title = label("Exportando ${book.name} ${chapter.track}", 22f, TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        val detail = label("Vídeo final • ${formatDurationPrecise(expected)}", 14f, MUTED).apply {
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
        }
        val progressText = label("Preparando…", 13f, MUTED).apply { gravity = Gravity.CENTER }
        center.addView(title, matchWrap())
        center.addView(detail, matchWrap().apply {
            topMargin = dp(7)
            bottomMargin = dp(24)
        })
        center.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        center.addView(progressText, matchWrap().apply {
            topMargin = dp(12)
            bottomMargin = dp(22)
        })
        center.addView(action("Cancelar exportação").apply { setOnClickListener { cancelExportAndReturn() } }, wrapWrap())
        root.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val exporter = VideoExporter(applicationContext)
        activeExporter = exporter
        val safeBook = book.name.replace(Regex("[^A-Za-zÀ-ÿ0-9 -]"), "").trim()
        val outputName = "LS Bíblia - $safeBook ${chapter.track} - ${video.quality}"
        runCatching {
            exporter.start(
                videoFile,
                edit,
                outputName,
                object : VideoExporter.Listener {
                    override fun onProgress(percent: Int) {
                        if (exportToken != token) return
                        progress.progress = percent
                        progressText.text = "Processando • $percent%"
                    }

                    override fun onCompleted(result: VideoExporter.Result) {
                        if (exportToken != token) return
                        activeExporter = null
                        showExportComplete(book, chapter, video, videoFile, result)
                    }

                    override fun onError(message: String) {
                        if (exportToken != token) return
                        activeExporter = null
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        showEditorScreen(book, chapter, video, videoFile)
                    }

                    override fun onCancelled() = Unit
                },
            )
        }.onFailure { error ->
            activeExporter = null
            Toast.makeText(this, error.message ?: "Não foi possível iniciar a exportação.", Toast.LENGTH_LONG).show()
            showEditorScreen(book, chapter, video, videoFile)
        }
    }

    private fun cancelExportAndReturn() {
        exportToken++
        val exporter = activeExporter
        activeExporter = null
        exporter?.cancel()
        redrawEditor(0.0)
    }

    private fun showExportComplete(
        book: BookDetail,
        chapter: Chapter,
        video: VideoFile,
        videoFile: File,
        result: VideoExporter.Result,
    ) {
        val root = baseRoot().apply { addView(createHeader(activeStep = 4), matchWrap()) }
        val center = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        center.addView(
            label("✓", 28f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(ZOOM, ZOOM, 99)
            },
            LinearLayout.LayoutParams(dp(58), dp(58)),
        )
        center.addView(
            label("Vídeo exportado", 24f, TEXT, Typeface.BOLD).apply { gravity = Gravity.CENTER },
            wrapWrap().apply { topMargin = dp(18) },
        )
        center.addView(
            label("Salvo em Filmes/LS Bíblia\n${result.displayName} • ${formatFileSize(result.bytes)}", 13f, MUTED).apply {
                gravity = Gravity.CENTER
            },
            wrapWrap().apply {
                topMargin = dp(8)
                bottomMargin = dp(22)
            },
        )
        center.addView(
            action("Assistir", primary = true).apply {
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(result.uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(intent) }.onFailure {
                        Toast.makeText(this@MainActivity, "Nenhum player disponível.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
        )
        center.addView(
            action("Compartilhar").apply {
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, result.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Compartilhar vídeo"))
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(9) },
        )
        center.addView(
            action("Voltar ao editor").apply {
                setOnClickListener { showEditorScreen(book, chapter, video, videoFile) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(9) },
        )
        center.addView(
            action("Criar outro vídeo").apply { setOnClickListener { showBookScreen() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(9) },
        )
        root.addView(center, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }


    private fun releasePlayer() {
        previewHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun showCacheScreen() {
        if (!showingCache) cacheReturnStep = currentStep
        showingCache = true
        releasePlayer()

        val root = baseRoot().apply { addView(createHeader(activeStep = cacheReturnStep), matchWrap()) }
        val loading = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(
                ProgressBar(this@MainActivity).apply {
                    indeterminateTintList = ColorStateList.valueOf(ACCENT)
                },
                LinearLayout.LayoutParams(dp(42), dp(42)),
            )
            addView(
                label("Lendo o cache…", 15f, MUTED).apply { gravity = Gravity.CENTER },
                wrapWrap().apply { topMargin = dp(15) },
            )
        }
        root.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        networkExecutor.execute {
            val result = runCatching(cacheManager::report)
            runOnUiThread {
                if (!showingCache) return@runOnUiThread
                result.onSuccess(::renderCacheReport).onFailure {
                    Toast.makeText(this, "Não foi possível ler o cache.", Toast.LENGTH_LONG).show()
                    closeCacheScreen()
                }
            }
        }
    }

    private fun renderCacheReport(report: CacheReport) {
        if (!showingCache) return
        val root = baseRoot().apply { addView(createHeader(activeStep = cacheReturnStep), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(20), dp(18), dp(32)) }
        content.addView(label("ARMAZENAMENTO", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(label("Gerenciar cache", 27f, TEXT, Typeface.BOLD), matchWrap().apply { topMargin = dp(6) })
        content.addView(
            label(
                "Catálogos expiram em 7 dias. Os vídeos ficam disponíveis até você apagá-los.",
                14f,
                MUTED,
            ),
            matchWrap().apply {
                topMargin = dp(5)
                bottomMargin = dp(17)
            },
        )
        content.addView(
            column().apply {
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = rounded(PANEL, LINE, 12)
                addView(label("${formatFileSize(report.totalBytes)} usados", 18f, TEXT, Typeface.BOLD))
                addView(
                    label(
                        "Vídeos: ${formatFileSize(report.videoBytes)}  •  Catálogos: ${formatFileSize(report.catalogBytes)}",
                        12f,
                        MUTED,
                    ),
                    matchWrap().apply { topMargin = dp(4) },
                )
            },
            matchWrap().apply { bottomMargin = dp(13) },
        )

        content.addView(
            row().apply {
                addView(action("‹  Voltar").apply { setOnClickListener { closeCacheScreen() } }, wrapWrap())
                addView(
                    action("Limpar tudo").apply {
                        setTextColor(DANGER)
                        setOnClickListener { confirmCacheClear(CacheKind.ALL, "todo o cache") }
                    },
                    wrapWrap().apply { leftMargin = dp(8) },
                )
            },
            matchWrap().apply { bottomMargin = dp(22) },
        )

        addCacheSection(content, "VÍDEOS", report.videos, report.videoBytes, CacheKind.VIDEOS)
        addCacheSection(content, "CATÁLOGOS", report.catalogs, report.catalogBytes, CacheKind.CATALOGS)

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun addCacheSection(
        parent: LinearLayout,
        title: String,
        entries: List<CacheEntry>,
        bytes: Long,
        kind: CacheKind,
    ) {
        val heading = row().apply {
            addView(
                label("$title  •  ${formatFileSize(bytes)}", 11f, MUTED, Typeface.BOLD).apply {
                    letterSpacing = 0.08f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (entries.isNotEmpty()) {
                addView(
                    action("Apagar seção").apply {
                        textSize = 11f
                        setOnClickListener {
                            confirmCacheClear(kind, if (kind == CacheKind.VIDEOS) "todos os vídeos" else "todos os catálogos")
                        }
                    },
                    wrapWrap(),
                )
            }
        }
        parent.addView(heading, matchWrap().apply {
            topMargin = dp(8)
            bottomMargin = dp(9)
        })
        if (entries.isEmpty()) {
            parent.addView(
                label("Nada armazenado nesta seção.", 13f, MUTED).apply {
                    setPadding(dp(14), dp(15), dp(14), dp(15))
                    background = rounded(PANEL, LINE, 10)
                },
                matchWrap().apply { bottomMargin = dp(18) },
            )
            return
        }
        entries.forEach { entry ->
            parent.addView(
                row().apply {
                    setPadding(dp(14), dp(12), dp(10), dp(12))
                    background = rounded(PANEL, if (entry.stale || entry.partial) DANGER else LINE, 10)
                    addView(
                        column().apply {
                            addView(label(entry.title, 14f, TEXT, Typeface.BOLD))
                            addView(
                                label("${entry.detail}  •  ${formatFileSize(entry.bytes)}", 11f,
                                    if (entry.stale || entry.partial) DANGER else MUTED),
                                matchWrap().apply { topMargin = dp(3) },
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        action("Apagar").apply {
                            textSize = 11f
                            setOnClickListener { confirmCacheEntryRemoval(entry) }
                        },
                        wrapWrap().apply { leftMargin = dp(8) },
                    )
                },
                matchWrap().apply { bottomMargin = dp(8) },
            )
        }
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
    }

    private fun confirmCacheClear(kind: CacheKind, description: String) {
        AlertDialog.Builder(this)
            .setTitle("Apagar $description?")
            .setMessage("Este conteúdo pode ser baixado novamente quando necessário.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ -> runCacheRemoval { cacheManager.clear(kind) } }
            .show()
    }

    private fun confirmCacheEntryRemoval(entry: CacheEntry) {
        AlertDialog.Builder(this)
            .setTitle("Apagar ${entry.title}?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ -> runCacheRemoval { cacheManager.remove(entry) } }
            .show()
    }

    private fun runCacheRemoval(remove: () -> CacheRemoval) {
        networkExecutor.execute {
            val result = runCatching(remove)
            runOnUiThread {
                if (!showingCache) return@runOnUiThread
                result.onSuccess { removal ->
                    val activeFile = editorVideoFile
                    if (activeFile != null && !activeFile.exists()) editorVideoFile = null
                    val message = if (removal.errors.isEmpty()) {
                        "${removal.removed} item(ns) apagado(s) • ${formatFileSize(removal.freedBytes)} liberados"
                    } else {
                        removal.errors.first()
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    showCacheScreen()
                }.onFailure {
                    Toast.makeText(this, "Não foi possível limpar o cache.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun closeCacheScreen() {
        showingCache = false
        when (cacheReturnStep) {
            4 -> {
                val book = currentBook
                val chapter = currentChapter
                val video = editorVideo
                val file = editorVideoFile
                if (book != null && chapter != null && video != null && file?.isFile == true) {
                    showEditorScreen(book, chapter, video, file)
                } else {
                    currentChapter?.let(::showQualityScreen) ?: showBookScreen()
                }
            }
            3 -> currentChapter?.let(::showQualityScreen) ?: showBookScreen()
            2 -> currentChapter?.let(::showVerseScreen) ?: showBookScreen()
            1 -> currentBook?.let(::showChapterScreen) ?: showBookScreen()
            else -> showBookScreen()
        }
    }

    private fun showCatalogError(index: Int, message: String) {
        currentBook = null
        val root = baseRoot().apply { addView(createHeader(activeStep = 1), matchWrap()) }
        root.addView(
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                addView(
                    label("!", 26f, DANGER, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        background = rounded(DANGER_PANEL, DANGER, 99)
                    },
                    LinearLayout.LayoutParams(dp(52), dp(52)),
                )
                addView(
                    label("Não foi possível abrir ${BOOK_NAMES[index]}", 20f, TEXT, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                    },
                    wrapWrap().apply { topMargin = dp(18) },
                )
                addView(
                    label(message, 14f, MUTED).apply { gravity = Gravity.CENTER },
                    wrapWrap().apply {
                        topMargin = dp(8)
                        bottomMargin = dp(22)
                    },
                )
                addView(
                    row().apply {
                        addView(
                            action("Escolher outro livro", primary = true).apply {
                                setOnClickListener { showBookScreen() }
                            },
                            wrapWrap(),
                        )
                        addView(
                            action("Tentar novamente").apply { setOnClickListener { loadBook(index, force = true) } },
                            wrapWrap().apply { leftMargin = dp(9) },
                        )
                    },
                    wrapWrap(),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun action(text: String, primary: Boolean = false): TextView =
        label(text, 13f, if (primary) Color.WHITE else TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(if (primary) ACCENT else PANEL_2, if (primary) ACCENT else LINE, 10)
            isClickable = true
            isFocusable = true
        }

    private fun bottomAction(text: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(PANEL)
            addView(
                action(text, primary = true).apply { setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
            )
        }

    private fun baseRoot(): LinearLayout = column().apply {
        setBackgroundColor(BG)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    ViewCompat.requestApplyInsets(view)
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            },
        )
    }

    private fun bookGridParams(position: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(GridLayout.spec(position / 2), GridLayout.spec(position % 2, 1f)).apply {
            width = 0
            height = dp(70)
            setGravity(Gravity.FILL)
            val gap = dp(4)
            setMargins(if (position % 2 == 0) 0 else gap, if (position < 2) 0 else gap,
                if (position % 2 == 0) gap else 0, gap)
        }

    private fun chapterGridParams(position: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(GridLayout.spec(position / 4), GridLayout.spec(position % 4, 1f)).apply {
            width = 0
            height = dp(78)
            setGravity(Gravity.FILL)
            val gap = dp(3)
            setMargins(if (position % 4 == 0) 0 else gap, if (position < 4) 0 else gap,
                if (position % 4 == 3) 0 else gap, gap)
        }

    private fun verseGridParams(position: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(GridLayout.spec(position / 5), GridLayout.spec(position % 5, 1f)).apply {
            width = 0
            height = dp(62)
            setGravity(Gravity.FILL)
            val gap = dp(3)
            setMargins(if (position % 5 == 0) 0 else gap, if (position < 5) 0 else gap,
                if (position % 5 == 4) 0 else gap, gap)
        }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
        includeFontPadding = false
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun matchMatch() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun formatDuration(seconds: Double): String {
        val rounded = seconds.toInt()
        return String.format(Locale.ROOT, "%d:%02d", rounded / 60, rounded % 60)
    }

    private fun formatDurationPrecise(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        return String.format(Locale.ROOT, "%d:%04.1f", (safe / 60).toInt(), safe % 60)
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    }

    private fun String.normalized(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
        .lowercase(Locale.ROOT)
        .trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(15, 17, 21)
        private val PANEL = Color.rgb(23, 26, 33)
        private val PANEL_2 = Color.rgb(30, 34, 43)
        private val ACTIVE_PANEL = Color.rgb(32, 50, 80)
        private val DANGER_PANEL = Color.rgb(58, 30, 36)
        private val SLOW_PANEL = Color.rgb(55, 42, 29)
        private val ZOOM_PANEL = Color.rgb(23, 54, 49)
        private val LINE = Color.rgb(42, 47, 58)
        private val TEXT = Color.rgb(230, 233, 239)
        private val MUTED = Color.rgb(147, 155, 171)
        private val ACCENT = Color.rgb(79, 140, 255)
        private val DANGER = Color.rgb(239, 95, 107)
        private val SLOW = Color.rgb(240, 161, 58)
        private val ZOOM = Color.rgb(35, 200, 160)

        private val BOOK_NAMES = listOf(
            "Gênesis", "Êxodo", "Levítico", "Números", "Deuteronômio", "Josué", "Juízes", "Rute",
            "1 Samuel", "2 Samuel", "1 Reis", "2 Reis", "1 Crônicas", "2 Crônicas", "Esdras", "Neemias",
            "Ester", "Jó", "Salmos", "Provérbios", "Eclesiastes", "Cântico de Salomão", "Isaías", "Jeremias",
            "Lamentações", "Ezequiel", "Daniel", "Oseias", "Joel", "Amós", "Obadias", "Jonas", "Miqueias",
            "Naum", "Habacuque", "Sofonias", "Ageu", "Zacarias", "Malaquias", "Mateus", "Marcos", "Lucas",
            "João", "Atos", "Romanos", "1 Coríntios", "2 Coríntios", "Gálatas", "Efésios", "Filipenses",
            "Colossenses", "1 Tessalonicenses", "2 Tessalonicenses", "1 Timóteo", "2 Timóteo", "Tito", "Filêmon",
            "Hebreus", "Tiago", "1 Pedro", "2 Pedro", "1 João", "2 João", "3 João", "Judas", "Apocalipse",
        )
    }
}
