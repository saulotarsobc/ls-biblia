package com.saulocosta.lsbiblia

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

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
    private lateinit var bookContent: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        showBookScreen()
    }

    override fun onDestroy() {
        downloadToken++
        activeDownloader?.cancel()
        releasePlayer()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Handled explicitly until navigation is introduced")
    override fun onBackPressed() {
        if (activeDownloader != null) {
            cancelDownloadAndReturn()
            return
        }
        when (currentStep) {
            4 -> currentChapter?.let(::showQualityScreen) ?: showBookScreen()
            3 -> currentChapter?.let(::showVerseScreen) ?: showBookScreen()
            2 -> currentBook?.let(::showChapterScreen) ?: showBookScreen()
            1 -> showBookScreen()
            else -> super.onBackPressed()
        }
    }

    private fun showBookScreen() {
        releasePlayer()
        currentStep = 0
        currentBook = null
        currentChapter = null
        selectedVerses.clear()
        selectedQuality = null
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
            setPadding(dp(18), dp(15), dp(18), dp(14))
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
        header.addView(brandRow)

        val steps = row().apply { setPadding(0, dp(15), dp(8), 0) }
        listOf("Livro", "Capítulo", "Versículos", "Qualidade", "Editor").forEachIndexed { index, name ->
            val active = index == activeStep
            val step = label(
                "${index + 1}  $name",
                12f,
                if (active) Color.WHITE else MUTED,
                if (active) Typeface.BOLD else Typeface.NORMAL,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = rounded(if (active) ACTIVE_PANEL else PANEL, if (active) ACCENT else LINE, 99)
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
            steps.addView(step, wrapWrap().apply { if (index > 0) leftMargin = dp(7) })
        }
        header.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(steps, wrapWrap())
            },
            matchWrap(),
        )
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
                    "Marque os versículos que entrarão no estudo. Sem seleção, o capítulo inteiro será usado."
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

    private fun showEditorScreen(book: BookDetail, chapter: Chapter, video: VideoFile, videoFile: File) {
        releasePlayer()
        currentStep = 4
        currentBook = book
        currentChapter = chapter

        val root = baseRoot().apply { addView(createHeader(activeStep = 4), matchWrap()) }
        val content = column().apply { setPadding(dp(18), dp(18), dp(18), dp(28)) }
        content.addView(label("PASSO 5 DE 5", 11f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.12f })
        content.addView(
            label("Editor", 27f, TEXT, Typeface.BOLD),
            matchWrap().apply { topMargin = dp(5) },
        )
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
                bottomMargin = dp(14)
            },
        )

        val playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerShowTimeoutMs = 3_000
            controllerAutoShow = true
        }
        content.addView(
            playerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)),
        )

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer
        val chosenVerses = chapter.verses.filter { it.number in selectedVerses }.sortedBy { it.number }
        val mediaItems = if (chosenVerses.isEmpty()) {
            listOf(MediaItem.fromUri(Uri.fromFile(videoFile)))
        } else {
            chosenVerses.map { verse ->
                MediaItem.Builder()
                    .setMediaId("verse-${verse.number}")
                    .setUri(Uri.fromFile(videoFile))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((verse.start * 1_000).toLong())
                            .setEndPositionMs((verse.end * 1_000).toLong())
                            .build(),
                    )
                    .build()
            }
        }
        exoPlayer.setMediaItems(mediaItems)
        exoPlayer.prepare()

        val previewDuration = if (chosenVerses.isEmpty()) chapter.duration
            else chosenVerses.sumOf { (it.end - it.start).coerceAtLeast(0.0) }
        content.addView(
            column().apply {
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = rounded(PANEL, LINE, 12)
                addView(label("Trecho do estudo", 14f, TEXT, Typeface.BOLD))
                addView(
                    label("$selectionLabel • ${formatDuration(previewDuration)}", 12f, MUTED),
                    matchWrap().apply { topMargin = dp(3) },
                )
            },
            matchWrap().apply {
                topMargin = dp(14)
                bottomMargin = dp(16)
            },
        )

        content.addView(label("VELOCIDADE DA PRÉVIA", 11f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f })
        val speedButtons = linkedMapOf<Float, TextView>()
        val speedRow = row()
        lateinit var updateSpeed: (Float) -> Unit
        listOf(0.5f, 0.75f, 1f).forEachIndexed { index, speed ->
            val button = action(if (speed == 1f) "Normal" else "${speed}×").apply {
                setOnClickListener { updateSpeed(speed) }
            }
            speedButtons[speed] = button
            speedRow.addView(
                button,
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (index > 0) leftMargin = dp(8) },
            )
        }
        updateSpeed = { speed ->
            exoPlayer.playbackParameters = PlaybackParameters(speed)
            speedButtons.forEach { (value, button) ->
                val selected = value == speed
                button.background = rounded(if (selected) ACTIVE_PANEL else PANEL_2, if (selected) ACCENT else LINE, 10)
                button.setTextColor(if (selected) Color.WHITE else TEXT)
            }
        }
        updateSpeed(1f)
        content.addView(speedRow, matchWrap().apply {
            topMargin = dp(10)
            bottomMargin = dp(16)
        })

        content.addView(
            label(
                "A prévia já respeita os versículos selecionados. No próximo marco entram câmera lenta e zoom por trechos, linha do tempo e exportação.",
                13f,
                MUTED,
            ).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(PANEL, LINE, 10)
            },
            matchWrap(),
        )
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content, matchMatch())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
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
        private val LINE = Color.rgb(42, 47, 58)
        private val TEXT = Color.rgb(230, 233, 239)
        private val MUTED = Color.rgb(147, 155, 171)
        private val ACCENT = Color.rgb(79, 140, 255)
        private val DANGER = Color.rgb(239, 95, 107)

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
