package com.nihilshell.launcher.ui

import android.annotation.TargetApi
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.nihilshell.launcher.core.Prefs
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Smoked glass (Settings, Smoked glass), as on the desktop (the shell's shaders/surface.frag):
 * the frame and every surface as dark glass. Behind it is the wallpaper: the one NIHIL put on
 * (the studio's render or its night, kept here when it sets one), through 30 % of smoke for
 * the frame and 38 % for a card or a key. On Android 13 and later (AGSL) the bevel is clear
 * and bends it outwards, its colours a little apart where it bends most; the face beyond is
 * frosted and a little uneven, so the dots come through soft and their light spreads past
 * them; the edge glows where a bright dot is near it; a glint where the light from the top
 * left catches the edge. The frost reads the wallpaper blurred from a chain of smaller copies
 * of it, made once as it loads, as the desktop reads its mipmaps. Light surfaces (ink, red)
 * frost more, so what is on them keeps its contrast; a hairline (LINE) is the glass's own
 * edge, and a border in any other colour says something and stays.
 *
 * With a wallpaper NIHIL didn't put on, there is nothing to bend: the glass is the smoke and
 * the glint over the real wallpaper, which shows through the window. On Android 12 it is the
 * wallpaper frosted, through the smoke, flat. Screens that don't show the wallpaper
 * (Settings, the deck, the studio) are black behind: their glass is the smoke and the glint.
 */
object Glass {

    @Volatile
    var enabled = false
        private set

    /** The wallpaper behind, when it is one NIHIL put on. */
    @Volatile
    var backdrop: Bitmap? = null
        private set

    // The wallpaper ever smaller, each half the last (index 1 is half the size): the frost's
    // blurs, as mipmaps are on the desktop.
    @Volatile
    private var levels: List<Bitmap> = emptyList()

    private const val FILE = "glass-backdrop.png"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loadedId = Int.MIN_VALUE

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * The choice in Prefs, and the wallpaper behind if it is still the one NIHIL put on;
     * [changed] when that is known, to draw again.
     */
    fun start(context: Context, changed: () -> Unit) {
        val app = context.applicationContext
        enabled = Prefs.get(app).glass
        if (!enabled) {
            backdrop = null
            loadedId = Int.MIN_VALUE
            return
        }
        io.execute {
            val wanted = Prefs.get(app).glassWallpaperId
            val current = runCatching {
                WallpaperManager.getInstance(app).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            }.getOrDefault(-1)
            val bitmap = when {
                wanted < 0 || wanted != current -> null
                loadedId == current && backdrop != null -> backdrop
                else -> runCatching { BitmapFactory.decodeFile(file(app).path) }.getOrNull()
            }
            val chain = when {
                bitmap == null -> emptyList()
                bitmap === backdrop -> levels
                else -> halves(bitmap)
            }
            main.post {
                loadedId = if (bitmap != null) current else Int.MIN_VALUE
                if (bitmap !== backdrop) {
                    backdrop = bitmap
                    levels = chain
                    shader = null
                    levelShaders.clear()
                }
                changed()
            }
        }
    }

    /**
     * NIHIL has just put [bitmap] on as the home screen's wallpaper ([id], as setBitmap
     * answered): kept, for the glass to see. Off the main thread.
     */
    fun remember(context: Context, bitmap: Bitmap, id: Int) {
        val app = context.applicationContext
        val saved = runCatching {
            file(app).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        Prefs.get(app).glassWallpaperId = if (saved && id > 0) id else -1
    }

    /** Each half the size of the last, filtered, down to a few pixels: index 0 is [bitmap]. */
    private fun halves(bitmap: Bitmap): List<Bitmap> {
        val chain = mutableListOf(bitmap)
        var last = bitmap
        while (chain.size < 8 && last.width > 8 && last.height > 8) {
            last = runCatching {
                Bitmap.createScaledBitmap(last, max(1, last.width / 2), max(1, last.height / 2), true)
            }.getOrNull() ?: break
            chain += last
        }
        return chain
    }

    // The wallpaper as a shader in screen pixels: scaled to cover the screen and centred,
    // as the system shows it (the launcher keeps its offsets in the middle).
    private var shader: BitmapShader? = null
    private var shaderFor = 0L
    private val levelShaders = HashMap<Int, BitmapShader>()

    private fun screenKey(context: Context): Pair<Long, android.graphics.Rect> {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return (bounds.width().toLong() shl 32 or bounds.height().toLong()) to bounds
    }

    // [bitmap], a copy of the wallpaper of any size, laid where the wallpaper is.
    private fun cover(bitmap: Bitmap, full: Bitmap, bounds: android.graphics.Rect): BitmapShader {
        val scale = max(bounds.width() / full.width.toFloat(), bounds.height() / full.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale * full.width / bitmap.width, scale * full.height / bitmap.height)
            postTranslate((bounds.width() - full.width * scale) / 2f, (bounds.height() - full.height * scale) / 2f)
        }
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
            if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }

    private fun backdropShader(context: Context): BitmapShader? {
        val bitmap = backdrop ?: return null
        val (key, bounds) = screenKey(context)
        shader?.let { if (shaderFor == key) return it }
        levelShaders.clear()
        return cover(bitmap, bitmap, bounds).also {
            shader = it
            shaderFor = key
        }
    }

    // The copy of the wallpaper whose pixel spans about [px] screen pixels, as the mipmap
    // level the desktop would read for that blur: a level's pixel spans 2^level of the
    // wallpaper's, and each of those `onScreen` of the screen's.
    private fun levelFor(context: Context, px: Float): Int {
        val bitmap = backdrop ?: return 0
        val (_, bounds) = screenKey(context)
        val onScreen = max(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
        return Math.round(log2(max(px / onScreen, 1f))).coerceIn(0, max(0, levels.size - 1))
    }

    /**
     * The wallpaper blurred by about [px] screen pixels: the copy that many times smaller, or
     * the wallpaper itself when there is no chain.
     */
    private fun blurShader(context: Context, px: Float): BitmapShader? {
        val full = backdropShader(context) ?: return null
        val bitmap = backdrop ?: return null
        val level = levelFor(context, px)
        val chain = levels
        if (level == 0 || level >= chain.size) return full
        val (_, bounds) = screenKey(context)
        return levelShaders.getOrPut(level) { cover(chain[level], bitmap, bounds) }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flatMatrix = Matrix()

    /** Whether [view]'s window shows the wallpaper behind it (the home screen does). */
    fun seesWallpaper(view: View): Boolean {
        val params = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return false
        return params.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0
    }

    /**
     * Glass over [shape]: a rounded box ([box], [radius]) or, with [frame], what is outside
     * it. [screenX], [screenY]: where the canvas's origin is on the screen. [pane]: the
     * frame's thicker glass. [wallpaper]: whether the wallpaper is what is behind.
     */
    fun paint(
        canvas: Canvas,
        context: Context,
        shape: Path,
        box: RectF,
        radius: Float,
        tint: Int,
        frame: Boolean,
        screenX: Float,
        screenY: Float,
        pane: Boolean,
        wallpaper: Boolean = true,
    ) {
        val density = context.resources.displayMetrics.density
        val through = if (pane) 0.3f else 0.38f
        val lightTint = luma(tint) > 0.35f
        val behind = if (wallpaper) backdropShader(context) else null
        fill.colorFilter = null
        // As the desktop's components/Surface.qml has them, in dp: a key is glass over glass,
        // its frost a little more.
        val dp = radius / density
        val frost = if (pane) 5f else 6f
        val bevel = if (pane) 26f else min(14f, 4f + dp)
        val bend = if (behind != null && Build.VERSION.SDK_INT >= 33) Bend.effect else null
        if (bend != null && behind != null && Build.VERSION.SDK_INT >= 33) {
            bend.setInputShader("backdrop", behind)
            // The blurs the frost reads: each tap a little blurred, the light spread wide, and
            // the light near the edge against that of a wider ring round it.
            bend.setInputShader("frostMip", blurShader(context, frost * density * 0.5f) ?: behind)
            bend.setInputShader("wideMip", blurShader(context, frost * 2.2f * density) ?: behind)
            bend.setInputShader("nearMip", blurShader(context, 10f * density) ?: behind)
            bend.setInputShader("aroundMip", blurShader(context, 28f * density) ?: behind)
            bend.setFloatUniform("screenAt", screenX, screenY)
            bend.setFloatUniform("boxAt", box.left, box.top)
            bend.setFloatUniform("boxSize", box.width(), box.height())
            bend.setFloatUniform("radius", radius)
            bend.setFloatUniform("through", through)
            bend.setFloatUniform("refraction", density * if (pane) 14f else min(6f, 2f + dp / 3f))
            bend.setFloatUniform("bevel", density * bevel)
            bend.setFloatUniform("rim", if (pane) 0.45f else 0.32f)
            bend.setFloatUniform("density", density)
            bend.setFloatUniform("frame", if (frame) 1f else 0f)
            bend.setFloatUniform("frost", density * frost)
            bend.setFloatUniform("scatter", if (pane) 0.4f else 0.25f)
            bend.setFloatUniform("halo", if (pane) 2.2f else 0.9f)
            bend.setFloatUniform("wobble", density * if (pane) 3f else 1.5f)
            // A narrow bevel (a key's) is frosted too, or its dots would ring it like stitches.
            bend.setFloatUniform("clarity", smoothstep(10f, 24f, bevel))
            bend.setFloatUniform(
                "tint",
                ((tint shr 16) and 0xFF) / 255f,
                ((tint shr 8) and 0xFF) / 255f,
                (tint and 0xFF) / 255f,
                ((tint ushr 24) and 0xFF) / 255f,
            )
            fill.shader = bend
            canvas.drawPath(shape, fill)
            fill.shader = null
            return
        }
        val bitmap = backdrop
        if (behind != null && bitmap != null && !lightTint) {
            // Android 12: the wallpaper frosted (a smaller copy of it), through the smoke, flat.
            flatMatrix.reset()
            flatMatrix.postTranslate(-screenX, -screenY)
            val frosted = blurShader(context, frost * density) ?: behind
            val local = Matrix()
            frosted.getLocalMatrix(local)
            local.postConcat(flatMatrix)
            val flat = BitmapShader(frostBitmap(context, frost * density) ?: bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            flat.setLocalMatrix(local)
            fill.shader = flat
            fill.colorFilter = smoke(tint, through)
            canvas.drawPath(shape, fill)
            fill.shader = null
            fill.colorFilter = null
        } else {
            // The smoke over the real wallpaper, which the window lets through.
            fill.shader = null
            fill.color = withAlpha(tint, (1f - if (lightTint) 0.1f else through) * ((tint ushr 24) / 255f))
            canvas.drawPath(shape, fill)
        }
        if (!frame) glint(canvas, context, box, radius, pane)
    }

    /** The glint without the bend: the top left edge lit, fading to nothing at the bottom right. */
    fun glint(canvas: Canvas, context: Context, box: RectF, radius: Float, pane: Boolean) {
        val density = context.resources.displayMetrics.density
        glint.strokeWidth = density
        glint.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            withAlpha(Nihil.INK, if (pane) 0.32f else 0.24f), 0x00000000, Shader.TileMode.CLAMP,
        )
        val inset = density / 2f
        canvas.drawRoundRect(
            box.left + inset, box.top + inset, box.right - inset, box.bottom - inset,
            max(0f, radius - inset), max(0f, radius - inset), glint,
        )
    }

    /** A border that says something (focus, the chosen one): drawn as it was, over the glass. */
    fun border(canvas: Canvas, box: RectF, radius: Float, width: Int, color: Int) {
        if (width <= 0 || color == Nihil.LINE || (color ushr 24) == 0) return
        edge.strokeWidth = width.toFloat()
        edge.color = color
        val inset = width / 2f
        canvas.drawRoundRect(
            box.left + inset, box.top + inset, box.right - inset, box.bottom - inset,
            max(0f, radius - inset), max(0f, radius - inset), edge,
        )
    }

    private fun smoke(tint: Int, through: Float): ColorMatrixColorFilter {
        val r = ((tint shr 16) and 0xFF) * (1f - through)
        val g = ((tint shr 8) and 0xFF) * (1f - through)
        val b = (tint and 0xFF) * (1f - through)
        return ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    through, 0f, 0f, 0f, r,
                    0f, through, 0f, 0f, g,
                    0f, 0f, through, 0f, b,
                    0f, 0f, 0f, (tint ushr 24) / 255f, 0f,
                ),
            ),
        )
    }

    // The copy of the wallpaper blurShader reads for a blur of [px].
    private fun frostBitmap(context: Context, px: Float): Bitmap? {
        val bitmap = backdrop ?: return null
        return levels.getOrNull(levelFor(context, px)) ?: bitmap
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun luma(color: Int): Float =
        (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f
}

/**
 * The surfaces of rounded(): a GradientDrawable as always, and in smoked glass the glass
 * (Glass). It finds the view it is drawn for through its callbacks (a ripple holds most of
 * them), and draws again when that view moves on the screen, so what is behind keeps up.
 */
class GlassDrawable : GradientDrawable() {

    private var strokeWidth = 0
    private var strokeColor = 0
    private val box = RectF()
    private val shape = Path()
    private val at = IntArray(2)
    private var watched: View? = null
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    private val moved = ViewTreeObserver.OnPreDrawListener {
        watched?.let { view ->
            view.getLocationOnScreen(at)
            if (at[0] != lastX || at[1] != lastY) invalidateSelf()
        }
        true
    }

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.viewTreeObserver.addOnPreDrawListener(moved)
        }

        override fun onViewDetachedFromWindow(view: View) {
            view.viewTreeObserver.removeOnPreDrawListener(moved)
        }
    }

    override fun setStroke(width: Int, color: Int) {
        strokeWidth = width
        strokeColor = color
        super.setStroke(width, color)
    }

    private fun host(): View? {
        var current: Any? = this.callback
        while (current is android.graphics.drawable.Drawable) current = current.callback
        return current as? View
    }

    private fun watch(view: View) {
        if (watched === view) return
        watched?.let {
            it.removeOnAttachStateChangeListener(attachment)
            it.viewTreeObserver.removeOnPreDrawListener(moved)
        }
        watched = view
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) view.viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun draw(canvas: Canvas) {
        val view = if (Glass.enabled) host() else null
        if (view == null) {
            super.draw(canvas)
            return
        }
        watch(view)
        view.getLocationOnScreen(at)
        lastX = at[0]
        lastY = at[1]
        val tint = color?.defaultColor ?: 0
        if ((tint ushr 24) == 0 && strokeWidth == 0) return
        box.set(bounds)
        val radius = min(cornerRadius, min(box.width(), box.height()) / 2f)
        if ((tint ushr 24) != 0) {
            shape.reset()
            shape.addRoundRect(box, radius, radius, Path.Direction.CW)
            Glass.paint(
                canvas, view.context, shape, box, radius, tint, false,
                at[0].toFloat(), at[1].toFloat(), false, Glass.seesWallpaper(view),
            )
        } else {
            // A border with nothing inside: the glass's edge alone.
            Glass.glint(canvas, view.context, box, radius, false)
        }
        Glass.border(canvas, box, radius, strokeWidth, strokeColor)
    }
}

/**
 * The bend (Android 13 and later): Glass's AGSL, the glass of the desktop's
 * shaders/surface.frag, and for the frame the outside of the card's rounded rectangle. The
 * desktop reads its blurs from mipmaps; here they are smaller copies of the wallpaper
 * (frostMip and the rest, chosen by Glass for the blur each wants). One effect for every
 * surface: each draw sets its uniforms, and a draw keeps what they were when it was
 * recorded. Null where AGSL won't compile: then the glass is flat.
 */
@TargetApi(33)
private object Bend {
    private const val AGSL = """
        uniform shader backdrop;
        uniform shader frostMip;
        uniform shader wideMip;
        uniform shader nearMip;
        uniform shader aroundMip;
        uniform float2 screenAt;
        uniform float2 boxAt;
        uniform float2 boxSize;
        uniform float radius;
        uniform float through;
        uniform float refraction;
        uniform float bevel;
        uniform float rim;
        uniform float density;
        uniform float frame;
        uniform float frost;
        uniform float scatter;
        uniform float halo;
        uniform float wobble;
        uniform float clarity;
        uniform float4 tint;

        float roundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        float field(float2 p) {
            float2 h = boxSize * 0.5;
            float d = roundBox(p - boxAt - h, h, min(radius, min(h.x, h.y)));
            return frame > 0.5 ? -d : d;
        }

        // The desktop's hash and value noise, for the same grain and unevenness.
        float hash(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
                       mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
        }

        half4 main(float2 coord) {
            float d = field(coord);
            float cover = clamp(0.5 - d, 0.0, 1.0);
            if (cover <= 0.0) {
                return half4(0.0);
            }
            float2 e = float2(0.5, 0.0);
            float2 n = float2(field(coord + e.xy) - field(coord - e.xy), field(coord + e.yx) - field(coord - e.yx));
            n = n / max(length(n), 0.0001);
            float depth = max(-d, 0.0);
            float2 screen = screenAt + coord;

            // The bevel bends what is behind outwards, most at the edge.
            float k = clamp(1.0 - depth / max(bevel, 1.0), 0.0, 1.0);
            k = k * k;
            float2 bend = n * (refraction * k);

            // The face beyond it is uneven (in dp, as on the desktop).
            float2 q = (coord - boxAt) / (34.0 * density);
            float2 w = float2(noise(q), noise(q + float2(19.7, 7.3)))
                + 0.5 * float2(noise(q * 2.7 + float2(5.1, 13.9)), noise(q * 2.7 + float2(23.4, 2.2)));
            w = w / 1.5 * 2.0 - 1.0;
            float2 at = screen + bend + w * wobble * (1.0 - k);

            // Clear on the bevel, its colours a little apart where it bends most; frosted beyond.
            float3 clear = float3(0.0);
            if (k > 0.01) {
                float2 apart = bend * 0.14;
                clear = float3(float(backdrop.eval(at - apart).r), float(backdrop.eval(at).g), float(backdrop.eval(at + apart).b));
            } else {
                clear = float3(backdrop.eval(at).rgb);
            }
            float grain = hash(floor(coord)) * 6.2831853;
            float r = frost * (0.75 + 0.5 * noise(q * 1.7 + float2(41.0, 3.0)));
            float3 frosted = float3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 2.3999632 + grain;
                float s = r * sqrt((float(i) + 0.5) / 8.0);
                frosted += float3(frostMip.eval(at + float2(cos(a), sin(a)) * s).rgb);
            }
            frosted *= 0.125;
            float3 behind = mix(frosted, clear, smoothstep(0.08, 0.7, k) * clarity);

            // The light the frost spreads wide.
            float3 wide = float3(0.0);
            for (int i = 0; i < 4; i++) {
                float a = float(i) * 1.5707963 + 0.7853982 + grain * 0.15;
                wide += float3(wideMip.eval(at + float2(cos(a), sin(a)) * (frost * 1.6)).rgb);
            }
            wide *= 0.25;

            float luma = dot(tint.rgb, float3(0.2126, 0.7152, 0.0722));
            float t = mix(through, 0.1, smoothstep(0.15, 0.6, luma));
            float3 col = mix(tint.rgb, behind, t);
            col += wide * scatter * (0.35 + t);

            // The edge gathers the light of bright points near it.
            float edge = exp(-depth / (2.5 * density));
            float2 past = screen + n * (3.0 * density + depth);
            float3 glow = float3(nearMip.eval(past).rgb) - float3(aroundMip.eval(past).rgb);
            col += max(glow, float3(0.0)) * (halo * edge);

            float band = clamp(1.0 - depth / (2.5 * density), 0.0, 1.0);
            float lit = pow(max(dot(n, float2(-0.7071, -0.7071)), 0.0), 4.0);
            float away = pow(max(dot(n, float2(0.7071, 0.7071)), 0.0), 2.0);
            col = col + float3(rim * lit * band);
            col = col * (1.0 - 0.3 * away * band);
            col = col + float3(0.03 * k);
            col = max(col + (hash(screen) - 0.5) / 255.0, float3(0.0));
            float alpha = cover * tint.a;
            return half4(float4(col * alpha, alpha));
        }
    """

    val effect: RuntimeShader? by lazy { runCatching { RuntimeShader(AGSL) }.getOrNull() }
}
