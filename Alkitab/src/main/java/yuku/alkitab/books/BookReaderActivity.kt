package yuku.alkitab.books

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject
import yuku.afw.storage.Preferences
import yuku.alkitab.base.App
import yuku.alkitab.base.IsiActivity
import yuku.alkitab.base.S
import yuku.alkitab.base.ac.base.BaseLeftDrawerActivity
import yuku.alkitab.base.storage.Prefkey
import yuku.alkitab.base.util.FontManager
import yuku.alkitab.base.util.RequestCodes
import yuku.alkitab.base.widget.LeftDrawer
import yuku.alkitab.base.widget.TextAppearancePanel
import yuku.alkitab.debug.R

/**
 * The book reader, a top-level screen with the left drawer like the Bible reader. It shows the
 * book it was opened with, or else the last one read. The drawer's Books section links to the
 * book and to Choose book, which picks another from [BooksActivity]; the menu has Contents and
 * Find in book, which turns the toolbar into a browser-style find bar. The WebView only sees files served by [shouldInterceptRequest] under
 * [ORIGIN]: the reader page and its scripts, the current book, and the user's custom font.
 * Everything else is refused, and web links open in the browser.
 */
class BookReaderActivity : BaseLeftDrawerActivity(), LeftDrawer.Books.Listener {
    private var slug: String? = null
    private var bookTitle: String = ""
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var leftDrawer: LeftDrawer.Books
    private lateinit var holder: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var tMessage: TextView
    private var webView: WebView? = null
    private var pageReady = false
    private var outlineOpen = false
    private var textAppearancePanel: TextAppearancePanel? = null
    private var findBar: View? = null
    /** [readerStamp] when the page was loaded, to notice a sync that replaced the reader files. */
    private var loadedReaderStamp = 0L
    private lateinit var tFindQuery: EditText
    private lateinit var tFindCount: TextView
    private var findCount = 0
    private val findHandler = Handler(Looper.getMainLooper())
    private var findPending = false
    private val findRunnable = Runnable {
        findPending = false
        runFind("SpiritualEmbed.find(${JSONObject.quote(tFindQuery.text.toString())})")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_reader)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu_white_24dp)

        drawerLayout = findViewById(R.id.drawerLayout)
        leftDrawer = findViewById(R.id.left_drawer)
        leftDrawer.configure(this, drawerLayout)

        holder = findViewById(R.id.webViewHolder)
        overlayContainer = findViewById(R.id.overlayContainer)
        tMessage = findViewById(R.id.tMessage)

        val wanted = intent.getStringExtra(EXTRA_slug) ?: Preferences.getString(PREF_LAST_SLUG, null)
        val wantedTitle = intent.getStringExtra(EXTRA_title) ?: Preferences.getString(PREF_LAST_TITLE, null)
        val action = intent.getStringExtra(EXTRA_action)
        if (wanted != null && BooksRepository.bookFile(wanted).exists()) {
            showBook(wanted, wantedTitle ?: wanted)
            if (action != null) runAction(action)
        } else {
            title = getString(R.string.books_menu)
            chooseBook()
        }
    }

    /** [ACTION_choose], from the drawer. */
    fun runAction(action: String) {
        if (action == ACTION_choose) chooseBook()
    }

    override fun getLeftDrawer() = leftDrawer

    override fun onResume() {
        super.onResume()
        if (pageReady) applyAppearance()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun chooseBook() {
        stopFind()
        @Suppress("DEPRECATION")
        startActivityForResult(BooksActivity.createIntent(), REQCODE_chooseBook)
    }

    /** Shows [newSlug], reusing the page when it is already loaded. */
    private fun showBook(newSlug: String, newTitle: String) {
        stopFind()
        slug = newSlug
        bookTitle = newTitle
        title = newTitle
        Preferences.setString(PREF_LAST_SLUG, newSlug)
        Preferences.setString(PREF_LAST_TITLE, newTitle)
        tMessage.isVisible = false

        if (webView == null) {
            createWebView()
        } else if (pageReady) {
            openBook()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        pageReady = false
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportZoom(false)
        }
        wv.setBackgroundColor(S.applied().backgroundColor)
        wv.addJavascriptInterface(Host(), "SpiritualHost")
        wv.webViewClient = Client()
        holder.addView(wv, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        webView = wv

        // The bundled reader is copied on first use; that is small but still disk I/O.
        Thread {
            BooksRepository.ensureReader()
            val stamp = readerStamp()
            runOnUiThread {
                loadedReaderStamp = stamp
                webView?.loadUrl("$ORIGIN/reader/embed.html")
            }
        }.start()
    }

    private fun readerStamp(): Long =
        BooksRepository.readerDir.listFiles().orEmpty().sumOf { it.lastModified() xor it.length() }

    /** Reloads the page when a sync replaced the reader files since it was loaded. */
    private fun reloadIfReaderChanged() {
        val wv = webView ?: return
        val stamp = readerStamp()
        if (stamp == loadedReaderStamp) return
        loadedReaderStamp = stamp
        pageReady = false
        wv.reload()
    }

    private fun showMessage(text: String) {
        tMessage.text = text
        tMessage.isVisible = true
    }

    private fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    /** The Bible reader's display settings, in the terms embed.html understands. */
    private fun appearanceJson(): JSONObject {
        S.recalculateAppliedValuesBasedOnPreferences()
        val applied = S.applied()
        val dark = Preferences.getBoolean(Prefkey.is_night_mode, false) ||
            ColorUtils.calculateLuminance(applied.backgroundColor) < 0.5
        return JSONObject()
            .put("fontSizePx", applied.fontSize2dp.toDouble())
            // embed.html's default of 1.65 matches the Bible reader's default spacing of 1.15.
            .put("lineHeight", 1.65 * applied.lineSpacingMult / 1.15)
            .put("fg", cssColor(applied.fontColor))
            .put("bg", cssColor(applied.backgroundColor))
            .put("dark", dark)
            .put("fontFamily", cssFontFamily())
    }

    private fun applyAppearance() {
        val appearance = appearanceJson()
        webView?.setBackgroundColor(S.applied().backgroundColor)
        js("SpiritualEmbed.applyAppearance(${appearance})")
    }

    private fun cssColor(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    private fun cssFontFamily(): String {
        val name = Preferences.getString(Prefkey.jenisHuruf, null)
        return when {
            name == "SERIF" -> "serif"
            name == "MONOSPACE" -> "monospace"
            customFontFile() != null -> "'AppFont', sans-serif"
            else -> "sans-serif"
        }
    }

    private fun customFontFile(): File? {
        val name = Preferences.getString(Prefkey.jenisHuruf, null) ?: return null
        if (!FontManager.isCustomFont(name)) return null
        return File(FontManager.getFontDir(name), "$name-Regular.ttf").takeIf { it.canRead() }
    }

    private fun openBook() {
        val slug = slug ?: return
        if (customFontFile() != null) {
            js("(function(){var s=document.createElement('style');s.textContent=\"@font-face{font-family:'AppFont';src:url('/font/regular.ttf')}\";document.head.appendChild(s)})()")
        }
        val opts = JSONObject()
            .put("url", "/book/$slug.docx")
            .put("title", bookTitle)
            .put("appearance", appearanceJson())
            .put("scrollTop", Preferences.getInt(scrollKey(slug), 0))
            .put("sidebarOpen", Preferences.getBoolean(PREF_SIDEBAR_OPEN, false))
        js("SpiritualEmbed.open(${opts})")
    }

    private fun scrollKey(slug: String) = "books_scroll_$slug"

    private inner class Host {
        @JavascriptInterface
        fun onReady() = runOnUiThread {
            pageReady = true
            invalidateOptionsMenu()
            openBook()
        }

        @JavascriptInterface
        fun onRendered(headingCount: Int) = Unit

        @JavascriptInterface
        fun onScroll(scrollTop: Int) {
            slug?.let { Preferences.setInt(scrollKey(it), scrollTop) }
        }

        @JavascriptInterface
        fun onSidebar(open: Boolean) {
            outlineOpen = open
            Preferences.setBoolean(PREF_SIDEBAR_OPEN, open)
        }

        @JavascriptInterface
        fun onFindResultChosen(index: Int, count: Int) = runOnUiThread {
            if (findBar != null) updateFindCount(count, index, empty = false)
        }

        @JavascriptInterface
        fun onError(message: String) = runOnUiThread {
            showMessage(getString(R.string.books_download_failed, bookTitle, message))
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val uri = request.url
            if ("${uri.scheme}://${uri.host}" != ORIGIN) return notFound()
            val path = uri.path.orEmpty()
            return when {
                path.startsWith("/book/") && path.endsWith(".docx") -> {
                    val name = path.removePrefix("/book/").removeSuffix(".docx")
                    if (name != slug) notFound() else serve(BooksRepository.bookFile(name), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                }
                path == "/font/regular.ttf" -> customFontFile()?.let { serve(it, "font/ttf") } ?: notFound()
                path.startsWith("/reader/") -> {
                    val name = path.removePrefix("/reader/")
                    if (name.contains('/') || name.contains("..")) notFound() else serve(File(BooksRepository.readerDir, name), mimeOf(name))
                }
                else -> notFound()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if ("${uri.scheme}://${uri.host}" == ORIGIN) return false
            if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))) }
            }
            return true
        }

        // A book too big for the WebView kills its renderer; keep the app alive and say so.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            holder.removeAllViews()
            webView?.destroy()
            webView = null
            pageReady = false
            invalidateOptionsMenu()
            showMessage(getString(R.string.books_too_large))
            return true
        }

        private fun serve(file: File, mime: String): WebResourceResponse =
            if (file.canRead()) WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null, FileInputStream(file))
            else notFound()

        private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private fun mimeOf(name: String) = when (name.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            else -> "application/octet-stream"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_book_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val reading = pageReady && slug != null && findBar == null
        menu.findItem(R.id.menuContents).isVisible = reading
        menu.findItem(R.id.menuFind).isVisible = reading
        return super.onPrepareOptionsMenu(menu)
    }

    private fun startFind() {
        if (findBar != null) return
        js("SpiritualEmbed.setSidebar(false)")
        val bar = layoutInflater.inflate(R.layout.book_reader_find, null)
        tFindQuery = bar.findViewById(R.id.tFindQuery)
        tFindCount = bar.findViewById(R.id.tFindCount)
        tFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                findHandler.removeCallbacks(findRunnable)
                findHandler.postDelayed(findRunnable, 250)
                findPending = true
            }
        })
        tFindQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (findPending) {
                    // the query just changed: search it now instead of stepping the old one
                    findHandler.removeCallbacks(findRunnable)
                    findRunnable.run()
                } else {
                    runFind("SpiritualEmbed.findNext()")
                }
                hideKeyboard()
                true
            } else false
        }
        tFindCount.setOnClickListener {
            if (findCount > 0) {
                hideKeyboard()
                js("SpiritualEmbed.showFindResults()")
            }
        }
        bar.findViewById<View>(R.id.bFindPrev).setOnClickListener { runFind("SpiritualEmbed.findPrev()") }
        bar.findViewById<View>(R.id.bFindNext).setOnClickListener { runFind("SpiritualEmbed.findNext()") }
        bar.findViewById<View>(R.id.bFindClose).setOnClickListener { stopFind() }

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setHomeAsUpIndicator(null)
            setDisplayShowCustomEnabled(true)
            // Without these the toolbar sizes the bar to its content and leaves the right side empty.
            setCustomView(bar, ActionBar.LayoutParams(ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT))
        }
        findBar = bar
        updateFindCount(0, -1, empty = true)
        invalidateOptionsMenu()
        tFindQuery.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(tFindQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun stopFind() {
        if (findBar == null) return
        findHandler.removeCallbacks(findRunnable)
        findPending = false
        hideKeyboard()
        js("SpiritualEmbed.findClear()")
        supportActionBar?.apply {
            customView = null
            setDisplayShowCustomEnabled(false)
            setDisplayShowTitleEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu_white_24dp)
        }
        findBar = null
        invalidateOptionsMenu()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(tFindQuery.windowToken, 0)
    }

    /** Runs one of embed.html's find calls and shows the { count, index } it returns. */
    private fun runFind(script: String) {
        webView?.evaluateJavascript(script) { result ->
            val state = runCatching { JSONObject(result) }.getOrNull() ?: return@evaluateJavascript
            updateFindCount(state.optInt("count"), state.optInt("index", -1), empty = tFindQuery.text.isBlank())
        }
    }

    private fun updateFindCount(count: Int, index: Int, empty: Boolean) {
        findCount = count
        tFindCount.text = if (empty) "" else getString(R.string.books_find_count, if (count == 0) 0 else index + 1, count)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menuContents -> {
            js("SpiritualEmbed.toggleSidebar()")
            true
        }
        R.id.menuFind -> {
            startFind()
            true
        }
        android.R.id.home -> {
            if (findBar != null) stopFind() else leftDrawer.toggleDrawer()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun bDisplay_click() = toggleTextAppearancePanel()

    override fun cNightMode_checkedChange(isChecked: Boolean) {
        if (Preferences.getBoolean(Prefkey.is_night_mode, false) == isChecked) return
        Preferences.setBoolean(Prefkey.is_night_mode, isChecked)
        applyNightModeColors()
        applyAppearance()
        textAppearancePanel?.displayValues()
        App.getLbm().sendBroadcast(Intent(IsiActivity.ACTION_NIGHT_MODE_CHANGED))
    }

    private fun toggleTextAppearancePanel() {
        textAppearancePanel?.let {
            it.hide()
            textAppearancePanel = null
            return
        }
        textAppearancePanel = TextAppearancePanel(
            this,
            overlayContainer,
            object : TextAppearancePanel.Listener {
                override fun onValueChanged() = applyAppearance()

                override fun onCloseButtonClick() {
                    textAppearancePanel?.hide()
                    textAppearancePanel = null
                }
            },
            RequestCodes.FromActivity.TextAppearanceGetFonts,
            RequestCodes.FromActivity.TextAppearanceCustomColors
        ).also { it.show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQCODE_chooseBook) {
            val chosen = data?.getStringExtra(EXTRA_slug)
            reloadIfReaderChanged()
            if (resultCode == RESULT_OK && chosen != null) {
                showBook(chosen, data.getStringExtra(EXTRA_title) ?: chosen)
            } else if (slug == null) {
                finish() // nothing to show without a book
            }
            return
        }
        if (requestCode == RequestCodes.FromActivity.TextAppearanceGetFonts || requestCode == RequestCodes.FromActivity.TextAppearanceCustomColors) {
            textAppearancePanel?.onActivityResult(requestCode)
            applyAppearance()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) -> leftDrawer.closeDrawer()
            findBar != null -> webView?.evaluateJavascript("SpiritualEmbed.findResultsOpen()") { open ->
                // the answer arrives later; another Back may have closed find meanwhile
                if (findBar == null) return@evaluateJavascript
                if (open == "true") js("SpiritualEmbed.hideFindResults()") else stopFind()
            } ?: stopFind()
            textAppearancePanel != null -> {
                textAppearancePanel?.hide()
                textAppearancePanel = null
            }
            outlineOpen -> js("SpiritualEmbed.setSidebar(false)")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object {
        private const val ORIGIN = "https://books.local"
        const val EXTRA_slug = "slug"
        const val EXTRA_title = "title"
        private const val PREF_SIDEBAR_OPEN = "books_sidebar_open"
        private const val PREF_LAST_SLUG = "books_last_slug"
        private const val PREF_LAST_TITLE = "books_last_title"
        private const val REQCODE_chooseBook = 1
        private const val EXTRA_action = "action"
        const val ACTION_choose = "choose"

        /** Opens the last book read, or the book list when there is none. */
        @JvmStatic
        fun createIntent(): Intent = Intent(App.context, BookReaderActivity::class.java)

        /** Like [createIntent], then runs [action] ([ACTION_choose]). */
        @JvmStatic
        fun createIntent(action: String): Intent = createIntent().putExtra(EXTRA_action, action)

        /** Title of the last book read while it is still downloaded, for the drawer. */
        @JvmStatic
        fun lastBookTitle(): String? {
            val slug = Preferences.getString(PREF_LAST_SLUG, null) ?: return null
            if (!BooksRepository.bookFile(slug).exists()) return null
            return Preferences.getString(PREF_LAST_TITLE, null) ?: slug
        }
    }
}
