package fr.florian.radio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.roundToInt

private const val FAVORITES_KEY = "favorites"
private const val TAB_FAVORITES = 0
private const val TAB_DISCOVER = 1

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(false)
    private var currentId by mutableStateOf<String?>(null)
    private var currentName by mutableStateOf<String?>(null)
    private var currentInfo by mutableStateOf<String?>(null)
    private var currentTrack by mutableStateOf<String?>(null)
    private var currentArt by mutableStateOf<String?>(null)
    private var volume by mutableIntStateOf(0)
    private var maxVolume by mutableIntStateOf(0)

    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private var favorites by mutableStateOf<List<Station>>(emptyList())
    private var lastStation by mutableStateOf<Station?>(null)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncState(player)
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MainActivity, "Impossible de lire ce flux", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        favorites = runCatching { RadioBrowser.parse(prefs.getString(FAVORITES_KEY, null) ?: "[]") }
            .getOrDefault(emptyList())
        lastStation = LastStation.load(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { RadioScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            // Rien en cours : on prépare la barre de lecture avec la dernière station (sans la lancer)
            if (c.mediaItemCount == 0) lastStation?.let { c.setMediaItem(it.toMediaItem()) }
            syncState(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun syncState(p: Player) {
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
        val item = p.currentMediaItem
        currentId = item?.mediaId
        currentName = item?.mediaMetadata?.title?.toString()
        val md = item?.mediaMetadata
        currentInfo = md?.subtitle?.toString()
        // `artist` vaut la description de la station, ou le morceau annoncé par le flux (cf. PlaybackService)
        currentTrack = md?.artist?.toString()?.takeIf { it.isNotBlank() && it != currentInfo }
        currentArt = item?.mediaMetadata?.artworkUri?.toString()
        if (p.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)) {
            volume = p.deviceVolume
            maxVolume = p.deviceInfo.maxVolume
        } else {
            maxVolume = 0
        }
    }

    private fun saveFavorites(list: List<Station>) {
        favorites = list
        prefs.edit().putString(FAVORITES_KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun play(s: Station) {
        val c = controller ?: return
        c.setMediaItem(s.toMediaItem())
        c.prepare()
        c.play()
        lastStation = s
        LastStation.save(this, s)
        lifecycleScope.launch(Dispatchers.IO) { RadioBrowser.countClick(s.uuid) }
    }

    private fun setVolume(v: Int) {
        controller?.setDeviceVolume(v, 0)
    }

    /** Radio en direct : on coupe le flux (stop) plutôt que pause, pour repartir en live. */
    private fun togglePlayback() {
        val c = controller ?: return
        if (c.isPlaying || isBuffering) {
            c.stop()
        } else {
            c.prepare()
            c.play()
        }
    }

    private fun statusText(): String = when {
        isBuffering -> "Connexion…"
        isPlaying -> currentTrack ?: listOfNotNull("En direct", currentInfo?.takeIf { it.isNotBlank() }).joinToString(" · ")
        else -> "Arrêté"
    }

    @Composable
    private fun RadioScreen() {
        var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
        var tab by rememberSaveable { mutableStateOf(if (favorites.isEmpty()) TAB_DISCOVER else TAB_FAVORITES) }
        var query by rememberSaveable { mutableStateOf("") }
        var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var header by remember { mutableStateOf("Populaires en France") }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val snackbar = remember { SnackbarHostState() }
        val favoriteIds = favorites.mapTo(HashSet()) { it.uuid }

        fun load(q: String) {
            scope.launch {
                loading = true
                error = null
                try {
                    stations = withContext(Dispatchers.IO) {
                        if (q.isBlank()) RadioBrowser.popular("FR") else RadioBrowser.search(q.trim())
                    }
                    header = if (q.isBlank()) "Populaires en France" else "Résultats pour « ${q.trim()} »"
                } catch (e: Exception) {
                    error = "Impossible de joindre Radio Browser"
                } finally {
                    loading = false
                }
            }
        }

        fun toggleFavorite(s: Station) {
            val index = favorites.indexOfFirst { it.uuid == s.uuid }
            if (index < 0) {
                saveFavorites(favorites + s)
                return
            }
            saveFavorites(favorites.filterNot { it.uuid == s.uuid })
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    "${s.name} retirée des favoris",
                    actionLabel = "Annuler",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && favorites.none { it.uuid == s.uuid }) {
                    saveFavorites(favorites.toMutableList().apply { add(index.coerceAtMost(size), s) })
                }
            }
        }

        fun onStationClick(s: Station) {
            keyboard?.hide()
            if (s.uuid == currentId) togglePlayback() else play(s)
        }

        LaunchedEffect(Unit) { load(query) }

        val current = favorites.find { it.uuid == currentId }
            ?: stations.find { it.uuid == currentId }
            ?: lastStation?.takeIf { it.uuid == currentId }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                currentName?.let { name ->
                    PlayerBar(
                        name = name,
                        logos = current?.logoUrls ?: listOfNotNull(currentArt),
                        status = statusText(),
                        playing = isPlaying,
                        buffering = isBuffering,
                        favorite = current?.let { it.uuid in favoriteIds },
                        onFavorite = { current?.let(::toggleFavorite) },
                        onToggle = ::togglePlayback,
                        onOpen = { nowPlayingOpen = true },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(
                        "Radio",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Version installée, discrète : utile pour vérifier une mise à jour (Obtainium) ou un APK de test
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(
                        selected = tab == TAB_FAVORITES,
                        onClick = { tab = TAB_FAVORITES },
                        text = { Text(if (favorites.isEmpty()) "Favoris" else "Favoris (${favorites.size})") },
                    )
                    Tab(
                        selected = tab == TAB_DISCOVER,
                        onClick = { tab = TAB_DISCOVER },
                        text = { Text("Découvrir") },
                    )
                }

                if (tab == TAB_FAVORITES) {
                    if (favorites.isEmpty()) {
                        EmptyFavorites { tab = TAB_DISCOVER }
                    } else {
                        StationList(favorites, currentId, favoriteIds, ::onStationClick, ::toggleFavorite)
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        placeholder = { Text("Rechercher une radio…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; load("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Effacer")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); load(query) }),
                    )
                    Text(
                        header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                    Box(Modifier.fillMaxSize()) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            error != null -> Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(error ?: "")
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { load(query) }) { Text("Réessayer") }
                            }
                            stations.isEmpty() -> Text("Aucune radio trouvée", Modifier.align(Alignment.Center))
                            else -> StationList(stations, currentId, favoriteIds, ::onStationClick, ::toggleFavorite)
                        }
                    }
                }
            }
        }

        val name = currentName
        if (nowPlayingOpen && name != null) {
            NowPlayingSheet(
                name = name,
                logos = current?.logoUrls ?: listOfNotNull(currentArt),
                track = currentTrack,
                info = currentInfo,
                playing = isPlaying,
                buffering = isBuffering,
                favorite = current?.let { it.uuid in favoriteIds },
                volume = volume,
                maxVolume = maxVolume,
                onFavorite = { current?.let(::toggleFavorite) },
                onToggle = ::togglePlayback,
                onVolume = ::setVolume,
                onDismiss = { nowPlayingOpen = false },
            )
        }
    }
}

@Composable
private fun StationList(
    list: List<Station>,
    currentId: String?,
    favoriteIds: Set<String>,
    onClick: (Station) -> Unit,
    onFavorite: (Station) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.uuid }) { s ->
            StationRow(s, s.uuid == currentId, s.uuid in favoriteIds, { onClick(s) }, { onFavorite(s) })
        }
    }
}

@Composable
private fun StationRow(s: Station, current: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = if (current) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { StationLogo(s.name, s.logoUrls) },
        headlineContent = {
            Text(
                s.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.SemiBold else null,
            )
        },
        supportingContent = { Text(s.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { FavoriteButton(favorite, onFavorite) },
    )
}

@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            tint = if (favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

@Composable
private fun EmptyFavorites(onDiscover: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.FavoriteBorder,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Aucune radio favorite", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Touchez le cœur à côté d'une radio pour la retrouver ici.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onDiscover) { Text("Découvrir des radios") }
    }
}

// shortcut: cache mémoire seulement (pas de cache disque) ; passer à Coil si besoin.
// Clé : la liste des adresses candidates d'une station. Les échecs sont mémorisés pour la session,
// pour ne pas retenter trois téléchargements à chaque défilement.
private val logoCache = LruCache<String, ImageBitmap>(200)
private val failedLogos = HashSet<String>()

/** En dessous, l'image est floue une fois agrandie : on cherche mieux parmi les adresses suivantes. */
private const val SHARP_LOGO_PX = 64

/**
 * Logo de la station : première image nette parmi [urls] (dans l'ordre), sinon la plus grande
 * trouvée, sinon l'initiale du nom.
 */
@Composable
private fun StationLogo(name: String, urls: List<String>, size: Dp = 48.dp) {
    val big = size > 100.dp
    // Écran de lecture : logo téléchargé en plus grand, mis en cache à part
    val px = if (big) 512 else 144
    val key = urls.joinToString("|") + "@" + px
    val logo by produceState(logoCache.get(key), key) {
        // produceState garde l'ancienne valeur quand la clé change (barre de lecture : changement de station)
        value = logoCache.get(key)
        if (value == null && urls.isNotEmpty() && key !in failedLogos) {
            val image = withContext(Dispatchers.IO) {
                var best: ImageBitmap? = null
                for (url in urls) {
                    val img = runCatching { RadioBrowser.logo(url, px)?.asImageBitmap() }.getOrNull() ?: continue
                    if (maxOf(img.width, img.height) >= SHARP_LOGO_PX) return@withContext img
                    if (best == null || img.width > best.width) best = img
                }
                best
            }
            if (image != null) {
                logoCache.put(key, image)
                value = image
            } else {
                failedLogos.add(key)
            }
        }
    }
    val image = logo
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(if (big) 28.dp else 12.dp))
            .background(if (image != null) Color.White else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(if (big) size / 10 else 4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                style = if (big) MaterialTheme.typography.displayLarge else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PlayerBar(
    name: String,
    logos: List<String>,
    status: String,
    playing: Boolean,
    buffering: Boolean,
    favorite: Boolean?,
    onFavorite: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        // Toucher ou glisser vers le haut : écran de lecture en grand
        modifier = Modifier
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f }) { _, dy ->
                    drag += dy
                    if (drag < -40f) onOpen()
                }
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StationLogo(name, logos, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Défilement si le titre en cours est trop long
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
            }
            // null : station inconnue de l'écran (ex. relancée depuis la notification), on ne peut pas l'enregistrer
            if (favorite != null) FavoriteButton(favorite, onFavorite)
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(52.dp)) {
                if (buffering) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        if (playing) PauseIcon else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Arrêter" else "Lecture",
                    )
                }
            }
        }
    }
}

/** Icône pause (absente de material-icons-core). */
private val PauseIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Pause") {
        materialPath {
            moveTo(6f, 19f); horizontalLineTo(10f); verticalLineTo(5f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); verticalLineTo(19f); horizontalLineTo(18f); verticalLineTo(5f); close()
        }
    }
}

/** Pochettes déjà cherchées (null : rien trouvé), par morceau. */
private val coverCache = LruCache<String, Any>(30)
private object NoCover

/**
 * Écran de lecture en grand, ouvert depuis la barre de lecture (toucher ou glisser vers le haut),
 * fermé en glissant vers le bas. Pochette du morceau si on la trouve, sinon logo de la station.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(
    name: String,
    logos: List<String>,
    track: String?,
    info: String?,
    playing: Boolean,
    buffering: Boolean,
    favorite: Boolean?,
    volume: Int,
    maxVolume: Int,
    onFavorite: () -> Unit,
    onToggle: () -> Unit,
    onVolume: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
                val cover = rememberCover(track)
                Crossfade(cover, label = "pochette") { image ->
                    if (image != null) {
                        Image(
                            image,
                            contentDescription = "Pochette",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        StationLogo(name, logos, maxWidth)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    buffering -> "Connexion…"
                    !playing -> "Arrêté"
                    else -> track ?: "En direct"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            if (!info.isNullOrBlank()) {
                Text(
                    info,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Emplacements de même largeur de part et d'autre pour garder le bouton centré
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (favorite != null) FavoriteButton(favorite, onFavorite)
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(80.dp)) {
                    if (buffering) {
                        CircularProgressIndicator(
                            Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            if (playing) PauseIcon else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Arrêter" else "Lecture",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.size(48.dp))
            }
            // Volume média du téléphone (le même que les boutons physiques)
            if (maxVolume > 0) {
                Spacer(Modifier.height(28.dp))
                var dragged by remember { mutableStateOf<Float?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(VolumeDownIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = dragged ?: volume.toFloat(),
                        onValueChange = { dragged = it; onVolume(it.roundToInt()) },
                        onValueChangeFinished = { dragged = null },
                        valueRange = 0f..maxVolume.toFloat(),
                        steps = (maxVolume - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(VolumeUpIcon, contentDescription = "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Pochette du morceau [track] (iTunes), null tant qu'elle n'est pas trouvée. */
@Composable
private fun rememberCover(track: String?): ImageBitmap? {
    val cover by produceState(track?.let { coverCache.get(it) as? ImageBitmap }, track) {
        value = track?.let { coverCache.get(it) as? ImageBitmap }
        if (track == null || coverCache.get(track) != null) return@produceState
        val image = withContext(Dispatchers.IO) {
            runCatching { CoverArt.find(track)?.let { RadioBrowser.logo(it, 600)?.asImageBitmap() } }.getOrNull()
        }
        coverCache.put(track, image ?: NoCover)
        value = image
    }
    return cover
}

private fun vectorIcon(name: String, path: String): ImageVector = materialIcon(name) {
    addPath(addPathNodes(path), fill = SolidColor(Color.Black))
}

private val VolumeDownIcon by lazy {
    vectorIcon("VolumeDown", "M18.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM5,9v6h4l5,5V4L9,9H5z")
}

private val VolumeUpIcon by lazy {
    vectorIcon(
        "VolumeUp",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
            "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )
}
