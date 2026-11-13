package com.awoo.swipereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.Executors

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

data class Pdf(val file: File, val modified: Long)

class PdfStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("swipe_reader", Context.MODE_PRIVATE)

    /** Folder the user picked, or null for automatic mode. */
    fun chosenFolder(): File? =
        prefs.getString("folder", null)?.let { File(it) }?.takeIf { it.isDirectory }

    fun setChosenFolder(f: File?) {
        val e = prefs.edit()
        if (f == null) e.remove("folder") else e.putString("folder", f.path)
        e.apply()
    }

    /** Places Telegram and its forks usually put downloads (automatic mode). */
    fun candidates(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "Telegram/Telegram Files",
            "Telegram/Telegram Documents",
            "Android/data/org.telegram.messenger/files/Telegram/Telegram Documents",
            "Android/data/org.telegram.messenger.web/files/Telegram/Telegram Documents",
            "Android/data/org.telegram.plus/files/Telegram/Telegram Documents",
            "Android/data/org.thunderdog.challegram/files/Telegram/Telegram Documents",
            "Download/Telegram"
        ).map { File(root, it) }
    }

    /** Chosen folder if set, otherwise every automatic location that exists. */
    fun folders(): List<File> {
        val c = chosenFolder()
        return if (c != null) listOf(c) else candidates().filter { it.isDirectory }
    }

    /** Human readable list of where the app is looking, for the empty screen. */
    fun describe(): String {
        val c = chosenFolder()
        if (c != null) return c.path
        val found = candidates().filter { it.isDirectory }
        return if (found.isEmpty()) "No Telegram folder found on this device"
        else found.joinToString("\n") { it.path }
    }

    private fun collect(dir: File, depth: Int, cutoff: Long, out: MutableList<Pdf>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile) {
                if (f.length() > 0 && f.name.endsWith(".pdf", ignoreCase = true) &&
                    f.lastModified() < cutoff
                ) {
                    out.add(Pdf(f, f.lastModified()))
                }
            } else if (f.isDirectory && depth > 0 && !f.name.startsWith(".")) {
                collect(f, depth - 1, cutoff, out)
            }
        }
    }

    /** Finished PDFs only: non-empty and not written to in the last 2 seconds. Looks 2 levels deep. */
    fun scan(): List<Pdf> {
        val cutoff = System.currentTimeMillis() - 2000
        val out = ArrayList<Pdf>()
        for (dir in folders()) collect(dir, 2, cutoff, out)
        return out.distinctBy { it.file.path }
    }

    /** Unread first (newest first), then already read (newest first). */
    fun queue(): List<Pdf> {
        val all = scan().sortedByDescending { it.modified }
        return all.filter { !isRead(it.file) } + all.filter { isRead(it.file) }
    }

    fun isRead(f: File): Boolean = prefs.getBoolean("r:" + f.path, false)
    fun setRead(f: File, v: Boolean) = prefs.edit().putBoolean("r:" + f.path, v).apply()
    fun lastPage(f: File): Int = prefs.getInt("p:" + f.path, 0)
    fun setLastPage(f: File, p: Int) = prefs.edit().putInt("p:" + f.path, p).apply()
}

data class Thumb(val bitmap: Bitmap?, val pages: Int)

/** Renders page 1 of a PDF for the cover card, off the main thread. */
object Thumbs {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Thumb>(12)

    fun load(file: File, width: Int, done: (Thumb) -> Unit) {
        val key = file.path + ":" + file.lastModified()
        val hit = cache.get(key)
        if (hit != null) {
            done(hit)
            return
        }
        exec.execute {
            var bmp: Bitmap? = null
            var pages = 0
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val r = PdfRenderer(pfd)
                    try {
                        pages = r.pageCount
                        if (pages > 0) {
                            val p = r.openPage(0)
                            try {
                                val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp = b
                            } finally {
                                p.close()
                            }
                        }
                    } finally {
                        r.close()
                    }
                } finally {
                    pfd.close()
                }
            } catch (e: Exception) {
                // Unreadable or password protected: show a blank cover
            }
            val t = Thumb(bmp, pages)
            cache.put(key, t)
            main.post { done(t) }
        }
    }
}
