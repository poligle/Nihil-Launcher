package com.nihilshell.launcher.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** The rear flash as a torch. CameraManager.setTorchMode needs no permission. */
object Torch {
    private var manager: CameraManager? = null
    private var cameraId: String? = null
    private val listeners = LinkedHashSet<(Boolean) -> Unit>()

    var on = false
        private set

    val available: Boolean get() = cameraId != null

    fun init(context: Context) {
        if (manager != null) return
        val cm = context.applicationContext.getSystemService(CameraManager::class.java) ?: return
        manager = cm
        cameraId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
        cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun listen(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    /** Switches the torch; false when there is no flash or the camera is busy. */
    fun toggle(): Boolean {
        val id = cameraId ?: return false
        return runCatching { manager?.setTorchMode(id, !on) }.isSuccess
    }

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            on = enabled
            listeners.toList().forEach { it(enabled) }
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            on = false
            listeners.toList().forEach { it(false) }
        }
    }
}
