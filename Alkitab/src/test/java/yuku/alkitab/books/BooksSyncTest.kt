package yuku.alkitab.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksSyncTest {
    private val manifestText = """
        {
         "version": 1,
         "reader": [
          {"path": "embed.html", "size": 10, "sha256": "e1"},
          {"path": "reader.js", "size": 20, "sha256": "r1"}
         ],
         "books": [
          {"slug": "the-secret-george-muller", "file": "The Secret - George Müller.docx", "url": "books/The%20Secret%20-%20George%20M%C3%BCller.docx",
           "title": "The Secret - George Müller", "author": "George Müller", "minutes": 1, "source": "pdf", "verified": true, "size": 2700, "sha256": "aaa"},
          {"slug": "abide-andrew-murray", "file": "Abide - Andrew Murray.docx", "url": "books/Abide%20-%20Andrew%20Murray.docx",
           "title": "Abide - Andrew Murray", "author": "Andrew Murray", "minutes": 90, "source": "docx", "verified": false, "size": 9000, "sha256": "bbb", "future": "ignored"},
          {"slug": "untitled", "file": "Untitled.docx", "url": "books/Untitled.docx",
           "title": "Untitled", "author": null, "minutes": 2, "source": "doc", "verified": false, "size": 100, "sha256": "ccc"},
          {"slug": "absolute-surrender-andrew-murray", "file": "Absolute Surrender - Andrew Murray.docx", "url": "books/Absolute%20Surrender%20-%20Andrew%20Murray.docx",
           "title": "Absolute Surrender - Andrew Murray", "author": "Andrew Murray", "minutes": 120, "source": "html", "verified": false, "size": 9000, "sha256": "ddd"}
         ]
        }
    """.trimIndent()

    private val manifest = BooksManifest.parse(manifestText)

    @Test
    fun `parse reads every field and ignores fields it does not know`() {
        assertEquals(2, manifest.reader.size)
        assertEquals(4, manifest.books.size)
        val muller = manifest.books[0]
        assertEquals("George Müller", muller.author)
        assertEquals("books/The%20Secret%20-%20George%20M%C3%BCller.docx", muller.url)
        assertTrue(muller.verified)
        assertEquals(null, manifest.books[2].author)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse rejects a manifest version the app does not understand`() {
        BooksManifest.parse(manifestText.replace("\"version\": 1", "\"version\": 2"))
    }

    @Test
    fun `encode then parse gives back the same manifest`() {
        assertEquals(manifest, BooksManifest.parse(BooksManifest.encode(manifest)))
    }

    @Test
    fun `shortTitle drops the author suffix only when it matches the author`() {
        assertEquals("The Secret", manifest.books[0].shortTitle)
        assertEquals("Untitled", manifest.books[2].shortTitle)
    }

    @Test
    fun `staleBooks returns only downloaded books whose hash changed`() {
        val downloaded = mapOf(
            "the-secret-george-muller" to "aaa",          // up to date
            "abide-andrew-murray" to "old",               // changed on the site
            "gone-from-the-site" to "zzz",                // removed from the site, left alone
        )
        assertEquals(listOf("abide-andrew-murray"), BooksSync.staleBooks(manifest, downloaded).map { it.slug })
    }

    @Test
    fun `staleReaderFiles returns missing and changed reader files`() {
        assertEquals(listOf("embed.html", "reader.js"), BooksSync.staleReaderFiles(manifest, emptyMap()).map { it.path })
        assertEquals(listOf("reader.js"), BooksSync.staleReaderFiles(manifest, mapOf("embed.html" to "e1", "reader.js" to "r0")).map { it.path })
        assertEquals(emptyList<String>(), BooksSync.staleReaderFiles(manifest, mapOf("embed.html" to "e1", "reader.js" to "r1")).map { it.path })
    }

    @Test
    fun `groupByAuthor sorts authors with Other last and books by title`() {
        val groups = BooksSync.groupByAuthor(manifest.books)
        assertEquals(listOf("Andrew Murray", "George Müller", null), groups.map { it.first })
        assertEquals(listOf("abide-andrew-murray", "absolute-surrender-andrew-murray"), groups[0].second.map { it.slug })
    }

    @Test
    fun `matches needs every term in title, author or source, ignoring case and accents`() {
        val muller = manifest.books[0]
        assertTrue(BooksSync.matches(muller, "muller"))
        assertTrue(BooksSync.matches(muller, "SECRET  müller"))
        assertTrue(BooksSync.matches(muller, "pdf"))
        assertTrue(BooksSync.matches(muller, "  "))
        assertFalse(BooksSync.matches(muller, "secret murray"))
    }

    @Test
    fun `sha256Hex matches the standard digest`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", BooksSync.sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BooksSync.sha256Hex("abc".toByteArray()))
    }
}
