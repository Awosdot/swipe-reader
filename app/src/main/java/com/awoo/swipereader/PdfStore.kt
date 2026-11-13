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

data class Parsed(val key: String, val name: String, val episode: Int?, val label: String?)

/** Works out which series a file belongs to and which episode it is, from the file name alone. */
object SeriesParser {
    private val season = Regex("""\b[sS](\d{1,2})\s*[eE](\d{1,4})\b""")
    private val word = Regex(
        """\b(?:episode|ep|chapter|chap|ch|part|pt|vol|volume|book|issue)\b[\s#:\-]*0*(\d{1,4})""",
        RegexOption.IGNORE_CASE
    )
    // Telegram channel style: "[001] Title" or "[Ch][024] Title"
    private val bracket = Regex("""\[\s*0*(\d{1,4})\s*\]""")
    private val tags = Regex("""\[[^\]]*\]""")
    private val handles = Regex("""@\S+""")
    private val hash = Regex("""#\s*0*(\d{1,4})""")
    private val paren = Regex("""\(\s*0*(\d{1,4})\s*\)""")
    private val trailing = Regex("""(?<!\d)0*(\d{1,4})\s*$""")
    private val junk = charArrayOf(' ', '-', ':', '#', '(', ')', ',', '[', ']')
    private val spaces = Regex("\\s+")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")

    fun parse(fileName: String): Parsed {
        val base = fileName.substringBeforeLast('.')
            .replace('_', ' ').replace('.', ' ')
            .replace(spaces, " ").trim()
        var ep: Int? = null
        var label: String? = null
        var range: IntRange? = null
        val s = season.find(base)
        if (s != null) {
            val sn = s.groupValues[1].toInt()
            val en = s.groupValues[2].toInt()
            ep = sn * 10000 + en
            label = "S%02dE%02d".format(sn, en)
            range = s.range
        } else {
            for (p in listOf(bracket, word, hash, paren, trailing)) {
                val m = p.find(base) ?: continue
                val n = m.groupValues[1].toIntOrNull() ?: continue
                ep = n
                label = "Episode $n"
                range = m.range
                break
            }
        }
        val rest = if (range != null) base.removeRange(range) else base
        // Drop channel tags like [MW], [Ch] and @handles so only the title is left
        val cleaned = rest.replace(tags, " ").replace(handles, " ")
        val name = cleaned.trim(*junk).replace(spaces, " ").ifEmpty { base }
        // Telegram cuts long titles and swaps apostrophes for spaces, so match on the first 20 letters only
        val key = name.lowercase().replace(nonAlnum, "").take(20)
        return Parsed(if (key.isEmpty()) base.lowercase() else key, name, ep, label)
    }
}

data class Pdf(val file: File, val modified: Long) {
    val info: Parsed = SeriesParser.parse(file.name)
}

/** One shelf card: every downloaded episode of the same title, in episode order. */
data class Series(val key: String, val name: String, val episodes: List<Pdf>)

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
            "Android/data/org.thunderdog.challegram/files/documents",
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

    fun orderMode(): String = prefs.getString("order", "series") ?: "series"

    fun setOrderMode(m: String) {
        prefs.edit().putString("order", m).apply()
    }

    /**
     * Series order: files are grouped by series, episodes run 1, 2, 3 whatever order they were
     * downloaded in. Series with something unread come first, most recently downloaded first.
     * Download order: unread first, newest first.
     */
    fun queue(): List<Pdf> {
        val all = scan()
        val groups = all.groupBy { it.info.key }.values.toList()
        val ordered = groups.sortedWith(
            compareBy<List<Pdf>>(
                { g -> g.all { isRead(it.file) } },
                { g -> -g.maxOf { it.modified } }
            )
        )
        return ordered.flatMap { g ->
            g.sortedWith(compareBy<Pdf>({ it.info.episode ?: Int.MAX_VALUE }, { it.modified }))
        }
    }

    /** The next unread file after this one: next episode first, then the next series. */
    fun nextUnread(current: File): Pdf? {
        val q = queue()
        val i = q.indexOfFirst { it.file.path == current.path }
        if (i < 0) {
            return q.firstOrNull { it.file.path != current.path && !isRead(it.file) }
        }
        return q.drop(i + 1).firstOrNull { !isRead(it.file) }
            ?: q.take(i).firstOrNull { !isRead(it.file) }
    }

    fun sortMode(): String = prefs.getString("sort", "recent") ?: "recent"

    fun setSortMode(m: String) {
        prefs.edit().putString("sort", m).apply()
    }

    /** The shelf: one entry per title. Recent puts unfinished series with new downloads first. */
    fun series(): List<Series> {
        val list = scan().groupBy { it.info.key }.map { (k, g) ->
            val eps = g.sortedWith(compareBy<Pdf>({ it.info.episode ?: Int.MAX_VALUE }, { it.modified }))
            Series(k, g.maxByOrNull { it.info.name.length }!!.info.name, eps)
        }
        return if (sortMode() == "az") {
            list.sortedBy { it.name.lowercase() }
        } else {
            list.sortedWith(
                compareBy<Series>(
                    { s -> s.episodes.all { isRead(it.file) } },
                    { s -> -s.episodes.maxOf { it.modified } }
                )
            )
        }
    }

    /** Remember which episode of a series was opened last. */
    fun setLast(f: File) {
        prefs.edit().putString("last:" + SeriesParser.parse(f.name).key, f.path).apply()
    }

    /** Where a tap on a shelf card should land: resume the open episode, else the next one, else episode 1. */
    fun continueEpisode(s: Series): Pdf {
        val lastPath = prefs.getString("last:" + s.key, null)
        val idx = s.episodes.indexOfFirst { it.file.path == lastPath }
        if (idx >= 0) {
            val cur = s.episodes[idx]
            if (!isRead(cur.file)) return cur
            s.episodes.getOrNull(idx + 1)?.let { return it }
        }
        return s.episodes.firstOrNull { !isRead(it.file) } ?: s.episodes.first()
    }

    /** Next episode by number in the same series (even if already read), then the next unread series. */
    fun nextEpisode(current: File): Pdf? {
        val key = SeriesParser.parse(current.name).key
        val eps = scan().filter { it.info.key == key }
            .sortedWith(compareBy<Pdf>({ it.info.episode ?: Int.MAX_VALUE }, { it.modified }))
        val i = eps.indexOfFirst { it.file.path == current.path }
        if (i >= 0 && i + 1 < eps.size) return eps[i + 1]
        return nextUnread(current)
    }

    /** Series name, episodes read, episodes in the library. */
    fun seriesProgress(f: File): Triple<String, Int, Int> {
        val info = SeriesParser.parse(f.name)
        val same = scan().filter { it.info.key == info.key }
        return Triple(info.name, same.count { isRead(it.file) }, same.size)
    }

    // Reading stats, all stored on the device only

    private fun today(): String = java.time.LocalDate.now().toString()

    private fun days(): List<Long> =
        (prefs.getStringSet("days", emptySet()) ?: emptySet())
            .mapNotNull { runCatching { java.time.LocalDate.parse(it).toEpochDay() }.getOrNull() }
            .sorted()

    fun touchToday() {
        val set = HashSet(prefs.getStringSet("days", emptySet()) ?: emptySet())
        if (set.add(today())) prefs.edit().putStringSet("days", set).apply()
    }

    fun recordFinish(f: File, pages: Int) {
        prefs.edit()
            .putInt("finishes", prefs.getInt("finishes", 0) + 1)
            .putInt("pages", prefs.getInt("pages", 0) + pages)
            .putInt("c:" + f.path, prefs.getInt("c:" + f.path, 0) + 1)
            .apply()
        touchToday()
    }

    fun streak(): Int {
        val d = days().toSet()
        var day = java.time.LocalDate.now().toEpochDay()
        if (day !in d) day -= 1
        var n = 0
        while (day in d) {
            n++
            day--
        }
        return n
    }

    fun bestStreak(): Int {
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (x in days()) {
            run = if (x == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = x
        }
        return best
    }

    fun statsText(): String {
        val all = scan()
        val finished = all.count { isRead(it.file) }
        val multi = all.groupBy { it.info.key }.values.filter { it.size > 1 }
        val seriesDone = multi.count { g -> g.all { isRead(it.file) } }
        return "PDFs in library: ${all.size}\n" +
            "Finished: $finished\n" +
            "Unread: ${all.size - finished}\n\n" +
            "Series with 2 or more parts: ${multi.size}\n" +
            "Series completed: $seriesDone\n\n" +
            "Times you reached the last page: ${prefs.getInt("finishes", 0)}\n" +
            "Pages finished: ${prefs.getInt("pages", 0)}\n\n" +
            "Current streak: ${streak()} days\n" +
            "Best streak: ${bestStreak()} days"
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
    private val cache = LruCache<String, Thumb>(40)

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
