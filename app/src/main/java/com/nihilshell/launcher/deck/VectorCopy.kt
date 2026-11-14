package com.nihilshell.launcher.deck

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.nihilshell.launcher.R

class VectorCopy : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(NotificationManager::class.java)?.cancel(Vector.NOTIFICATION)
        if (Vector.copy(context) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.vector_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
