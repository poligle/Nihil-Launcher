package com.nihilshell.launcher.deck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nihilshell.launcher.R
import com.nihilshell.launcher.TasksActivity

class VectorAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            COPY -> if (Vector.copy(app) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(app, R.string.vector_copied, Toast.LENGTH_SHORT).show()
            }
            SAVE -> {
                if (!Vector.canSave(app)) {
                    app.startActivity(
                        Intent(app, TasksActivity::class.java)
                            .putExtra(TasksActivity.EXTRA_SAVE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
                val pending = goAsync()
                Thread({
                    val saved = Vector.save(app)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, if (saved) R.string.vector_saved else R.string.vector_no_save, Toast.LENGTH_SHORT).show()
                        pending.finish()
                    }
                }, "vector-save").start()
            }
            SHARED -> Vector.use(app)
        }
    }

    companion object {
        const val COPY = "com.nihilshell.launcher.vector.COPY"
        const val SAVE = "com.nihilshell.launcher.vector.SAVE"
        const val SHARED = "com.nihilshell.launcher.vector.SHARED"
    }
}
