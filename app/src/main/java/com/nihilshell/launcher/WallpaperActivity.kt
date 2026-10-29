package com.nihilshell.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nihilshell.launcher.core.DotRender
import com.nihilshell.launcher.core.Prefs
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.Pill
import com.nihilshell.launcher.ui.Segmented
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.lp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.text
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The wallpaper studio: a photo becomes an LED matrix at the exact resolution
 * of the screen, with the same algorithm as the desktop's wallpapers. All on
 * the phone; nothing is uploaded anywhere.
 */
class WallpaperActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: ImageView
    private lateinit var empty: LinearLayout
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var dots: Segmented

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0
    private var cover: Bitmap? = null
    private var result: Bitmap? = null
    private var generation = 0

    private val pitches by lazy { intArrayOf(dp(5), dp(7), dp(10)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        // Edge to edge on every version (Android 15 enforces it; 12-14 need the call).
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false

        val bounds = windowManager.maximumWindowMetrics.bounds
        screenW = minOf(bounds.width(), bounds.height())
        screenH = maxOf(bounds.width(), bounds.height())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Nihil.VOID)
        }
        setContentView(column)
        column.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(20), bars.top + dp(16), bars.right + dp(20), bars.bottom + dp(16))
            insets
        }

        // Title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(getString(R.string.studio_title), 22f, Nihil.INK, Nihil.fonts(this).sans), llp(0, WRAP, 1f))
        header.addView(Pill(this, getString(R.string.studio_pick)).apply { setOnClickListener { pick() } })
        column.addView(header, llp(MATCH, WRAP))

        // The preview, in the screen's proportions.
        val stage = AspectFrame(this, screenW.toFloat() / screenH)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22f))
            }
            clipToOutline = true
        }
        stage.addView(preview, lp(MATCH, MATCH))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            addView(text(getString(R.string.studio_empty), 15f, Nihil.DIM, Nihil.fonts(this@WallpaperActivity).sans).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(4f), 1f)
            })
            addView(Pill(this@WallpaperActivity, getString(R.string.studio_pick), filled = true).apply {
                setOnClickListener { pick() }
            }, llp(WRAP, WRAP).apply { topMargin = dp(22) })
        }
        stage.addView(empty, lp(MATCH, MATCH))
        column.addView(stage, llp(MATCH, 0, 1f).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        })

        // Controls.
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chosen = pitches.indices.minByOrNull { abs(pitches[it] - prefs.dotPitch) } ?: 1
        dots = Segmented(
            this,
            listOf(getString(R.string.dots_fine), getString(R.string.dots_normal), getString(R.string.dots_coarse)),
            chosen,
        ) { index ->
            prefs.dotPitch = pitches[index]
            render()
        }
        controls.addView(labelled(R.string.studio_dots, dots))

        val targets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Pill(this@WallpaperActivity, getString(R.string.apply_home), filled = true).apply {
                setOnClickListener { apply(WallpaperManager.FLAG_SYSTEM) }
            })
            addView(Pill(this@WallpaperActivity, getString(R.string.apply_lock)).apply {
                setOnClickListener { apply(WallpaperManager.FLAG_LOCK) }
            }, llp(WRAP, WRAP).apply { marginStart = dp(8) })
            addView(Pill(this@WallpaperActivity, getString(R.string.apply_both)).apply {
                setOnClickListener { apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
            }, llp(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        controls.addView(labelled(R.string.studio_apply, targets), llp(MATCH, WRAP).apply { topMargin = dp(12) })
        status = mono("", 11f, Nihil.DIM).apply {
            gravity = Gravity.CENTER
            minHeight = dp(18)
        }
        controls.addView(status, llp(MATCH, WRAP).apply { topMargin = dp(14) })
        column.addView(controls, llp(MATCH, WRAP))
        showControls(false)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun labelled(label: Int, control: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(mono(getString(label), 11f, Nihil.MUTED), llp(dp(92), WRAP))
        addView(control, llp(WRAP, WRAP))
    }

    private fun showControls(on: Boolean) {
        controls.alpha = if (on) 1f else 0.35f
        setEnabledDeep(controls, on)
        empty.visibility = if (on || cover != null) View.GONE else View.VISIBLE
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    @Suppress("DEPRECATION")
    private fun pick() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        runCatching { startActivityForResult(intent, PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) load(uri)
    }

    private fun load(uri: Uri) {
        val job = ++generation
        status.text = getString(R.string.studio_rendering)
        val resolver = contentResolver
        worker.execute {
            val decoded = runCatching { DotRender.decodeCover(resolver, uri, screenW, screenH) }.getOrNull()
            main.post {
                if (job != generation) return@post
                if (decoded == null) {
                    status.text = getString(R.string.studio_failed)
                    return@post
                }
                cover = decoded
                render()
            }
        }
    }

    private fun render() {
        val photo = cover ?: return
        val job = ++generation
        val pitch = pitches[dots.selected]
        status.text = getString(R.string.studio_rendering)
        worker.execute {
            val bitmap = runCatching { DotRender.render(DotRender.grid(photo, pitch), screenW, screenH) }.getOrNull()
            main.post {
                if (job != generation) return@post
                if (bitmap == null) {
                    status.text = getString(R.string.studio_failed)
                    return@post
                }
                result = bitmap
                preview.setImageBitmap(bitmap)
                status.text = ""
                showControls(true)
            }
        }
    }

    private fun apply(which: Int) {
        val bitmap = result ?: return
        status.text = getString(R.string.studio_rendering)
        val context: Context = applicationContext
        worker.execute {
            val ok = runCatching { WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, which) }.isSuccess
            main.post { status.text = getString(if (ok) R.string.studio_done else R.string.studio_failed) }
        }
    }

    /** A frame that keeps a width / height ratio inside whatever space it gets. */
    private class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            val maxH = MeasureSpec.getSize(heightMeasureSpec)
            var w = maxW
            var h = (w / ratio).toInt()
            if (maxH in 1 until h) {
                h = maxH
                w = (h * ratio).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(maxW, h)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            // Centre the content horizontally.
            val inner = (measuredHeight * ratio).toInt().coerceAtMost(measuredWidth)
            val dx = (measuredWidth - inner) / 2
            for (i in 0 until childCount) getChildAt(i).layout(dx, 0, dx + inner, measuredHeight)
        }
    }

    private companion object {
        const val PICK = 1
    }
}
