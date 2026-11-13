package com.awoo.swipereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var store: PdfStore
    private lateinit var grid: RecyclerView
    private lateinit var message: TextView
    private lateinit var emptyBox: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var sortButton: TextView
    private lateinit var streakView: TextView
    private lateinit var permBox: LinearLayout
    private val shelf = ShelfAdapter()
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

        val dpWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val spans = maxOf(2, (dpWidth / 170f).toInt())
        grid = RecyclerView(this)
        grid.layoutManager = GridLayoutManager(this, spans)
        grid.adapter = shelf
        grid.clipToPadding = false
        grid.setPadding(dp(8), dp(56), dp(8), dp(16))
        root.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))

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

        sortButton = link("Recent") { toggleSort() }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(link("Stats") { showStats() })
            addView(sortButton)
            addView(link("Folder") { pickFolder() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4)
            rightMargin = dp(4)
        })

        streakView = TextView(this).apply {
            textSize = 13f
            setTextColor(fg)
            alpha = 0.7f
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(streakView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        val permText = TextView(this).apply {
            text = "Swipe Shelf needs access to your files so it can find the PDFs Telegram downloads. It only reads, and has no internet access."
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
            grid.visibility = View.GONE
            emptyBox.visibility = View.GONE
            topBar.visibility = View.GONE
            streakView.visibility = View.GONE
            return
        }
        permBox.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        streakView.visibility = View.VISIBLE
        sortButton.text = if (store.sortMode() == "az") "A to Z" else "Recent"
        val streak = store.streak()
        streakView.text = if (streak > 0) "$streak day streak" else ""
        val list = store.series()
        shelf.submit(list)
        if (list.isEmpty()) {
            grid.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
            message.text = "No PDFs found yet.\n\nLooking in:\n" + store.describe() +
                "\n\nIf your Telegram PDFs are somewhere else, tap Choose folder."
        } else {
            emptyBox.visibility = View.GONE
            grid.visibility = View.VISIBLE
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

    private fun toggleSort() {
        store.setSortMode(if (store.sortMode() == "az") "recent" else "az")
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

    /** Episode list for one series: tap any episode to start there. */
    private fun showEpisodes(s: Series) {
        val cont = store.continueEpisode(s)
        val labels = s.episodes.map { p ->
            val base = p.info.label ?: p.file.nameWithoutExtension.replace('_', ' ')
            val page = store.lastPage(p.file)
            val status = when {
                store.isRead(p.file) -> "read"
                page > 0 -> "page ${page + 1}"
                else -> ""
            }
            if (status.isEmpty()) base else "$base   ($status)"
        }.toTypedArray()
        val d = AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(labels) { _, which -> open(s.episodes[which]) }
            .setNegativeButton("Close", null)
            .show()
        val idx = s.episodes.indexOfFirst { it.file.path == cont.file.path }
        if (idx > 0) d.listView.setSelection(idx)
    }

    class CardVH(
        val card: LinearLayout,
        val cover: ImageView,
        val name: TextView,
        val sub: TextView,
        val bar: ProgressBar,
        val action: TextView
    ) : RecyclerView.ViewHolder(card)

    inner class ShelfAdapter : RecyclerView.Adapter<CardVH>() {
        private var items: List<Series> = emptyList()

        fun submit(l: List<Series>) {
            items = l
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val c = parent.context
            val fg = ContextCompat.getColor(c, R.color.fg)
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (night) "#1E1F24" else "#F1F3F8"))
                    cornerRadius = c.dp(14).toFloat()
                }
                clipToOutline = true
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    setMargins(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
                }
            }
            val cover = ImageView(c).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setBackgroundColor(Color.parseColor(if (night) "#2A2B31" else "#E3E6EE"))
            }
            card.addView(cover, LinearLayout.LayoutParams(MATCH, c.dp(190)))

            val body = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(10))
            }
            val name = TextView(c).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(fg)
                minLines = 2
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(c).apply {
                textSize = 12f
                setTextColor(fg)
                alpha = 0.7f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal)
            val action = TextView(c).apply {
                textSize = 13f
                setTextColor(ContextCompat.getColor(c, R.color.accent))
                setPadding(0, c.dp(8), 0, 0)
            }
            body.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = c.dp(2) })
            body.addView(bar, LinearLayout.LayoutParams(MATCH, c.dp(3)).apply { topMargin = c.dp(8) })
            body.addView(action, LinearLayout.LayoutParams(WRAP, WRAP))
            card.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
            return CardVH(card, cover, name, sub, bar, action)
        }

        override fun onBindViewHolder(h: CardVH, position: Int) {
            val s = items[position]
            val cont = store.continueEpisode(s)
            val total = s.episodes.size
            val unread = s.episodes.count { !store.isRead(it.file) }

            h.name.text = s.name
            if (total > 1) {
                val lbl = cont.info.label ?: "next"
                h.sub.text = "Continue $lbl  ·  $unread unread"
                h.bar.max = total
                h.bar.progress = total - unread
                h.bar.visibility = View.VISIBLE
                h.action.text = "All $total episodes"
                h.action.visibility = View.VISIBLE
            } else {
                h.sub.text = if (store.isRead(cont.file)) "Read" else "New"
                h.bar.visibility = View.GONE
                h.action.visibility = View.GONE
            }

            h.cover.setImageDrawable(null)
            h.cover.tag = cont.file.path
            Thumbs.load(cont.file, 420) { t ->
                val bmp = t.bitmap
                if (bmp != null) {
                    h.cover.post {
                        if (h.cover.tag == cont.file.path && h.cover.width > 0) {
                            h.cover.setImageBitmap(bmp)
                            val sc = h.cover.width.toFloat() / bmp.width
                            h.cover.imageMatrix = Matrix().apply { setScale(sc, sc) }
                        }
                    }
                }
            }

            h.card.setOnClickListener { open(cont) }
            h.card.setOnLongClickListener {
                showEpisodes(s)
                true
            }
            h.action.setOnClickListener { showEpisodes(s) }
        }
    }
}
