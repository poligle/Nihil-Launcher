package com.nihilshell.launcher.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.util.LruCache
import com.nihilshell.launcher.deck.DeckCache
import com.nihilshell.launcher.deck.DeckClient
import com.nihilshell.launcher.deck.DeckStore
import com.nihilshell.launcher.ui.Nihil
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors

class WebEntry(val url: String, title: String) : Entry {
    override val key: String = KEY + url
    override val label: String = title.trim().ifEmpty { WebAddress.title(url) }
    override val searchLabel: String = Fuzzy.normalize(label)
    val host: String = WebAddress.host(url)

    companion object {
        const val KEY = "web:"
    }
}

object WebAddress {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IN_TEXT = Regex("https?://\\S+")

    fun normalize(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val url = if (SCHEME.containsMatchIn(text)) text else "https://$text"
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host.orEmpty()
        if (scheme != "http" && scheme != "https") return null
        if (!host.contains('.') && !host.contains(':')) return null
        return url
    }

    fun host(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

    fun title(url: String): String = host(url).removePrefix("www.").ifEmpty { url }

    fun find(text: String): String? = IN_TEXT.find(text)?.value
}

object Webs {
    const val CHROME = "com.android.chrome"

    private val main = Handler(Looper.getMainLooper())
    private val painter = Executors.newSingleThreadExecutor()
    private val fetcher = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(96)
    private val waiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var fetching = false

    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }

    fun open(context: Context, web: WebEntry, options: Bundle?): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(web.url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName + ".web." + Integer.toHexString(web.url.hashCode()))
        return listOf(Intent(view).setPackage(CHROME), view).any { intent ->
            runCatching { context.startActivity(intent, options) }.isSuccess
        }
    }

    fun icon(context: Context, web: WebEntry, size: Int, style: IconStyle, ready: (Bitmap) -> Unit): Bitmap? {
        val cacheKey = "${web.url}|${web.label}|$style|$size"
        icons.get(cacheKey)?.let { return it }
        waiting[cacheKey]?.let {
            it += ready
            return null
        }
        waiting[cacheKey] = mutableListOf(ready)
        val app = context.applicationContext
        painter.execute {
            val bitmap = runCatching { IconRenderer.renderWeb(kept(app, web.url), web.label, size, style, Nihil.fonts(app).sans) }.getOrNull()
            main.post {
                val waiters = waiting.remove(cacheKey).orEmpty()
                if (bitmap != null) {
                    icons.put(cacheKey, bitmap)
                    waiters.forEach { it(bitmap) }
                }
            }
        }
        return null
    }

    fun clearIcons() = icons.evictAll()

    private fun kept(context: Context, url: String): Bitmap? {
        val bytes = DeckCache.file(context, WebEntry.KEY + url).takeIf { it.isFile }?.readBytes() ?: return null
        return if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun fetchIcons(context: Context, webs: List<WebEntry>) {
        val app = context.applicationContext
        val store = DeckStore.get(app)
        val computer = store.computer ?: return
        val wanted = webs.filter { DeckCache.stale(app, WebEntry.KEY + it.url) }
        if (fetching || wanted.isEmpty()) return
        fetching = true
        val token = store.token()
        fetcher.execute {
            var got = false
            for (web in wanted) {
                val reply = DeckClient(computer, token).call("GET", "/v1/favicon?url=" + URLEncoder.encode(web.url, "UTF-8")) ?: break
                if (reply.status == 401) break
                DeckCache.keep(app, WebEntry.KEY + web.url, if (reply.status == 200) reply.bytes else ByteArray(0))
                got = true
            }
            main.post {
                fetching = false
                if (got) {
                    icons.evictAll()
                    listeners.toList().forEach { it() }
                }
            }
        }
    }
}
