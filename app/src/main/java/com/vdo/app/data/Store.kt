package com.vdo.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Small preference-backed store for settings, bookmarks, history and playback positions. */
object Store {
    private lateinit var sp: SharedPreferences

    val bookmarks = MutableStateFlow<List<WebEntry>>(emptyList())
    val history = MutableStateFlow<List<WebEntry>>(emptyList())
    val recents = MutableStateFlow<List<PlayItem>>(emptyList())

    // Player
    var resumePlayback by bool("resume_playback", true)
    var backgroundVideo by bool("background_video", false)
    var autoPip by bool("auto_pip", true)
    var seekSeconds by int("seek_seconds", 10)
    var gestures by bool("gestures", true)

    // Library
    var videoGrid by bool("video_grid", true)

    // Downloads
    var maxConcurrent by int("max_concurrent", 3)
    var threads by int("threads", 6)
    var wifiOnly by bool("wifi_only", false)
    var notifyDone by bool("notify_done", true)

    // Browser
    var searchEngine by string("search_engine", "https://www.google.com/search?q=")
    var adBlock by bool("ad_block", true)
    var desktopMode by bool("desktop_mode", false)
    var saveHistory by bool("save_history", true)
    var homePage by string("home_page", "https://www.google.com")

    private var pinHash by string("pin_hash", "")

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("vdo", Context.MODE_PRIVATE)
        bookmarks.value = readList("bookmarks", WebEntry::fromJson)
        history.value = readList("history", WebEntry::fromJson)
        recents.value = readList("recents", PlayItem::fromJson)
    }

    // --- Private folder PIN -------------------------------------------------
    val hasPin get() = pinHash.isNotEmpty()
    fun setPin(pin: String) { pinHash = if (pin.isEmpty()) "" else hash(pin) }
    fun checkPin(pin: String) = pinHash == hash(pin)
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256")
        .digest("vdo:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    // --- Playback positions -------------------------------------------------
    fun position(id: String): Long = sp.getLong("pos_" + id.hashCode(), 0)
    fun setPosition(id: String, ms: Long) = sp.edit().putLong("pos_" + id.hashCode(), ms).apply()

    /** Remembers position and length together so lists can draw a "watched" bar. */
    fun setProgress(id: String, ms: Long, durationMs: Long) {
        val e = sp.edit().putLong("pos_" + id.hashCode(), ms)
        if (durationMs > 0) e.putLong("dur_" + id.hashCode(), durationMs)
        e.apply()
    }

    /** How much of an item has been watched, 0..1 (0 when unknown or finished). */
    fun watched(id: String): Float {
        if (!::sp.isInitialized) return 0f
        val d = sp.getLong("dur_" + id.hashCode(), 0)
        return if (d <= 0) 0f else (position(id).toFloat() / d).coerceIn(0f, 1f)
    }

    // --- Lists --------------------------------------------------------------
    fun addRecent(item: PlayItem) {
        recents.value = (listOf(item) + recents.value.filter { it.uri != item.uri }).take(20)
        writeList("recents", recents.value.map { it.toJson() })
    }

    fun clearRecents() { recents.value = emptyList(); writeList("recents", emptyList()) }

    fun isBookmarked(url: String) = bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        bookmarks.value = if (isBookmarked(url)) bookmarks.value.filter { it.url != url }
        else listOf(WebEntry(url, title.ifBlank { url })) + bookmarks.value
        writeList("bookmarks", bookmarks.value.map { it.toJson() })
    }

    fun addHistory(url: String, title: String) {
        if (!saveHistory || url.isBlank() || url == "about:blank") return
        history.value = (listOf(WebEntry(url, title.ifBlank { url })) + history.value.filter { it.url != url }).take(300)
        writeList("history", history.value.map { it.toJson() })
    }

    fun clearHistory() { history.value = emptyList(); writeList("history", emptyList()) }

    // --- Raw JSON blobs (used by the download manager) -----------------------
    fun readJson(key: String): String? = sp.getString(key, null)
    fun writeJson(key: String, value: String) = sp.edit().putString(key, value).apply()

    private fun <T> readList(key: String, f: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        (0 until arr.length()).map { f(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeList(key: String, items: List<JSONObject>) =
        sp.edit().putString(key, JSONArray(items).toString()).apply()

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    private fun string(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }
}
