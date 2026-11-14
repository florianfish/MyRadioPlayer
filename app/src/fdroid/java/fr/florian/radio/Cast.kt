package fr.florian.radio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Player

// Variante « fdroid » : sans Google Cast, qui dépend des services Google Play (non libres).

/** Pas de Cast : lecture locale uniquement. */
@Suppress("UNUSED_PARAMETER")
fun withCast(context: Context, local: Player): Player = local

/** Pas de bouton Cast. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CastButton(modifier: Modifier = Modifier) = Unit
