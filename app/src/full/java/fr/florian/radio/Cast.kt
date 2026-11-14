package fr.florian.radio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.Player

// Variante « full » (GitHub, Obtainium) : Google Cast actif. Pendant sans Cast : src/fdroid.

/**
 * Lecteur de la session : bascule seul entre le téléphone et l'appareil casté.
 * Sans services Google (ou en cas d'échec), lecture locale seulement.
 */
fun withCast(context: Context, local: Player): Player =
    runCatching { CastPlayer.Builder(context).setLocalPlayer(local).build() }.getOrElse { local }

/** Bouton Google Cast : visible quand un appareil compatible est sur le réseau. */
@Composable
fun CastButton(modifier: Modifier = Modifier) = MediaRouteButton(modifier = modifier)
