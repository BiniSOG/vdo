package com.vdo.app.data

import org.json.JSONObject

enum class DlStatus { QUEUED, RUNNING, PAUSED, MERGING, COMPLETED, FAILED }

enum class DlKind { DIRECT, HLS, DASH }

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val kind: DlKind,
    val headers: Map<String, String> = emptyMap(),
    val status: DlStatus = DlStatus.QUEUED,
    val downloaded: Long = 0,
    val total: Long = -1,
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    val speed: Long = 0,
    val error: String? = null,
    /** content:// URI or absolute file path of the finished file. */
    val output: String? = null,
    val mime: String? = null,
    val isAudio: Boolean = false,
    val isPrivate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val maxHeight: Int = 0,
) {
    val active get() = status == DlStatus.RUNNING || status == DlStatus.MERGING
    val finished get() = status == DlStatus.COMPLETED

    /** 0..1, or -1 when unknown. */
    val fraction: Float
        get() = when {
            total > 0 -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            partsTotal > 0 -> (partsDone.toFloat() / partsTotal).coerceIn(0f, 1f)
            else -> -1f
        }

    fun toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("kind", kind.name)
        put("headers", JSONObject(headers)); put("status", status.name)
        put("downloaded", downloaded); put("total", total)
        put("partsDone", partsDone); put("partsTotal", partsTotal)
        put("error", error ?: JSONObject.NULL); put("output", output ?: JSONObject.NULL)
        put("mime", mime ?: JSONObject.NULL); put("isAudio", isAudio); put("isPrivate", isPrivate)
        put("createdAt", createdAt); put("maxHeight", maxHeight)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadTask {
            val h = o.optJSONObject("headers")
            val headers = HashMap<String, String>()
            h?.keys()?.forEach { headers[it] = h.optString(it) }
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
            return DownloadTask(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"),
                kind = runCatching { DlKind.valueOf(o.optString("kind")) }.getOrDefault(DlKind.DIRECT),
                headers = headers,
                status = runCatching { DlStatus.valueOf(o.optString("status")) }.getOrDefault(DlStatus.PAUSED),
                downloaded = o.optLong("downloaded"), total = o.optLong("total", -1),
                partsDone = o.optInt("partsDone"), partsTotal = o.optInt("partsTotal"),
                error = str("error"), output = str("output"), mime = str("mime"),
                isAudio = o.optBoolean("isAudio"), isPrivate = o.optBoolean("isPrivate"),
                createdAt = o.optLong("createdAt"), maxHeight = o.optInt("maxHeight"),
            )
        }
    }
}

/** Something the player can play. */
data class PlayItem(
    val uri: String,
    val title: String,
    val subtitle: String = "",
    val isAudio: Boolean = false,
    val mime: String? = null,
    val artwork: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("uri", uri); put("title", title); put("subtitle", subtitle); put("isAudio", isAudio)
        put("mime", mime ?: JSONObject.NULL); put("artwork", artwork ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlayItem(
            o.getString("uri"), o.optString("title"), o.optString("subtitle"), o.optBoolean("isAudio"),
            if (o.isNull("mime")) null else o.optString("mime"),
            if (o.isNull("artwork")) null else o.optString("artwork"),
        )
    }
}

/** A bookmark or browsing-history entry. */
data class WebEntry(val url: String, val title: String, val time: Long = System.currentTimeMillis()) {
    fun toJson() = JSONObject().apply { put("url", url); put("title", title); put("time", time) }

    companion object {
        fun fromJson(o: JSONObject) = WebEntry(o.getString("url"), o.optString("title"), o.optLong("time"))
    }
}
