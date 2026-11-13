package com.awoo.swipereader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

class ReaderActivity : AppCompatActivity() {

    private class Session(val pfd: ParcelFileDescriptor, val r: PdfRenderer) {
        fun close() {
            try { r.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    private class PageVH(val img: ImageView) : RecyclerView.ViewHolder(img)

    private lateinit var store: PdfStore
    private lateinit var rv: RecyclerView
    private lateinit var bar: ProgressBar
    private lateinit var label: TextView
    private lateinit var nextCard: LinearLayout
    private lateinit var nextTitle: TextView
    private lateinit var nextHint: TextView

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pageAdapter = PageAdapter()
    private val hideLabel = Runnable { label.visibility = View.GONE }

    // Touched only from the exec thread
    private var session: Session? = null

    @Volatile private var gen = 0
    private var file: File? = null
    private var ratios = FloatArray(0)
    private var nextFile: File? = null
    private var finishedShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PdfStore(this)
        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }

        val readerBg = ContextCompat.getColor(this, R.color.reader_bg)
        window.statusBarColor = readerBg
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val root = FrameLayout(this)
        root.setBackgroundColor(readerBg)

        rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = pageAdapter
        rv.clipToPadding = false
        rv.setPadding(0, 0, 0, dp(96))
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
            ) {
                outRect.bottom = dp(6)
            }
        })
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged()
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) saveProgress()
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showLabel()
                return false
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                tapDetector.onTouchEvent(e)
                return false
            }
        })
        root.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(bar, FrameLayout.LayoutParams(MATCH, dp(3)))

        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(label, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(24)
        })

        nextTitle = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nextHint = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ReaderActivity, R.color.accent))
        }
        val nextLabel = TextView(this).apply {
            text = "Up next"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
        }
        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B2D31"))
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        nextCard.addView(nextLabel)
        nextCard.addView(nextTitle)
        nextCard.addView(nextHint)
        root.addView(nextCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })
        val nextGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                goNext()
                return true
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (velocityY < -500f && abs(velocityY) > abs(velocityX)) {
                    goNext()
                    return true
                }
                return false
            }
        })
        nextCard.setOnTouchListener { _, ev -> nextGesture.onTouchEvent(ev) }

        setContentView(root)
        load(File(path))
    }

    private fun goNext() {
        val n = nextFile
        if (n != null) load(n) else finish()
    }

    private fun showLabel() {
        main.removeCallbacks(hideLabel)
        label.visibility = View.VISIBLE
        main.postDelayed(hideLabel, 1500)
    }

    private fun load(f: File) {
        gen += 1
        val g = gen
        file = f
        finishedShown = false
        nextCard.visibility = View.GONE
        ratios = FloatArray(0)
        pageAdapter.notifyDataSetChanged()
        cache.evictAll()
        bar.progress = 0

        exec.execute {
            session?.close()
            session = null
            try {
                val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                val rs = FloatArray(r.pageCount)
                for (i in rs.indices) {
                    val pg = r.openPage(i)
                    rs[i] = pg.height.toFloat() / pg.width
                    pg.close()
                }
                session = Session(p, r)
                main.post {
                    if (g != gen) return@post
                    ratios = rs
                    pageAdapter.notifyDataSetChanged()
                    val start = if (store.isRead(f)) 0
                    else store.lastPage(f).coerceIn(0, maxOf(0, rs.size - 1))
                    (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, 0)
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(this, "Cannot open this PDF", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun onScrollChanged() {
        val f = file ?: return
        val n = ratios.size
        if (n == 0) return
        val lm = rv.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0) return
        bar.max = n
        bar.progress = last + 1
        label.text = "${first + 1} / $n"
        if (!finishedShown && lm.findLastCompletelyVisibleItemPosition() >= n - 1) {
            finishedShown = true
            store.setRead(f, true)
            val nxt = store.queue().firstOrNull { it.file.path != f.path && !store.isRead(it.file) }
            nextFile = nxt?.file
            nextTitle.text = nxt?.file?.nameWithoutExtension?.replace('_', ' ') ?: "You are all caught up"
            nextHint.text = if (nxt != null) "↑  Swipe up to read next" else "Tap to go back"
            nextCard.visibility = View.VISIBLE
        }
    }

    private fun saveProgress() {
        val f = file ?: return
        if (ratios.isEmpty() || finishedShown) return
        val first = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        if (first >= 0) store.setLastPage(f, first)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        exec.execute {
            session?.close()
            session = null
        }
        exec.shutdown()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = ratios.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val img = ImageView(parent.context).apply {
                setBackgroundColor(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            return PageVH(img)
        }

        override fun onBindViewHolder(h: PageVH, pos: Int) {
            val w = if (rv.width > 0) rv.width else resources.displayMetrics.widthPixels
            val hgt = (w * ratios[pos]).toInt().coerceAtLeast(1)
            val lp = h.img.layoutParams
            lp.height = hgt
            h.img.layoutParams = lp

            val g = gen
            val key = "$g:$pos"
            h.img.tag = key
            val hit = cache.get(key)
            if (hit != null) {
                h.img.setImageBitmap(hit)
                return
            }
            h.img.setImageDrawable(null)
            exec.execute {
                if (g != gen) return@execute
                val s = session ?: return@execute
                try {
                    val page = s.r.openPage(pos)
                    val b = try {
                        val bb = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
                        bb.eraseColor(Color.WHITE)
                        page.render(bb, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bb
                    } finally {
                        page.close()
                    }
                    cache.put(key, b)
                    main.post { if (h.img.tag == key) h.img.setImageBitmap(b) }
                } catch (e: Exception) {
                    // Page failed to render; leave it blank
                }
            }
        }
    }
}
