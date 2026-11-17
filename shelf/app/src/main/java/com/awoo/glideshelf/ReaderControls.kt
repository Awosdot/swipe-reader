package com.awoo.glideshelf

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * Reading helpers shared by every reader screen:
 * auto scroll with an on/off button and speed, a slider down the right edge, and a jump to end button.
 * Works on any vertical RecyclerView that uses a LinearLayoutManager.
 */
class ReaderControls(
    private val act: Activity,
    private val rv: RecyclerView,
    private val root: FrameLayout,
    private val accent: Int,
    private val describe: () -> String,
    private val jumpToEnd: () -> Unit,
    private val moreComing: () -> Boolean = { false }
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("reader_ui", Context.MODE_PRIVATE)

    // Scroll speeds in dp per second
    private val speeds = floatArrayOf(30f, 55f, 90f, 140f, 210f, 310f)
    private var level = prefs.getInt("speed", 2).coerceIn(0, speeds.size - 1)

    private var auto = false
    private var touching = false
    private var lastFrame = 0L
    private var carry = 0f

    private lateinit var scrub: ScrubView
    private lateinit var playBtn: CircleBtn
    private lateinit var row: LinearLayout
    private lateinit var chip: TextView
    private lateinit var bubble: TextView

    private val lm: LinearLayoutManager get() = rv.layoutManager as LinearLayoutManager

    private fun dp(v: Int): Int = act.dp(v)

    private val hideRow = Runnable {
        row.visibility = View.GONE
        playBtn.alpha = 0.6f
    }
    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    private val frame = object : Runnable {
        override fun run() {
            if (!auto) return
            val now = SystemClock.uptimeMillis()
            val dt = min(now - lastFrame, 64L)
            lastFrame = now
            // Pause while a finger is on the screen or a fling is running
            if (!touching && !scrub.dragging && rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                carry += speeds[level] * act.resources.displayMetrics.density * dt / 1000f
                val px = carry.toInt()
                if (px > 0) {
                    rv.scrollBy(0, px)
                    carry -= px
                }
                if (!rv.canScrollVertically(1) && !moreComing()) {
                    stopAuto()
                    return
                }
            }
            rv.postOnAnimation(this)
        }
    }

    fun attach() {
        scrub = ScrubView(
            act, accent,
            onSeek = { f -> seekTo(f) },
            onDrag = { on -> dragChanged(on) }
        )
        root.addView(scrub, FrameLayout.LayoutParams(dp(36), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        bubble = TextView(act).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply { marginEnd = dp(48) })

        playBtn = CircleBtn(act, accent)
        playBtn.glyph = Glyph.PLAY
        playBtn.alpha = 0.6f
        playBtn.setOnClickListener {
            toggleAuto()
            show()
        }
        root.addView(playBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(44)
        })

        val endBtn = CircleBtn(act, accent)
        endBtn.glyph = Glyph.END
        endBtn.setOnClickListener {
            jumpToEnd()
            show()
        }
        val slower = CircleBtn(act, accent)
        slower.glyph = Glyph.MINUS
        slower.setOnClickListener { changeSpeed(-1) }
        val faster = CircleBtn(act, accent)
        faster.glyph = Glyph.PLUS
        faster.setOnClickListener { changeSpeed(1) }
        chip = TextView(act).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC202226.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        row.addView(endBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        row.addView(slower, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        row.addView(faster, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = dp(44 + 48 + 10) })
        updateChip()

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                sync()
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> touching = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
                }
                return false
            }
        })
    }

    /** Reveal the speed and jump buttons for a few seconds. */
    fun show() {
        if (!::row.isInitialized) return
        row.visibility = View.VISIBLE
        playBtn.alpha = 1f
        main.removeCallbacks(hideRow)
        main.postDelayed(hideRow, 4000)
    }

    fun isAuto(): Boolean = auto

    fun toggleAuto() {
        if (auto) stopAuto() else startAuto()
    }

    private fun startAuto() {
        auto = true
        carry = 0f
        lastFrame = SystemClock.uptimeMillis()
        playBtn.glyph = Glyph.PAUSE
        playBtn.active = true
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rv.postOnAnimation(frame)
    }

    fun stopAuto() {
        if (!auto) return
        auto = false
        if (::playBtn.isInitialized) {
            playBtn.glyph = Glyph.PLAY
            playBtn.active = false
        }
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rv.removeCallbacks(frame)
    }

    fun release() {
        stopAuto()
        main.removeCallbacksAndMessages(null)
    }

    private fun changeSpeed(d: Int) {
        level = (level + d).coerceIn(0, speeds.size - 1)
        prefs.edit().putInt("speed", level).apply()
        updateChip()
        show()
    }

    private fun updateChip() {
        chip.text = "Speed ${level + 1}"
    }

    /** Move the slider thumb to match where the list is now. */
    fun sync() {
        if (!::scrub.isInitialized || scrub.dragging) return
        scrub.fraction = fractionNow()
    }

    private fun fractionNow(): Float {
        val n = rv.adapter?.itemCount ?: 0
        if (n < 2) return 0f
        if (!rv.canScrollVertically(1)) return 1f
        val fv = lm.findFirstVisibleItemPosition()
        if (fv < 0) return 0f
        val v = lm.findViewByPosition(fv)
        val inside = if (v != null && v.height > 0) (-v.top).toFloat() / v.height else 0f
        return min(0.99f, (fv + inside.coerceIn(0f, 1f)) / (n - 1))
    }

    private fun seekTo(f: Float) {
        val n = rv.adapter?.itemCount ?: 0
        if (n < 2) return
        val t = f.coerceIn(0f, 1f) * (n - 1)
        val idx = min(t.toInt(), n - 1)
        val inside = t - idx
        lm.scrollToPositionWithOffset(idx, 0)
        rv.post {
            if (inside > 0.02f) {
                val v = lm.findViewByPosition(idx)
                if (v != null) rv.scrollBy(0, (inside * v.height).toInt())
            }
            showBubble()
        }
    }

    private fun showBubble() {
        bubble.text = describe()
        bubble.visibility = View.VISIBLE
        val bh = if (bubble.height > 0) bubble.height else dp(36)
        bubble.translationY = (dp(12) + scrub.thumbCenterY() - bh / 2f).coerceAtLeast(dp(4).toFloat())
        main.removeCallbacks(hideBubble)
    }

    private fun dragChanged(on: Boolean) {
        main.removeCallbacks(hideBubble)
        if (!on) {
            main.postDelayed(hideBubble, 700)
            sync()
        }
    }
}

private enum class Glyph { PLAY, PAUSE, PLUS, MINUS, END }

/** A slim track down the right edge with a draggable thumb. */
private class ScrubView(
    ctx: Context,
    private val accent: Int,
    private val onSeek: (Float) -> Unit,
    private val onDrag: (Boolean) -> Unit
) : View(ctx) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var dragging = false
        private set

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val thumbH = ctx.dp(56).toFloat()

    fun thumbCenterY(): Float {
        val usable = (height - thumbH).coerceAtLeast(0f)
        return thumbH / 2f + usable * fraction
    }

    override fun onDraw(c: Canvas) {
        val cx = width - context.dp(14).toFloat()
        val tw = context.dp(4) / 2f
        rect.set(cx - tw, 0f, cx + tw, height.toFloat())
        c.drawRoundRect(rect, tw, tw, track)
        thumb.color = accent
        thumb.alpha = if (dragging) 255 else 160
        val w = context.dp(if (dragging) 14 else 10) / 2f
        val cy = thumbCenterY()
        rect.set(cx - w, cy - thumbH / 2f, cx + w, cy + thumbH / 2f)
        c.drawRoundRect(rect, w, w, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent.requestDisallowInterceptTouchEvent(true)
                onDrag(true)
                move(e.y)
            }
            MotionEvent.ACTION_MOVE -> move(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onDrag(false)
                invalidate()
            }
        }
        return true
    }

    private fun move(y: Float) {
        val usable = height - thumbH
        if (usable <= 0f) return
        fraction = (y - thumbH / 2f) / usable
        onSeek(fraction)
    }
}

/** Round button drawn with a few lines, so no icon files are needed. */
private class CircleBtn(ctx: Context, private val accent: Int) : View(ctx) {
    var glyph = Glyph.PLAY
        set(v) {
            field = v
            invalidate()
        }
    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    init {
        isClickable = true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = if (active) accent else 0xCC202226.toInt()
        c.drawCircle(w / 2f, h / 2f, min(w, h) / 2f, bg)
        val u = min(w, h) / 10f
        val cx = w / 2f
        val cy = h / 2f
        when (glyph) {
            Glyph.PLAY -> {
                path.reset()
                path.moveTo(cx - 1.6f * u, cy - 2.4f * u)
                path.lineTo(cx + 2.6f * u, cy)
                path.lineTo(cx - 1.6f * u, cy + 2.4f * u)
                path.close()
                c.drawPath(path, solid)
            }
            Glyph.PAUSE -> {
                c.drawRect(cx - 2.2f * u, cy - 2.3f * u, cx - 0.7f * u, cy + 2.3f * u, solid)
                c.drawRect(cx + 0.7f * u, cy - 2.3f * u, cx + 2.2f * u, cy + 2.3f * u, solid)
            }
            Glyph.PLUS -> {
                c.drawLine(cx - 2.4f * u, cy, cx + 2.4f * u, cy, line)
                c.drawLine(cx, cy - 2.4f * u, cx, cy + 2.4f * u, line)
            }
            Glyph.MINUS -> {
                c.drawLine(cx - 2.4f * u, cy, cx + 2.4f * u, cy, line)
            }
            Glyph.END -> {
                path.reset()
                path.moveTo(cx - 2.4f * u, cy - 2.2f * u)
                path.lineTo(cx, cy + 0.4f * u)
                path.lineTo(cx + 2.4f * u, cy - 2.2f * u)
                c.drawPath(path, line)
                c.drawLine(cx - 2.4f * u, cy + 2.6f * u, cx + 2.4f * u, cy + 2.6f * u, line)
            }
        }
    }
}
