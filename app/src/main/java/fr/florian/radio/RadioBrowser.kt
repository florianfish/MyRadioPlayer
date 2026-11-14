package fr.florian.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
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
    val homepage: String?,
    val country: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
) {
    val subtitle: String
        get() = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            Tags.display(tags).takeIf { it.isNotBlank() },
            if (bitrate > 0) "$bitrate kbps" else codec.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    /**
     * Adresses à essayer pour le logo : celui de l'annuaire, puis les icônes du site de la radio
     * (souvent présentes quand le favicon de l'annuaire est vide ou cassé).
     */
    val logoUrls: List<String>
        get() {
            val site = homepage?.let { runCatching { URL(it) }.getOrNull() }
                ?.takeIf { it.host.isNotBlank() }
                ?.let { "${it.protocol}://${it.host}" }
            return listOfNotNull(favicon, site?.let { "$it/apple-touch-icon.png" }, site?.let { "$it/favicon.ico" })
                .distinct()
        }

    /** Même format que l'API, pour être relu par [RadioBrowser.parse] (favoris). */
    fun toJson(): JSONObject = JSONObject()
        .put("stationuuid", uuid)
        .put("name", name)
        .put("url_resolved", url)
        .put("favicon", favicon ?: "")
        .put("homepage", homepage ?: "")
        .put("countrycode", country)
        .put("tags", tags)
        .put("codec", codec)
        .put("bitrate", bitrate)
}

/**
 * Mise en forme des tags bruts de l'annuaire (« aac, information, public radio ») :
 * sans tags techniques ni génériques, genres anglais courants traduits, majuscule, sans doublon.
 * Appliqué à l'affichage pour que les favoris déjà enregistrés en profitent.
 */
object Tags {
    private const val MAX = 2

    /** Formats, débits et mots qui n'apprennent rien sur la station. */
    private val ignored = setOf(
        "aac", "aac+", "he-aac", "heaac", "mp3", "ogg", "opus", "flac", "hls", "m3u8", "icecast", "shoutcast",
        "radio", "webradio", "web radio", "internet radio", "online radio", "fm", "am", "dab", "dab+",
        "stream", "streaming", "live", "station", "fr", "france", "french", "français", "francais",
    )

    /** Débits (« 128k », « 64 kbps »…). */
    private val bitrate = Regex("[0-9]+ *k(bps|b/s|bit)?")

    /** Genres anglais fréquents et fautes courantes de l'annuaire. */
    private val translations = mapOf(
        "news" to "info", "information" to "info", "informations" to "info", "actualités" to "info",
        "talk" to "débat", "debate" to "débat", "sports" to "sport", "music" to "musique",
        "classical" to "classique", "public radio" to "service public", "generalist" to "généraliste",
        "general" to "généraliste", "génaraliste" to "généraliste", "generaliste" to "généraliste",
        "variety" to "variétés", "variete" to "variétés", "oldies" to "rétro",
        "local" to "locale", "regional" to "régionale", "christian" to "chrétienne",
        "catholic" to "catholique", "kids" to "enfants",
    )

    fun display(raw: String): String = raw.split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && it !in ignored && !bitrate.matches(it) }
        .map { translations[it] ?: it }
        .distinct()
        .take(MAX)
        .joinToString(", ") { tag -> tag.replaceFirstChar { it.titlecase() } }
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

    /**
     * Télécharge un logo réduit à [size] px max ; null si l'adresse ne renvoie pas une image.
     * Suit les redirections à la main (HttpURLConnection refuse http -> https) et décode le SVG.
     */
    fun logo(url: String, size: Int = 144): Bitmap? {
        var target = URL(url)
        repeat(5) {
            val conn = target.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "SimpleRadio/1.0")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = URL(target, conn.getHeaderField("Location") ?: return null)
                    return@repeat
                }
                // Pages d'erreur « 200 » en HTML des sites (ex. apple-touch-icon absent)
                val type = conn.contentType.orEmpty().lowercase()
                if (code != 200 || type.startsWith("text/html")) return null
                if (conn.contentLengthLong > MAX_LOGO_BYTES) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                return decodeLogo(bytes, type, size)
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private const val MAX_LOGO_BYTES = 2 * 1024 * 1024

    private fun decodeLogo(bytes: ByteArray, type: String, size: Int): Bitmap? {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart().lowercase()
        if ("svg" in type || head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head)) {
            return renderSvg(String(bytes, Charsets.UTF_8), size)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = size.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true,
        )
    }

    private fun renderSvg(text: String, size: Int): Bitmap? {
        val svg = SVG.getFromString(text)
        // Sans viewBox, AndroidSVG ne met pas le dessin à l'échelle : on en déduit une des dimensions
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        if (svg.documentViewBox == null) return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp), RectF(0f, 0f, size.toFloat(), size.toFloat()))
        return bmp
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
                homepage = o.optString("homepage").takeIf { it.startsWith("http") },
                country = o.optString("countrycode"),
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(8).joinToString(", "),
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
            )
        }.distinctBy { it.uuid }
    }
}
