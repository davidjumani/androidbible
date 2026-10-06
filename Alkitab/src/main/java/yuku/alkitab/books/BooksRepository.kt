package yuku.alkitab.books

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import yuku.afw.storage.Preferences
import yuku.alkitab.base.App
import yuku.alkitab.base.connection.Connections
import yuku.alkitab.base.util.AppLog

/**
 * Books synced from the Spiritual Books site: its manifest, the downloaded .docx books, and the
 * reader page (embed.html and its scripts) that shows them. Everything lives under
 * files/books. All methods do I/O; call them off the main thread.
 */
object BooksRepository {
    private const val TAG = "BooksRepository"
    const val DEFAULT_SITE_URL = "https://my-spiritual-books.vercel.app"
    private const val PREF_SITE_URL = "books_site_url"
    private const val BUNDLED_READER_DIR = "books_reader"

    private val root get() = File(App.context.filesDir, "books")
    private val manifestFile get() = File(root, "manifest.json")
    private val stateFile get() = File(root, "state.json")
    private val docsDir get() = File(root, "docs")
    val readerDir: File get() = File(root, "reader")

    @Serializable
    private data class State(
        /** Slug to sha256 of the downloaded copy. */
        val books: Map<String, String> = emptyMap(),
        /** Reader file path to sha256 of the local copy. */
        val reader: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    var siteUrl: String
        get() = Preferences.getString(PREF_SITE_URL, DEFAULT_SITE_URL).trimEnd('/')
        set(value) = Preferences.setString(PREF_SITE_URL, value.trim().trimEnd('/').ifEmpty { DEFAULT_SITE_URL })

    fun cachedManifest(): BooksManifest? = try {
        if (manifestFile.exists()) BooksManifest.parse(manifestFile.readText()) else null
    } catch (e: Exception) {
        AppLog.e(TAG, "Cached manifest unreadable", e)
        null
    }

    /**
     * Fetches the manifest, then brings the reader files and every already-downloaded book up to
     * date. Books that were never downloaded are fetched on demand by [downloadBook].
     *
     * @return the fresh manifest
     * @throws IOException when the site can't be reached or sends something unusable
     */
    fun refresh(): BooksManifest {
        val text = Connections.downloadString("$siteUrl/manifest.json")
        val manifest = try {
            BooksManifest.parse(text)
        } catch (e: Exception) {
            // The site answers a missing file with its home page.
            val reason = if (text.trimStart().startsWith("<")) "the site has no manifest.json yet" else "its manifest.json is not usable"
            throw IOException(reason, e)
        }
        root.mkdirs()
        writeAtomically(manifestFile, text.toByteArray())

        for (file in BooksSync.staleReaderFiles(manifest, readState().reader)) {
            val bytes = downloadVerified("$siteUrl/${file.path}", file.sha256)
            writeAtomically(File(readerDir, file.path), bytes)
            updateState { it.copy(reader = it.reader + (file.path to file.sha256)) }
        }

        for (book in BooksSync.staleBooks(manifest, readState().books)) {
            try {
                downloadBook(book)
            } catch (e: IOException) {
                AppLog.e(TAG, "Could not update ${book.slug}", e)
            }
        }
        return manifest
    }

    fun isDownloaded(slug: String): Boolean = slug in readState().books && bookFile(slug).exists()

    fun downloadedSlugs(): Set<String> = readState().books.keys.filter { bookFile(it).exists() }.toSet()

    fun bookFile(slug: String) = File(docsDir, "$slug.docx")

    /** Downloads [book], checking its hash before replacing any copy already here. */
    fun downloadBook(book: BooksManifest.BookEntry) {
        val bytes = downloadVerified("$siteUrl/${book.url}", book.sha256)
        writeAtomically(bookFile(book.slug), bytes)
        updateState { it.copy(books = it.books + (book.slug to book.sha256)) }
    }

    fun deleteBook(slug: String) {
        bookFile(slug).delete()
        updateState { it.copy(books = it.books - slug) }
    }

    /**
     * Makes sure the reader page is on disk: the synced copy if there is one, otherwise the copy
     * bundled in the APK, so books open even before the first successful sync.
     */
    fun ensureReader() {
        val assets = App.context.assets
        val bundled = assets.list(BUNDLED_READER_DIR).orEmpty()
        for (name in bundled) {
            val target = File(readerDir, name)
            if (target.exists()) continue
            val bytes = assets.open("$BUNDLED_READER_DIR/$name").use { it.readBytes() }
            writeAtomically(target, bytes)
            updateState { it.copy(reader = it.reader + (name to BooksSync.sha256Hex(bytes))) }
        }
    }

    private fun downloadVerified(url: String, sha256: String): ByteArray {
        val bytes = Connections.downloadBytes(url)
        val actual = BooksSync.sha256Hex(bytes)
        if (actual != sha256) throw IOException("Downloaded $url does not match the manifest (sha256 $actual, expected $sha256)")
        return bytes
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not write $target")
        }
    }

    @Synchronized
    private fun readState(): State = try {
        if (stateFile.exists()) json.decodeFromString(State.serializer(), stateFile.readText()) else State()
    } catch (e: Exception) {
        AppLog.e(TAG, "State unreadable, starting over", e)
        State()
    }

    @Synchronized
    private fun updateState(change: (State) -> State) {
        val next = change(readState())
        writeAtomically(stateFile, json.encodeToString(State.serializer(), next).toByteArray())
    }
}
