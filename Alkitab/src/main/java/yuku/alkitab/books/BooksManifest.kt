package yuku.alkitab.books

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The site's manifest.json, written by its generate.js. */
@Serializable
data class BooksManifest(
    val version: Int,
    val reader: List<ReaderFile>,
    val books: List<BookEntry>,
) {
    @Serializable
    data class ReaderFile(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    @Serializable
    data class BookEntry(
        val slug: String,
        val file: String,
        /** Relative to the site root and already URL-encoded. */
        val url: String,
        val title: String,
        val author: String? = null,
        val minutes: Int = 0,
        val source: String? = null,
        val verified: Boolean = false,
        val size: Long,
        val sha256: String,
    ) {
        /** The title without its " - Author" suffix. */
        val shortTitle: String
            get() = if (author != null && title.endsWith(" - $author")) title.removeSuffix(" - $author") else title
    }

    companion object {
        /** Manifest versions this app understands. */
        const val SUPPORTED_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BooksManifest {
            val manifest = json.decodeFromString(serializer(), text)
            require(manifest.version == SUPPORTED_VERSION) { "Unsupported manifest version ${manifest.version}" }
            return manifest
        }

        fun encode(manifest: BooksManifest): String = json.encodeToString(serializer(), manifest)
    }
}

object BooksSync {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Downloaded books whose content changed on the site, so they need downloading again.
     * Books no longer on the site are left alone; they stay readable until deleted.
     *
     * @param downloaded slug to the sha256 of the downloaded copy
     */
    fun staleBooks(manifest: BooksManifest, downloaded: Map<String, String>): List<BooksManifest.BookEntry> =
        manifest.books.filter { book -> downloaded[book.slug]?.let { it != book.sha256 } ?: false }

    /**
     * Reader files that are missing or differ from the manifest.
     *
     * @param local path to the sha256 of the local copy
     */
    fun staleReaderFiles(manifest: BooksManifest, local: Map<String, String>): List<BooksManifest.ReaderFile> =
        manifest.reader.filter { local[it.path] != it.sha256 }

    /**
     * Books grouped by author for the list, authors sorted alphabetically with books without an
     * author ("Other") last, and books sorted by title inside each group.
     */
    fun groupByAuthor(books: List<BooksManifest.BookEntry>): List<Pair<String?, List<BooksManifest.BookEntry>>> =
        books.groupBy { it.author }
            .toList()
            .sortedWith(compareBy<Pair<String?, List<BooksManifest.BookEntry>>> { it.first == null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.orEmpty() })
            .map { (author, list) -> author to list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }

    /** Every search term must appear in the title, author or source, ignoring case and accents. */
    fun matches(book: BooksManifest.BookEntry, query: String): Boolean {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val text = normalize(book.title + " " + book.author.orEmpty() + " " + book.source.orEmpty())
        return terms.all { it in text }
    }

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
