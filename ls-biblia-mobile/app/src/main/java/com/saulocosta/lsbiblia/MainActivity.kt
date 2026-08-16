package com.saulocosta.lsbiblia

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val catalogClient = JwCatalogClient()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var currentBook: BookDetail? = null
    private lateinit var bookContent: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        showBookScreen()
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Handled explicitly until navigation is introduced")
    override fun onBackPressed() {
        if (currentBook != null) showBookScreen() else super.onBackPressed()
    }

    private fun showBookScreen() {
        currentBook = null
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
                addView(label("Estudo em língua de sinais", 12f, MUTED))
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
        currentBook = detail
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
            Toast.makeText(this@MainActivity, "Capítulo ${chapter.track} selecionado", Toast.LENGTH_SHORT).show()
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

    private fun baseRoot(): LinearLayout = column().apply {
        setBackgroundColor(BG)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
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
