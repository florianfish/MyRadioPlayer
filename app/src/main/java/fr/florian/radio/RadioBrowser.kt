package fr.florian.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** Flux HLS (.m3u8) selon l'annuaire : sert au type MIME envoyé au Cast. */
    val hls: Boolean = false,
    /** Région selon l'annuaire, nombre d'écoutes et de votes : servent au tri des recherches fusionnées. */
    val state: String = "",
    val clicks: Int = 0,
    val votes: Int = 0,
) {
    val subtitle: String
        get() = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            Tags.display(tags).takeIf { it.isNotBlank() },
            if (bitrate > 0) "$bitrate kbps" else codec.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    /**
     * Adresses à essayer pour le logo : celui de l'annuaire, puis les icônes du site de la radio
     * (souvent présentes quand le favicon de l'annuaire est vide ou cassé), et en dernier recours
     * le service d'icônes de Google, qui contourne les sites refusant les applis.
     */
    val logoUrls: List<String>
        get() {
            val site = homepage?.let { runCatching { URL(it) }.getOrNull() }?.takeIf { it.host.isNotBlank() }
            val origin = site?.let { "${it.protocol}://${it.host}" }
            // Sans site connu, le domaine du favicon fait l'affaire (souvent celui de la radio)
            val domain = (site ?: favicon?.let { runCatching { URL(it) }.getOrNull() })?.host?.takeIf { it.isNotBlank() }
            return listOfNotNull(
                favicon,
                origin?.let { "$it/apple-touch-icon.png" },
                origin?.let { "$it/favicon.ico" },
                domain?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" },
            ).distinct()
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
        .put("hls", if (hls) 1 else 0)
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

/** Tri des résultats (valeur `order` de l'API). */
enum class Sort(val label: String, val api: String) {
    POPULAR("Populaires", "clickcount"),
    VOTES("Mieux notées", "votes"),
    NAME("Nom", "name"),
}

/** Genre proposé en puce : libellé affiché et tag cherché dans l'annuaire. */
data class Genre(val label: String, val tag: String)

/** Pays proposé : code ISO (filtre des stations) et nom anglais de l'annuaire (liste des régions). */
data class Country(val code: String, val label: String, val apiName: String)

val GENRES = listOf(
    Genre("Info", "news"), Genre("Pop", "pop"), Genre("Rock", "rock"), Genre("Jazz", "jazz"),
    Genre("Classique", "classical"), Genre("Électro", "electronic"), Genre("Dance", "dance"),
    Genre("Rap", "hip hop"), Genre("Années 80", "80s"), Genre("Rétro", "oldies"),
    Genre("Sport", "sport"), Genre("Chill", "chill"), Genre("Enfants", "kids"),
)

val COUNTRIES = listOf(
    Country("FR", "France", "France"), Country("BE", "Belgique", "Belgium"),
    Country("CH", "Suisse", "Switzerland"), Country("LU", "Luxembourg", "Luxembourg"),
    Country("CA", "Canada", "Canada"), Country("GB", "Royaume-Uni", "The United Kingdom Of Great Britain And Northern Ireland"),
    Country("US", "États-Unis", "The United States Of America"), Country("DE", "Allemagne", "Germany"),
    Country("ES", "Espagne", "Spain"), Country("IT", "Italie", "Italy"), Country("PT", "Portugal", "Portugal"),
)

/** Critères de l'onglet Découvrir. [country] null : tous les pays. */
data class Criteria(
    val text: String = "",
    val genre: Genre? = null,
    val country: Country? = COUNTRIES.first(),
    val region: String? = null,
    val sort: Sort = Sort.POPULAR,
    val hd: Boolean = false,
) {
    /** Aucun critère choisi : liste d'accueil. */
    val isDefault: Boolean get() = text.isBlank() && genre == null && region == null
}

/** Débit minimal du filtre « HD ». */
const val HD_BITRATE = 96

/** Client minimal pour https://api.radio-browser.info */
object RadioBrowser {
    private val hosts = listOf(
        "all.api.radio-browser.info",
        "de1.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "at1.api.radio-browser.info",
    )

    /**
     * Recherche de l'onglet Découvrir. Le texte est cherché dans le nom, les genres et la région
     * (trois requêtes en parallèle, fusionnées puis retriées) : « jazz » ou « Bretagne » trouvent
     * aussi les stations qui n'ont pas le mot dans leur nom.
     */
    suspend fun search(s: Criteria): List<Station> = coroutineScope {
        val base = buildMap {
            s.genre?.let { put("tag", it.tag) }
            s.country?.let { put("countrycode", it.code) }
            s.region?.let { put("state", it) }
            if (s.hd) put("bitrateMin", HD_BITRATE.toString())
            put("order", s.sort.api)
            put("reverse", (s.sort != Sort.NAME).toString())
            put("limit", "100")
            put("hidebroken", "true")
        }
        val text = s.text.trim()
        val variants = if (text.isEmpty()) listOf(base) else listOfNotNull(
            base + ("name" to text),
            (base + ("tag" to text)).takeIf { s.genre == null },
            (base + ("state" to text)).takeIf { s.region == null },
        )
        val results = variants.map { params ->
            async(Dispatchers.IO) { runCatching { parse(get("/json/stations/search?" + encode(params))) } }
        }.awaitAll()
        // Toutes les requêtes en échec : on remonte l'erreur (sinon résultats partiels)
        if (results.all { it.isFailure }) results.first().getOrThrow()
        val merged = results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.uuid }
        if (variants.size == 1) return@coroutineScope merged
        when (s.sort) {
            Sort.POPULAR -> merged.sortedByDescending { it.clicks }
            Sort.VOTES -> merged.sortedByDescending { it.votes }
            Sort.NAME -> merged.sortedBy { it.name.lowercase() }
        }
    }

    /** Régions d'un pays ayant au moins quelques stations, par ordre alphabétique. */
    fun regions(country: Country): List<String> {
        val name = URLEncoder.encode(country.apiName, "UTF-8").replace("+", "%20")
        val arr = JSONArray(get("/json/states/$name/?order=stationcount&reverse=true&hidebroken=true"))
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optInt("stationcount") >= 3 }
            .map { it.optString("name").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(80)
            .sortedBy { it.lowercase() }
    }

    private fun encode(params: Map<String, String>) =
        params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

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
            // Agent de navigateur : certains sites (CDN anti-robots) refusent les autres
            conn.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
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
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

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
                conn.setRequestProperty("User-Agent", "MyRadioPlayer/1.0")
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
                hls = o.optInt("hls") == 1,
                state = o.optString("state").trim(),
                clicks = o.optInt("clickcount"),
                votes = o.optInt("votes"),
            )
        }.distinctBy { it.uuid }
    }
}

/** Recherche de pochette via l'API publique iTunes Search (sans clé). */
object CoverArt {
    /** Adresse d'une pochette 600 px pour « Artiste - Titre », ou null. */
    fun find(track: String): String? {
        // Seulement les annonces au format « artiste - titre » : évite les pochettes au hasard sur un slogan
        if (" - " !in track && " – " !in track) return null
        val q = URLEncoder.encode(track.replace(" – ", " ").replace(" - ", " "), "UTF-8")
        val conn = URL("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=1&country=FR")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val art = json.optJSONArray("results")?.optJSONObject(0)?.optString("artworkUrl100").orEmpty()
            return art.takeIf { it.startsWith("http") }?.replace("100x100bb", "600x600bb")
        } finally {
            conn.disconnect()
        }
    }
}
