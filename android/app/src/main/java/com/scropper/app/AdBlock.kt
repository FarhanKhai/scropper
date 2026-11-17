package com.scropper.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Loads and refreshes the ad-block engine (Brave's `adblock` engine in rust-core) with
 * uBlock Origin's filter lists. Lists ship in `assets/adblock/` and are refreshed from the
 * network at most once a day into `files/adblock/`. The compiled engine is cached on disk so
 * later launches skip parsing.
 */
object AdBlock {
    private const val TAG = "ScropperAdBlock"
    private const val RESOURCES = "resources.json"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val KEY_LAST_UPDATE = "last_update"

    private class FilterList(val file: String, val url: String, val trusted: Boolean)

    private const val UBO = "https://ublockorigin.github.io/uAssetsCDN/filters"
    private val LISTS = listOf(
        FilterList("ublock-filters.txt", "$UBO/filters.min.txt", trusted = true),
        FilterList("ublock-quick-fixes.txt", "$UBO/quick-fixes.min.txt", trusted = true),
        FilterList("ublock-privacy.txt", "$UBO/privacy.min.txt", trusted = true),
        FilterList(
            "peter-lowe.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            trusted = false,
        ),
    )

    @Volatile
    var ready = false
        private set

    @Volatile
    private var loaded = false
    private var starting = false
    private var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Call on the main thread. Loads the engine on a background thread (first call only), then
     * calls [onChanged] on the main thread: once when loading has finished, and again if a list
     * update replaced the engine. It also fires if loading failed, so callers never hang, and
     * right away if loading finished earlier. The latest [onChanged] replaces any previous one.
     */
    fun start(context: Context, onChanged: () -> Unit) {
        listener = onChanged
        if (starting) {
            if (loaded) main.post { listener?.invoke() }
            return
        }
        starting = true
        val app = context.applicationContext
        thread(name = "scropper-adblock", isDaemon = true) {
            runCatching { load(app) }.onFailure { Log.w(TAG, "engine load failed", it) }
            loaded = true
            main.post { listener?.invoke() }
            runCatching { if (updateLists(app)) main.post { listener?.invoke() } }
                .onFailure { Log.w(TAG, "list update failed", it) }
        }
    }

    fun clearListener() {
        listener = null
    }

    fun cosmeticScript(url: String): String = if (ready) RustCore.adblockCosmeticScript(url) else ""

    private fun dir(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }

    /** Cache is tied to the installed build: a new APK may ship a different engine format. */
    private fun cacheFile(context: Context): File {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        return File(dir(context), "engine-$stamp.dat")
    }

    private fun read(context: Context, name: String): ByteArray {
        val updated = File(dir(context), name)
        return if (updated.isFile) updated.readBytes() else context.assets.open("adblock/$name").use { it.readBytes() }
    }

    private fun load(context: Context) {
        val resources = read(context, RESOURCES)
        val cache = cacheFile(context)
        if (cache.isFile && RustCore.adblockLoadCache(cache.readBytes(), resources)) {
            ready = true
            return
        }
        build(context, resources)
    }

    private fun build(context: Context, resources: ByteArray) {
        val started = System.currentTimeMillis()
        fun joined(trusted: Boolean) = LISTS.filter { it.trusted == trusted }
            .joinToString("\n") { String(read(context, it.file), Charsets.UTF_8) }
            .toByteArray(Charsets.UTF_8)
        val serialized = RustCore.adblockLoadLists(joined(true), joined(false), resources)
        ready = true
        Log.i(TAG, "engine built in ${System.currentTimeMillis() - started} ms")
        val dir = dir(context)
        dir.listFiles { f -> f.name.startsWith("engine-") }?.forEach { it.delete() }
        if (serialized != null) cacheFile(context).writeBytes(serialized)
    }

    /** Returns `true` if fresh lists were downloaded and the engine was rebuilt. */
    private fun updateLists(context: Context): Boolean {
        val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE, 0) < UPDATE_INTERVAL_MS) return false
        val dir = dir(context)
        // All or nothing: a half-updated set of lists is never installed.
        val downloads = LISTS.map { list ->
            val tmp = File(dir, list.file + ".tmp")
            download(list.url, tmp)
            if (tmp.length() < 1024) error("suspiciously small list: ${list.file}")
            list to tmp
        }
        downloads.forEach { (list, tmp) -> tmp.renameTo(File(dir, list.file)) }
        prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
        build(context, read(context, RESOURCES))
        return true
    }

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode} for $url")
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }
}
