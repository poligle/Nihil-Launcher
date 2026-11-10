package com.nihilshell.launcher.deck

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The icons the computer gives, on disk: the deck's apps and webs, and the launcher's webs.
 * A file each, by key, good for a week.
 */
object DeckCache {
    const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun dir(context: Context): File = File(context.cacheDir, "deck")

    fun file(context: Context, key: String): File = File(dir(context), sha(key))

    fun stale(context: Context, key: String): Boolean {
        val file = file(context, key)
        return !file.isFile || System.currentTimeMillis() - file.lastModified() > WEEK
    }

    fun keep(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val file = file(context, key)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
