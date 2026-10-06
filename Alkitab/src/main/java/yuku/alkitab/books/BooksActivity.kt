package yuku.alkitab.books

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import yuku.alkitab.base.App
import yuku.alkitab.base.ac.base.BaseActivity
import yuku.alkitab.base.util.Background
import yuku.alkitab.debug.R

/**
 * The list of books on the Spiritual Books site, grouped by author. Opened by
 * [BookReaderActivity] to choose a book: picking one downloads it if needed and returns its slug
 * and title as the result.
 */
class BooksActivity : BaseActivity() {
    private lateinit var progress: ProgressBar
    private lateinit var tStatus: TextView
    private val adapter = BooksAdapter()

    private var manifest: BooksManifest? = null
    private var downloaded: Set<String> = emptySet()
    private val downloading = mutableSetOf<String>()
    private var searchQuery = ""
    private var downloadedOnly = false
    private var verifiedOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_books)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.books_menu)

        progress = findViewById(R.id.progress)
        tStatus = findViewById(R.id.tStatus)
        findViewById<RecyclerView>(R.id.lsBooks).adapter = adapter

        Background.run {
            val cached = BooksRepository.cachedManifest()
            val slugs = BooksRepository.downloadedSlugs()
            runOnUiThread {
                if (manifest == null && cached != null) {
                    manifest = cached
                    downloaded = slugs
                    render()
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Background.run {
            val slugs = BooksRepository.downloadedSlugs()
            runOnUiThread {
                downloaded = slugs
                render()
            }
        }
    }

    private fun refresh() {
        progress.isVisible = true
        Background.run {
            val result = runCatching { BooksRepository.refresh() }
            val slugs = BooksRepository.downloadedSlugs()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                progress.isVisible = false
                downloaded = slugs
                result.onSuccess {
                    manifest = it
                    tStatus.isVisible = false
                }.onFailure { e ->
                    tStatus.isVisible = true
                    tStatus.text = if (manifest != null) {
                        getString(R.string.books_offline, e.message)
                    } else {
                        getString(R.string.books_unreachable, BooksRepository.siteUrl, e.message)
                    }
                }
                render()
            }
        }
    }

    private fun render() {
        val books = manifest?.books.orEmpty().filter { book ->
            (!downloadedOnly || book.slug in downloaded) &&
                (!verifiedOnly || book.verified) &&
                BooksSync.matches(book, searchQuery)
        }
        val rows = mutableListOf<Row>()
        for ((author, list) in BooksSync.groupByAuthor(books)) {
            rows += Row.Author(author ?: getString(R.string.books_other_author))
            list.forEach { rows += Row.Book(it) }
        }
        adapter.rows = rows
        if (manifest != null && rows.isEmpty()) {
            tStatus.isVisible = true
            tStatus.setText(R.string.books_empty)
        } else if (tStatus.text == getString(R.string.books_empty)) {
            tStatus.isVisible = false
        }
    }

    private fun choose(book: BooksManifest.BookEntry) {
        setResult(RESULT_OK, Intent().putExtra(BookReaderActivity.EXTRA_slug, book.slug).putExtra(BookReaderActivity.EXTRA_title, book.title))
        finish()
    }

    private fun open(book: BooksManifest.BookEntry) {
        if (book.slug in downloaded) {
            choose(book)
            return
        }
        if (!downloading.add(book.slug)) return
        render()
        Background.run {
            val result = runCatching { BooksRepository.downloadBook(book) }
            runOnUiThread {
                downloading -= book.slug
                result.onSuccess {
                    downloaded = downloaded + book.slug
                    render()
                    if (!isFinishing) choose(book)
                }.onFailure { e ->
                    render()
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.books_download_failed, book.title, e.message))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun confirmDelete(book: BooksManifest.BookEntry): Boolean {
        if (book.slug !in downloaded) return false
        AlertDialog.Builder(this)
            .setTitle(R.string.books_delete_title)
            .setMessage(getString(R.string.books_delete_message, book.title))
            .setPositiveButton(R.string.books_delete) { _, _ ->
                Background.run {
                    BooksRepository.deleteBook(book.slug)
                    runOnUiThread {
                        downloaded = downloaded - book.slug
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun editSiteAddress() {
        val input = EditText(this).apply {
            setText(BooksRepository.siteUrl)
            hint = TextUtils.expandTemplate(getText(R.string.books_site_address_hint), BooksRepository.DEFAULT_SITE_URL)
            setSingleLine()
            setSelectAllOnFocus(true)
            requestFocus()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.books_site_address)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                BooksRepository.siteUrl = input.text.toString()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_books, menu)
        (menu.findItem(R.id.menuSearch).actionView as SearchView).apply {
            queryHint = getString(R.string.books_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String) = false
                override fun onQueryTextChange(q: String): Boolean {
                    searchQuery = q
                    render()
                    return true
                }
            })
        }
        menu.findItem(R.id.menuDownloadedOnly).isChecked = downloadedOnly
        menu.findItem(R.id.menuVerifiedOnly).isChecked = verifiedOnly
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menuDownloadedOnly -> {
            downloadedOnly = !downloadedOnly
            item.isChecked = downloadedOnly
            render()
            true
        }
        R.id.menuVerifiedOnly -> {
            verifiedOnly = !verifiedOnly
            item.isChecked = verifiedOnly
            render()
            true
        }
        R.id.menuRefresh -> {
            refresh()
            true
        }
        R.id.menuSiteAddress -> {
            editSiteAddress()
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private sealed class Row {
        data class Author(val name: String) : Row()
        data class Book(val book: BooksManifest.BookEntry) : Row()
    }

    private inner class BooksAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Author) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == 0) R.layout.item_books_author else R.layout.item_books_book
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Author -> (holder.itemView as TextView).text = row.name
                is Row.Book -> bindBook(holder.itemView, row.book)
            }
        }

        private fun bindBook(view: View, book: BooksManifest.BookEntry) {
            view.findViewById<TextView>(R.id.tTitle).text = if (book.verified) "${book.shortTitle}  ✓" else book.shortTitle
            val h = book.minutes / 60
            val m = book.minutes % 60
            val time = if (h > 0) getString(R.string.books_hours, h, m) else getString(R.string.books_minutes, book.minutes)
            view.findViewById<TextView>(R.id.tDetails).text = listOfNotNull(time, book.source?.uppercase()).joinToString("  ·  ")

            val isDownloading = book.slug in downloading
            view.findViewById<ProgressBar>(R.id.progressBook).isVisible = isDownloading
            view.findViewById<ImageView>(R.id.imgStatus).apply {
                isVisible = !isDownloading
                setImageResource(if (book.slug in downloaded) R.drawable.ic_book_downloaded else R.drawable.ic_book_cloud)
            }
            view.setOnClickListener { open(book) }
            view.setOnLongClickListener { confirmDelete(book) }
        }
    }

    companion object {
        @JvmStatic
        fun createIntent() = Intent(App.context, BooksActivity::class.java)
    }
}
