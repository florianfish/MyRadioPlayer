package fr.florian.radio

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var isPlaying by mutableStateOf(false)
    private var isBuffering by mutableStateOf(false)
    private var currentId by mutableStateOf<String?>(null)
    private var currentName by mutableStateOf<String?>(null)
    private var currentInfo by mutableStateOf<String?>(null)

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
        currentInfo = item?.mediaMetadata?.artist?.toString()
    }

    private fun play(s: Station) {
        val c = controller ?: return
        val uri = Uri.parse(s.url)
        val item = MediaItem.Builder()
            .setMediaId(s.uuid)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.name)
                    .setArtist(s.subtitle)
                    .setArtworkUri(s.favicon?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        lifecycleScope.launch(Dispatchers.IO) { RadioBrowser.countClick(s.uuid) }
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
        isPlaying -> listOfNotNull("En direct", currentInfo?.takeIf { it.isNotBlank() }).joinToString(" · ")
        else -> "Arrêté"
    }

    @Composable
    private fun RadioScreen() {
        var query by rememberSaveable { mutableStateOf("") }
        var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var header by remember { mutableStateOf("Populaires en France") }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current

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

        LaunchedEffect(Unit) { load(query) }

        Scaffold(
            bottomBar = {
                currentName?.let { name ->
                    PlayerBar(name, statusText(), isPlaying, isBuffering, ::togglePlayback)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(stations, key = { it.uuid }) { s ->
                                StationRow(s, s.uuid == currentId) {
                                    keyboard?.hide()
                                    play(s)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: Station, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { Initial(s.name) },
        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(s.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (selected) {
            { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
    )
}

@Composable
private fun Initial(name: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PlayerBar(name: String, status: String, playing: Boolean, buffering: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Initial(name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
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
