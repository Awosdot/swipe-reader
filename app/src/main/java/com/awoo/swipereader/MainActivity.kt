package com.awoo.swipereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var store: PdfStore
    private lateinit var pager: ViewPager2
    private lateinit var message: TextView
    private lateinit var emptyBox: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var orderButton: TextView
    private lateinit var streakView: TextView
    private lateinit var permBox: LinearLayout
    private val adapter = CardAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val observers = ArrayList<FileObserver>()
    private val refreshTask = Runnable { refresh() }
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PdfStore(this)
        val fg = ContextCompat.getColor(this, R.color.fg)

        val root = FrameLayout(this)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        pager = ViewPager2(this)
        pager.adapter = adapter
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        message = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(fg)
        }
        val chooseButton = Button(this).apply {
            text = "Choose folder"
            setOnClickListener { pickFolder() }
        }
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(message, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(chooseButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(24) })
        }
        root.addView(emptyBox, FrameLayout.LayoutParams(MATCH, MATCH))

        orderButton = link("Series order") { toggleOrder() }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(link("Stats") { showStats() })
            addView(orderButton)
            addView(link("Folder") { pickFolder() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            rightMargin = dp(4)
        })

        streakView = TextView(this).apply {
            textSize = 13f
            setTextColor(fg)
            alpha = 0.7f
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(streakView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        val permText = TextView(this).apply {
            text = "Swipe Reader needs access to your files so it can find the PDFs Telegram downloads. It only reads, and has no internet access."
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(fg)
        }
        val permButton = Button(this).apply {
            text = "Allow access"
            setOnClickListener { askPermission() }
        }
        permBox.addView(permText, LinearLayout.LayoutParams(MATCH, WRAP))
        permBox.addView(permButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(24) })
        root.addView(permBox, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)

        if (!hasPermission()) askPermission()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        val blocked = asked && !ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (blocked) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } else {
            asked = true
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        startWatching()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        startWatching()
    }

    override fun onStop() {
        super.onStop()
        stopWatching()
        handler.removeCallbacks(refreshTask)
    }

    private fun startWatching() {
        stopWatching()
        if (!hasPermission()) return
        for (dir in store.folders()) {
            val o = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.endsWith(".pdf", ignoreCase = true)) {
                        // Wait for the file to settle, then refresh
                        handler.removeCallbacks(refreshTask)
                        handler.postDelayed(refreshTask, 2500)
                    }
                }
            }
            o.startWatching()
            observers.add(o)
        }
    }

    private fun stopWatching() {
        for (o in observers) o.stopWatching()
        observers.clear()
    }

    private fun refresh() {
        if (!hasPermission()) {
            permBox.visibility = View.VISIBLE
            pager.visibility = View.GONE
            emptyBox.visibility = View.GONE
            topBar.visibility = View.GONE
            streakView.visibility = View.GONE
            return
        }
        permBox.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        streakView.visibility = View.VISIBLE
        orderButton.text = if (store.orderMode() == "series") "Series order" else "Download order"
        val streak = store.streak()
        streakView.text = if (streak > 0) "$streak day streak" else ""
        val list = store.queue()
        adapter.submit(list)
        if (list.isEmpty()) {
            pager.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
            message.text = "No PDFs found yet.\n\nLooking in:\n" + store.describe() +
                "\n\nIf your Telegram PDFs are somewhere else, tap Choose folder."
        } else {
            emptyBox.visibility = View.GONE
            pager.visibility = View.VISIBLE
            // Land on the first unread episode so there is always something to read
            val start = list.indexOfFirst { !store.isRead(it.file) }.coerceAtLeast(0)
            pager.post { pager.setCurrentItem(start, false) }
        }
        // Folder may have been created since last time
        if (observers.isEmpty()) startWatching()
    }

    private fun link(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    private fun toggleOrder() {
        store.setOrderMode(if (store.orderMode() == "series") "downloaded" else "series")
        refresh()
    }

    private fun showStats() {
        AlertDialog.Builder(this)
            .setTitle("Your reading")
            .setMessage(store.statsText())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun pickFolder() {
        FolderPicker.show(this, store) {
            refresh()
            startWatching()
        }
    }

    private fun open(pdf: Pdf) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("path", pdf.file.path))
    }

    class CardVH(
        val root: LinearLayout,
        val count: TextView,
        val img: ImageView,
        val title: TextView,
        val meta: TextView,
        val series: TextView,
        val bar: ProgressBar
    ) : RecyclerView.ViewHolder(root)

    inner class CardAdapter : RecyclerView.Adapter<CardVH>() {
        private var items: List<Pdf> = emptyList()

        fun submit(l: List<Pdf>) {
            items = l
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val c = parent.context
            val fg = ContextCompat.getColor(c, R.color.fg)
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(c.dp(24), c.dp(32), c.dp(24), c.dp(32))
                layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            }
            val count = TextView(c).apply {
                textSize = 13f
                setTextColor(fg)
                alpha = 0.6f
            }
            val img = ImageView(c).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            val title = TextView(c).apply {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(fg)
            }
            val meta = TextView(c).apply {
                textSize = 14f
                setTextColor(fg)
                alpha = 0.7f
            }
            val hint = TextView(c).apply {
                text = "↑  Swipe up to read"
                textSize = 14f
                setTextColor(ContextCompat.getColor(c, R.color.accent))
            }
            root.addView(count, LinearLayout.LayoutParams(WRAP, WRAP))
            root.addView(img, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = c.dp(16)
                bottomMargin = c.dp(16)
            })
            val series = TextView(c).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(c, R.color.accent))
            }
            val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal)
            root.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(series, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = c.dp(6) })
            root.addView(bar, LinearLayout.LayoutParams(c.dp(180), c.dp(4)).apply { topMargin = c.dp(6) })
            root.addView(meta, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = c.dp(6) })
            root.addView(hint, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = c.dp(20) })
            return CardVH(root, count, img, title, meta, series, bar)
        }

        override fun onBindViewHolder(h: CardVH, position: Int) {
            val pdf = items[position]
            h.title.text = pdf.file.nameWithoutExtension.replace('_', ' ')
            val ago = DateUtils.getRelativeTimeSpanString(
                pdf.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
            h.meta.text = ago
            val state = if (store.isRead(pdf.file)) "read" else "new"
            h.count.text = "${position + 1} of ${items.size}  ·  $state"

            val same = items.filter { it.info.key == pdf.info.key }
            if (same.size > 1) {
                val read = same.count { store.isRead(it.file) }
                val ep = pdf.info.label?.let { "  ·  $it" } ?: ""
                h.series.text = pdf.info.name + ep + "  ·  $read of ${same.size} read"
                h.bar.max = same.size
                h.bar.progress = read
                h.series.visibility = View.VISIBLE
                h.bar.visibility = View.VISIBLE
            } else {
                h.series.visibility = View.GONE
                h.bar.visibility = View.GONE
            }

            h.img.setImageDrawable(null)
            h.img.tag = pdf.file.path
            Thumbs.load(pdf.file, 640) { t ->
                if (h.img.tag == pdf.file.path) {
                    h.img.setImageBitmap(t.bitmap)
                    if (t.pages > 0) h.meta.text = "${t.pages} pages  ·  $ago"
                }
            }

            val gd = GestureDetector(h.root.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    open(pdf)
                    return true
                }
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
                ): Boolean {
                    if (velocityY < -600f && abs(velocityY) > abs(velocityX)) {
                        open(pdf)
                        return true
                    }
                    return false
                }
            })
            h.root.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        }
    }
}
