package yuku.alkitab.base.util

import java.io.File
import yuku.alkitab.base.App

/**
 * Installs the fonts shipped in assets/bundled_fonts, each a folder holding
 * "<Name>-Regular.ttf", where [FontManager] finds installed fonts, so they show up in the font
 * list. A font already there is left alone.
 */
object BundledFonts {
    private const val TAG = "BundledFonts"
    private const val ASSET_DIR = "bundled_fonts"

    @JvmStatic
    fun installInBackground() {
        Background.run { install() }
    }

    private fun install() {
        val assets = App.context.assets
        for (name in assets.list(ASSET_DIR).orEmpty()) {
            val file = "$name-Regular.ttf"
            val target = File(FontManager.getFontDir(name), file)
            if (target.exists()) continue
            try {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "$file.tmp")
                assets.open("$ASSET_DIR/$name/$file").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(target)) error("Could not rename $tmp to $target")
            } catch (e: Exception) {
                AppLog.e(TAG, "Could not install bundled font $name", e)
            }
        }
    }
}
