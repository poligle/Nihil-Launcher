package com.nihilshell.launcher.deck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import com.nihilshell.launcher.R
import com.nihilshell.launcher.TasksActivity
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object Vector {
    const val MAX = 100L * 1024 * 1024
    const val NOTIFICATION = 0x7EC
    const val FOLDER = "Vector"
    private const val CHANNEL = "vector"
    private const val WAIT = 50
    private const val TEXT = 256 * 1024
    private const val FRESH = 24 * 60 * 60L
    private val KINDS = setOf("text", "image", "video", "file")

    class Item(val id: String, val kind: String, val name: String, val mime: String, val from: String, val file: File) {
        val size: Long get() = file.length()

        fun text(limit: Int = TEXT): String = runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(minOf(file.length(), limit.toLong()).toInt())
                var read = 0
                while (read < bytes.size) {
                    val count = input.read(bytes, read, bytes.size - read)
                    if (count < 0) break
                    read += count
                }
                String(bytes, 0, read, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    class Payload(val name: String, val mime: String, val size: Long, val text: String = "", val uri: Uri? = null, val open: () -> InputStream?) {
        val kind: String
            get() = when {
                mime.startsWith("text/") -> "text"
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                else -> "file"
            }

        val label: String
            get() = if (text.isNotEmpty()) text.trim().lineSequence().firstOrNull().orEmpty() else name
    }

    enum class Outcome { SENT, EMPTY, BIG, UNREADABLE, UNPAIRED, UNREACHABLE, FORGOTTEN, OLDER, REFUSED }

    @Volatile
    var received: Item? = null
        private set

    @Volatile
    var sending = false
        private set

    @Volatile
    var watching = 0

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val pool = Executors.newSingleThreadExecutor()
    private val wake = Semaphore(0)
    private var thread: Thread? = null
    private var screen: BroadcastReceiver? = null
    private var loaded = false

    fun listen(listener: (String) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (String) -> Unit) {
        listeners -= listener
    }

    private fun emit(what: String) {
        main.post { for (listener in listeners) listener(what) }
    }

    private fun folder(context: Context) = File(context.cacheDir, "vector")

    private fun meta(context: Context) = File(folder(context), ".item.json")

    private fun prefs(context: Context) = context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE)

    private fun seen(context: Context) = prefs(context).getString("seen", "").orEmpty()

    private fun see(context: Context, id: String) = prefs(context).edit().putString("seen", id).apply()

    fun current(context: Context): Item? {
        load(context)
        return received?.takeIf { it.file.isFile }
    }

    fun waiting(context: Context): Item? =
        current(context)?.takeIf { it.id != prefs(context).getString("used", "") }

    fun use(context: Context) {
        val item = current(context) ?: return
        prefs(context).edit().putString("used", item.id).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        emit("used")
    }

    fun discard(context: Context) {
        synchronized(this) {
            current(context)?.file?.delete()
            meta(context).delete()
            received = null
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        emit("used")
    }

    fun save(context: Context): Boolean {
        val item = current(context) ?: return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime.substringBefore(';').trim().ifEmpty { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        val written = runCatching {
            resolver.openOutputStream(target)?.use { output -> item.file.inputStream().use { it.copyTo(output) } } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(target, null, null) }
            return false
        }
        runCatching { resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
        use(context)
        return true
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        received = runCatching {
            val json = JSONObject(meta(context).readText())
            val kind = json.getString("kind").takeIf { it in KINDS } ?: "file"
            val file = File(folder(context), name(json.getString("name"), kind))
            Item(json.getString("id"), kind, file.name, json.getString("mime"), json.optString("from"), file).takeIf { file.isFile }
        }.getOrNull()
    }

    private fun name(value: String, kind: String): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim().take(160)
        return if (name.isEmpty() || name.startsWith('.')) kind else name
    }

    fun uri(context: Context, item: Item): Uri =
        Uri.Builder().scheme("content").authority(context.packageName + ".vector").appendPath(item.id).appendPath(item.name).build()

    fun start(context: Context) {
        val app = context.applicationContext
        load(app)
        synchronized(this) {
            if (thread?.isAlive == true) {
                wake.release()
                return
            }
            if (DeckStore.get(app).computer == null) return
            if (screen == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = wake.release()
                }
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(receiver, filter)
                }
                screen = receiver
            }
            thread = Thread({ loop(app) }, "vector").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun pause(millis: Long) {
        wake.tryAcquire(millis, TimeUnit.MILLISECONDS)
        wake.drainPermits()
    }

    private fun loop(app: Context) {
        val power = app.getSystemService(PowerManager::class.java)
        val store = DeckStore.get(app)
        var failures = 0
        var missed = 0
        while (true) {
            val computer = store.computer ?: return
            val token = store.token() ?: return
            if (power?.isInteractive == false) {
                pause(15 * 60_000L)
                continue
            }
            val since = seen(app)
            val client = DeckClient(computer, token)
            val last = computer.last
            val reply = client.listen("/v1/vector?since=${Uri.encode(since)}&wait=$WAIT", (WAIT + 15) * 1000, app.takeIf { failures % 4 == 3 })
            if (computer.last != last) store.save(computer)
            val json = reply?.json
            when {
                reply == null -> {
                    failures++
                    pause(minOf(120_000L, 5_000L shl minOf(failures, 5)))
                }
                reply.status == 401 || reply.status == 404 -> pause(10 * 60_000L)
                reply.status != 200 || json == null -> {
                    failures++
                    pause(30_000L)
                }
                else -> {
                    failures = 0
                    val id = json.optString("id")
                    when {
                        id == since -> Unit
                        id.isEmpty() -> see(app, id)
                        fetch(app, client, computer, json) -> {
                            missed = 0
                            see(app, id)
                        }
                        ++missed >= 3 -> {
                            missed = 0
                            see(app, id)
                        }
                        else -> pause(30_000L)
                    }
                }
            }
        }
    }

    private fun fetch(app: Context, client: DeckClient, computer: Computer, json: JSONObject): Boolean {
        val id = json.optString("id")
        val kind = json.optString("kind").takeIf { it in KINDS } ?: "file"
        val mime = json.optString("mime").ifEmpty { "application/octet-stream" }
        val name = name(json.optString("name"), kind)
        if (json.optLong("size") > MAX) return false
        val folder = folder(app).apply { mkdirs() }
        val part = File(folder, ".part")
        emit("receiving")
        val reply = client.download("/v1/vector/data?id=${Uri.encode(id)}", part, MAX)
        if (reply?.status != 200) {
            part.delete()
            emit("missed")
            return false
        }
        val item = synchronized(this) {
            folder.listFiles()?.forEach { if (it != part) it.delete() }
            prefs(app).edit().remove("used").apply()
            val file = File(folder, name)
            if (!part.renameTo(file)) {
                part.delete()
                emit("missed")
                return false
            }
            val meta = JSONObject().put("id", id).put("kind", kind).put("name", name).put("mime", mime).put("from", computer.name)
            meta(app).writeText(meta.toString())
            Item(id, kind, name, mime, computer.name, file).also { received = it }
        }
        emit("received")
        val age = json.optLong("now") - json.optLong("time")
        if (watching == 0 && age in 0..FRESH) notify(app, item)
        return true
    }

    private fun notify(app: Context, item: Item) {
        val manager = channel(app) ?: return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(app, 0, Intent(app, TasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val copy = PendingIntent.getBroadcast(app, 0, Intent(app, VectorAction::class.java).setAction(VectorAction.COPY), flags)
        val save = PendingIntent.getBroadcast(app, 1, Intent(app, VectorAction::class.java).setAction(VectorAction.SAVE), flags)
        val text = if (item.kind == "text") item.text(4096) else item.name
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.mdi_swap)
            .setContentTitle(app.getString(R.string.vector_from, item.from))
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setContentIntent(open)
            .setAutoCancel(true)
        if (item.kind == "text" || item.kind == "image") {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(app, R.drawable.mdi_clipboard), app.getString(R.string.vector_copy), copy).build())
        }
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(app, R.drawable.mdi_download), app.getString(R.string.vector_save), save).build())
        when (item.kind) {
            "image" -> thumbnail(item.file, 512)?.let { builder.setLargeIcon(it).setStyle(Notification.BigPictureStyle().bigPicture(it)) }
            "text" -> builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager.notify(NOTIFICATION, builder.build())
    }

    fun notifies(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    private fun channel(app: Context): NotificationManager? {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return null
        if (!manager.areNotificationsEnabled()) return null
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.vector), NotificationManager.IMPORTANCE_DEFAULT))
        return manager
    }

    private fun notifySent(app: Context, computer: String, payload: Payload) {
        val manager = channel(app) ?: return
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.mdi_swap)
            .setContentTitle(app.getString(R.string.vector_sent, computer))
            .setContentText(payload.label)
            .setTimeoutAfter(5000)
            .setOnlyAlertOnce(true)
        manager.notify(NOTIFICATION + 1, builder.build())
    }

    fun preview(context: Context, payload: Payload, size: Int): Bitmap? {
        val uri = payload.uri ?: return null
        if (payload.kind != "image" && payload.kind != "video") return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
    }

    fun thumbnail(file: File, max: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        var sample = 1
        while (longest / (sample * 2) >= max) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
    }.getOrNull()

    fun copy(context: Context): Boolean {
        val item = current(context) ?: return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = if (item.kind == "text") {
            ClipData.newPlainText(item.from, item.text())
        } else {
            ClipData.newUri(context.contentResolver, item.name, uri(context, item))
        }
        clipboard.setPrimaryClip(clip)
        use(context)
        return true
    }

    fun forget(context: Context) {
        synchronized(this) {
            folder(context).deleteRecursively()
            received = null
            see(context, "")
            prefs(context).edit().remove("used").apply()
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        emit("received")
    }

    fun text(value: String): Payload {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Payload("", "text/plain; charset=utf-8", bytes.size.toLong(), value) { bytes.inputStream() }
    }

    fun payload(context: Context, uri: Uri): Payload? {
        if (uri.scheme != "content") return null
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                if (row.moveToFirst()) {
                    val named = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (named >= 0 && !row.isNull(named)) name = row.getString(named)
                    val sized = row.getColumnIndex(OpenableColumns.SIZE)
                    if (sized >= 0 && !row.isNull(sized)) size = row.getLong(sized)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        return Payload(name, mime, size, uri = uri) { resolver.openInputStream(uri) }
    }

    fun clipboard(context: Context): Payload? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) payload(context, uri)?.let { return it }
        val text = item.coerceToText(context)?.toString().orEmpty()
        return if (text.isEmpty()) null else text(text)
    }

    fun send(context: Context, payload: Payload, done: (Outcome, String) -> Unit) {
        val app = context.applicationContext
        val store = DeckStore.get(app)
        val computer = store.computer
        when {
            computer == null -> done(Outcome.UNPAIRED, "")
            payload.size > MAX -> done(Outcome.BIG, computer.name)
            else -> {
                sending = true
                emit("sending")
                pool.execute {
                    val part = File(app.cacheDir, "vector-out").apply { mkdirs() }.let { File.createTempFile("send", ".part", it) }
                    val outcome = runCatching { upload(app, store, computer, payload, part) }.getOrDefault(Outcome.UNREACHABLE)
                    part.delete()
                    sending = false
                    if (outcome == Outcome.SENT) notifySent(app, computer.name, payload)
                    emit(if (outcome == Outcome.SENT) "sent" else "failed")
                    main.post { done(outcome, computer.name) }
                }
            }
        }
    }

    private fun upload(app: Context, store: DeckStore, computer: Computer, payload: Payload, part: File): Outcome {
        val copied = try {
            payload.open()?.use { input -> part.outputStream().use { output -> DeckClient.copy(input, output, MAX) } }
        } catch (big: DeckClient.TooBig) {
            return Outcome.BIG
        } catch (failed: Exception) {
            return Outcome.UNREADABLE
        }
        if (copied == null || copied == 0L) return Outcome.EMPTY
        val last = computer.last
        val reply = DeckClient(computer, store.token()).upload("/v1/vector", payload.mime, payload.name, part, app)
        if (computer.last != last) store.save(computer)
        return when {
            reply == null -> Outcome.UNREACHABLE
            reply.status == 200 -> Outcome.SENT
            reply.status == 401 -> Outcome.FORGOTTEN
            reply.status == 404 -> Outcome.OLDER
            reply.status == 413 -> Outcome.BIG
            else -> Outcome.REFUSED
        }
    }

    fun say(context: Context, outcome: Outcome, computer: String): String = when (outcome) {
        Outcome.SENT -> context.getString(R.string.vector_sent, computer)
        Outcome.EMPTY -> context.getString(R.string.vector_nothing)
        Outcome.BIG -> context.getString(R.string.vector_big)
        Outcome.UNREADABLE -> context.getString(R.string.vector_unreadable)
        Outcome.UNPAIRED -> context.getString(R.string.vector_unpaired)
        Outcome.UNREACHABLE -> context.getString(R.string.deck_unreachable_status, computer)
        Outcome.FORGOTTEN -> context.getString(R.string.deck_forgotten, computer)
        Outcome.OLDER -> context.getString(R.string.deck_older, computer)
        Outcome.REFUSED -> context.getString(R.string.deck_refused, computer)
    }
}
