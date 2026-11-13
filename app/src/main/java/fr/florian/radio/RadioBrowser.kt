package fr.florian.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val favicon: String?,
    val country: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
) {
    val subtitle: String
        get() = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            tags.takeIf { it.isNotBlank() },
            if (bitrate > 0) "$bitrate kbps" else codec.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    /** Même format que l'API, pour être relu par [RadioBrowser.parse] (favoris). */
    fun toJson(): JSONObject = JSONObject()
        .put("stationuuid", uuid)
        .put("name", name)
        .put("url_resolved", url)
        .put("favicon", favicon ?: "")
        .put("countrycode", country)
        .put("tags", tags)
        .put("codec", codec)
        .put("bitrate", bitrate)
}

/** Client minimal pour https://api.radio-browser.info */
object RadioBrowser {
    private val hosts = listOf(
        "all.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "at1.api.radio-browser.info",
    )

    fun popular(countryCode: String): List<Station> =
        parse(get("/json/stations/search?countrycode=$countryCode&order=clickcount&reverse=true&limit=100&hidebroken=true"))

    fun search(query: String): List<Station> {
        val q = URLEncoder.encode(query, "UTF-8")
        return parse(get("/json/stations/search?name=$q&order=clickcount&reverse=true&limit=100&hidebroken=true"))
    }

    /** Signale l'écoute à Radio Browser (demandé par l'API pour le classement). */
    fun countClick(uuid: String) {
        runCatching { get("/json/url/$uuid") }
    }

    /** Télécharge un logo réduit à [size] px max ; null si format non décodable (svg, ico…). */
    fun logo(url: String, size: Int = 144): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "SimpleRadio/1.0")
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        try {
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            val scale = size.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale >= 1f) return bmp
            return Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): String {
        var last: Exception? = null
        for (host in hosts) {
            try {
                val conn = URL("https://$host$path").openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "SimpleRadio/1.0")
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                try {
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Aucun serveur Radio Browser joignable")
    }

    fun parse(json: String): List<Station> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val uuid = o.optString("stationuuid")
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (uuid.isBlank() || url.isBlank()) return@mapNotNull null
            Station(
                uuid = uuid,
                name = o.optString("name").trim().ifBlank { "Sans nom" },
                url = url,
                favicon = o.optString("favicon").takeIf { it.startsWith("http") },
                country = o.optString("countrycode"),
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(3).joinToString(", "),
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
            )
        }.distinctBy { it.uuid }
    }
}
