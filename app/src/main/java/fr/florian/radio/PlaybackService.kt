package fr.florian.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Lecture en arrière-plan + notification média (gérée par Media3). */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("SimpleRadio/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                    .setLoadErrorHandlingPolicy(PatientRetryPolicy())
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Volume du téléphone pilotable depuis l'appli (curseur de l'écran de lecture)
            .setDeviceVolumeControlEnabled(true)
            .build()

        // Google Cast selon la variante (cf. src/full et src/fdroid)
        val player: Player = withCast(this, exoPlayer)
        player.addListener(NowPlayingListener(player))
        resumeOnNetwork = ResumeOnNetwork(this, player)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Restaure l'URI (et le type MIME, utile au Cast) s'ils n'ont pas traversé controller -> session
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(
                        mediaItems.map { item ->
                            val uri = item.requestMetadata.mediaUri ?: return@map item
                            item.buildUpon()
                                .setUri(uri)
                                .setMimeType(item.requestMetadata.extras?.getString(MIME_EXTRA))
                                .build()
                        }.toMutableList()
                    )

                // Lecture demandée sans station chargée (casque, voiture, carte média du système) :
                // on relance la dernière station écoutée
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val station = LastStation.load(this@PlaybackService)
                        ?: return Futures.immediateFailedFuture(IllegalStateException("Aucune station mémorisée"))
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(listOf(station.toMediaItem()), 0, C.TIME_UNSET)
                    )
                }
            })
            .build()
    }

    private var resumeOnNetwork: ResumeOnNetwork? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Appli fermée (balayée depuis les applis récentes) : on coupe la radio et le service.
     * La lecture continue en revanche écran éteint ou dans une autre appli ; la dernière
     * station reste mémorisée pour la reprise (casque, voiture, carte média).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        resumeOnNetwork?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * MediaItem d'une station. L'URI est mise à la fois dans `setUri` et dans
 * `RequestMetadata.mediaUri` : seule la seconde survit au passage controller -> session.
 */
fun Station.toMediaItem(): MediaItem {
    val uri = Uri.parse(url)
    val mime = mimeType()
    return MediaItem.Builder()
        .setMediaId(uuid)
        .setUri(uri)
        .setMimeType(mime)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uri)
                .setExtras(Bundle().apply { putString(MIME_EXTRA, mime) })
                .build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setTitle(name)
                .setArtist(subtitle)
                .setSubtitle(subtitle)
                .setArtworkUri(favicon?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}

private const val MIME_EXTRA = "mime"

/**
 * Type MIME du flux : le récepteur Cast en a besoin pour le lire. Localement, seul le HLS
 * change quelque chose (choix de la source) ; le reste est détecté par ExoPlayer.
 */
private fun Station.mimeType(): String? = when {
    hls || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
    codec.startsWith("AAC", ignoreCase = true) -> MimeTypes.AUDIO_AAC
    codec.equals("MP3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
    codec.equals("OGG", ignoreCase = true) -> MimeTypes.AUDIO_OGG
    else -> null
}

/** Dernière station lancée, pour la reprise (appli, casque, voiture). Mêmes préférences que les favoris. */
object LastStation {
    private const val KEY = "last_station"

    private fun prefs(context: Context) = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun load(context: Context): Station? = runCatching {
        prefs(context).getString(KEY, null)?.let { RadioBrowser.parse("[$it]").firstOrNull() }
    }.getOrNull()

    fun save(context: Context, station: Station) {
        prefs(context).edit().putString(KEY, station.toJson().toString()).apply()
    }
}

/**
 * Titre en cours : recopie dans `artist` du MediaItem le morceau annoncé par le flux
 * (métadonnées ICY « StreamTitle », ou ID3 pour le HLS). La notification, l'écran de
 * verrouillage et l'appli le lisent ainsi directement. `subtitle` garde la description
 * de la station, remise dans `artist` quand le flux n'annonce rien ou s'arrête.
 *
 * Nécessaire car ExoPlayer fait primer les métadonnées du MediaItem sur celles du flux
 * dans `player.mediaMetadata` : le titre ICY n'y apparaît jamais.
 */
@OptIn(UnstableApi::class)
private class NowPlayingListener(private val player: Player) : Player.Listener {

    override fun onMetadata(metadata: Metadata) {
        val track = nowPlaying(metadata) ?: return
        setArtist(track.takeUnless { it.isEmpty() || it.equals(stationName(), ignoreCase = true) })
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Arrêt (stop) : on efface le morceau, il sera ré-annoncé à la relance
        if (playbackState == Player.STATE_IDLE) setArtist(null)
    }

    private fun stationName() = player.currentMediaItem?.mediaMetadata?.title?.toString()

    /** [track] null : retour à la description de la station. */
    private fun setArtist(track: String?) {
        val item = player.currentMediaItem ?: return
        val md = item.mediaMetadata
        val artist = track ?: md.subtitle
        if (artist?.toString() == md.artist?.toString()) return
        // Même URI : ExoPlayer met à jour l'élément sans recharger le flux
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            item.buildUpon().setMediaMetadata(md.buildUpon().setArtist(artist).build()).build(),
        )
    }

    /**
     * Morceau annoncé dans [metadata] : chaîne vide si le flux signale explicitement
     * « rien » (StreamTitle vide), null si ce bloc ne concerne pas le titre.
     */
    private fun nowPlaying(metadata: Metadata): String? {
        var title: String? = null
        var artist: String? = null
        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is IcyInfo -> return clean(entry.title.orEmpty())
                is TextInformationFrame -> when (entry.id) {
                    "TIT2" -> title = entry.values.firstOrNull()
                    "TPE1" -> artist = entry.values.firstOrNull()
                }
            }
        }
        if (title == null && artist == null) return null
        return clean(listOfNotNull(artist, title).filter { it.isNotBlank() }.joinToString(" – "))
    }

    /** Vide si le texte ne contient rien d'utile (ex. « - », espaces). */
    private fun clean(raw: String): String {
        val s = raw.trim()
        return if (s.none { it.isLetterOrDigit() }) "" else s
    }
}

/** Erreurs dues au réseau (et non au flux lui-même) : la lecture peut reprendre au retour du réseau. */
val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
)

/**
 * Coupures réseau (tunnel, passage Wi-Fi <-> 4G) : ExoPlayer réessaie de charger le flux pendant
 * environ 1 min 30 (1, 2, 4, 8 puis 15 s entre les tentatives) en restant « en connexion », au
 * lieu d'abandonner après quelques secondes. Les erreurs définitives (404, format…) ne sont pas réessayées.
 */
@UnstableApi
private class PatientRetryPolicy : DefaultLoadErrorHandlingPolicy(RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        return minOf(1_000L shl (loadErrorInfo.errorCount - 1).coerceIn(0, 4), 15_000L)
    }

    private companion object {
        const val RETRIES = 10
    }
}

/**
 * Filet de sécurité : si les tentatives sont épuisées (coupure longue), la lecture reprend
 * dès que le réseau revient, à condition que l'utilisateur n'ait pas arrêté la radio entre-temps.
 */
private class ResumeOnNetwork(context: Context, private val player: Player) : ConnectivityManager.NetworkCallback() {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(this) }
    }

    override fun onAvailable(network: Network) {
        // Rappel sur un autre fil : le lecteur se pilote depuis le fil principal
        main.post {
            val error = player.playerError ?: return@post
            if (player.playWhenReady && error.errorCode in NETWORK_ERRORS) player.prepare()
        }
    }

    fun release() {
        runCatching { connectivity.unregisterNetworkCallback(this) }
        main.removeCallbacksAndMessages(null)
    }
}
