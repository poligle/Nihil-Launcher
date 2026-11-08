package com.nihilshell.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nihilshell.launcher.core.QrDecoder
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.ui.KeyButton
import com.nihilshell.launcher.ui.MATCH
import com.nihilshell.launcher.ui.Nihil
import com.nihilshell.launcher.ui.WRAP
import com.nihilshell.launcher.ui.dp
import com.nihilshell.launcher.ui.llp
import com.nihilshell.launcher.ui.lp
import com.nihilshell.launcher.ui.mono
import com.nihilshell.launcher.ui.rounded
import com.nihilshell.launcher.ui.text
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pairing: the camera reads the QR in the computer's Preferences, Phone, with
 * NIHIL's own reader (QrDecoder: no libraries, nothing leaves the phone),
 * and the phone pairs with the computer it names, trusting only its pin.
 */
class DeckScanActivity : Activity() {

    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var size = Size(1280, 720)
    private val reading = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        val root = FrameLayout(this).apply { setBackgroundColor(Nihil.VOID) }
        texture = TextureView(this)
        root.addView(texture, lp(MATCH, MATCH))

        // Where to put the code: a square with the desktop's hairline, in ink.
        val side = dp(248)
        root.addView(View(this).apply { background = rounded(0, dp(22f), dp(2), Nihil.INK) }, lp(side, side, Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(text(getString(R.string.deck_pair_title), 22f, Nihil.INK))
            addView(text(getString(R.string.deck_scan_hint), 13f, Nihil.DIM).apply { setLineSpacing(dp(2f), 1f) },
                llp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        root.addView(top, lp(MATCH, WRAP, Gravity.TOP))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(20))
        }
        status = mono(getString(R.string.deck_scan_waiting), 11f, Nihil.DIM).apply { gravity = Gravity.CENTER }
        bottom.addView(status, llp(MATCH, WRAP))
        bottom.addView(KeyButton(this, getString(android.R.string.cancel)).apply { setOnClickListener { finish() } },
            llp(WRAP, WRAP).apply { topMargin = dp(14) })
        root.addView(bottom, lp(MATCH, WRAP, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(20))
            bottom.setPadding(dp(24) + bars.left, dp(16), dp(24) + bars.right, dp(20) + bars.bottom)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onPause() {
        stop()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else say(getString(R.string.deck_camera_denied))
    }

    // --- The camera --------------------------------------------------------------

    private fun start() {
        if (done || thread != null) return
        thread = HandlerThread("deck-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        if (texture.isAvailable) {
            open()
        } else {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = open()

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fit()

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun open() {
        val manager = getSystemService(CameraManager::class.java) ?: return say(getString(R.string.deck_camera_none))
        val id = runCatching {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return say(getString(R.string.deck_camera_none))
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // Up to 1080p: enough modules per pixel for a code across a third of the frame, and quick to read.
        size = sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: size
        val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        images.setOnImageAvailableListener({ frame(it) }, handler)
        reader = images
        fit()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    preview(device, images)
                }

                override fun onDisconnected(device: CameraDevice) = device.close()

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    runOnUiThread { say(getString(R.string.deck_camera_none)) }
                }
            }, handler)
        }.onFailure { say(getString(R.string.deck_camera_none)) }
    }

    private fun preview(device: CameraDevice, images: ImageReader) {
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        val callbacks = handler ?: return
        val executor = Executor { callbacks.post(it) }
        val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(images.surface))
        runCatching {
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            addTarget(images.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        runCatching { configured.setRepeatingRequest(request.build(), null, callbacks) }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        runOnUiThread { say(getString(R.string.deck_camera_none)) }
                    }
                }))
        }
    }

    /** The preview filling the screen without stretching: the frames are landscape, the screen portrait. */
    private fun fit() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w == 0f || h == 0f) return
        val frame = size.height.toFloat() / size.width
        val view = w / h
        val matrix = Matrix()
        if (view < frame) matrix.setScale(h * frame / w, 1f, w / 2, h / 2) else matrix.setScale(1f, w / frame / h, w / 2, h / 2)
        texture.setTransform(matrix)
    }

    /** Each frame's luminance to the reader, one at a time: the ones that come meanwhile are dropped. */
    private fun frame(images: ImageReader) {
        val image = runCatching { images.acquireLatestImage() }.getOrNull() ?: return
        if (done || !reading.compareAndSet(false, true)) {
            image.close()
            return
        }
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val lum = ByteArray(w * h)
        runCatching {
            for (y in 0 until h) {
                buffer.position(y * plane.rowStride)
                buffer.get(lum, y * w, w)
            }
        }
        image.close()
        worker.execute {
            val text = runCatching { QrDecoder.decode(lum, w, h) }.getOrNull()
            reading.set(false)
            if (text != null && !done) runOnUiThread { found(text) }
        }
    }

    private fun stop() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        thread?.quitSafely()
        session = null
        camera = null
        reader = null
        thread = null
        handler = null
    }

    // --- Pairing ----------------------------------------------------------------

    private fun found(text: String) {
        val invitation = DeckClient.Invitation.parse(text)
        if (invitation == null) {
            say(getString(R.string.deck_not_qr))
            return
        }
        done = true
        stop()
        say(getString(R.string.deck_pairing, invitation.name))
        val device = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL
        worker.execute {
            val result = DeckClient.pair(invitation, device)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (result) {
                    is DeckClient.Pairing.Paired -> {
                        DeckStore.get(this).save(result.computer, result.token)
                        startActivity(Intent(this, DeckActivity::class.java).putExtra(DeckActivity.EXTRA_PAIRED, true))
                        finish()
                    }
                    is DeckClient.Pairing.Failed -> {
                        say(getString(when (result.reason) {
                            "wrong" -> R.string.deck_error_wrong
                            "closed", "expired" -> R.string.deck_error_expired
                            "wait" -> R.string.deck_error_wait
                            else -> R.string.deck_error_unreachable
                        }, invitation.name))
                        // Another look: a new QR, or the same one once the computer answers.
                        done = false
                        start()
                    }
                }
            }
        }
    }

    private fun say(message: String) {
        status.text = message
    }

    private companion object {
        const val CAMERA_REQUEST = 4
    }
}
