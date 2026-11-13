# SimpleRadio

Lecteur de radios internet pour Android, minimaliste et sans publicité. Les stations viennent de l'annuaire libre [Radio Browser](https://www.radio-browser.info/) ; la lecture continue en arrière-plan avec une notification de contrôle.

## Fonctionnalités

- **Découvrir** : stations les plus écoutées en France à l'ouverture, recherche par nom dans tout l'annuaire.
- **Favoris** : un cœur sur chaque station pour l'ajouter ou la retirer, avec « Annuler » après un retrait. Les favoris sont stockés sur le téléphone et s'affichent même hors ligne.
- **Lecture en arrière-plan** : notification média, contrôles sur l'écran de verrouillage et depuis les écouteurs Bluetooth. La lecture se met en pause quand les écouteurs sont débranchés.
- **Direct** : « Arrêter » coupe le flux au lieu de le mettre en pause, pour reprendre au direct à la relance.
- **Logos des stations**, thème clair/sombre et couleurs Material You (Android 12+).
- Flux MP3, AAC et HLS (`.m3u8`), en HTTP comme en HTTPS.

## Installation

1. Ouvrir l'onglet **Actions** du dépôt et choisir la dernière exécution réussie du workflow **Build APK**.
2. Télécharger l'artefact `simple-radio-apk` puis extraire `app-debug.apk`.
3. Installer l'APK sur le téléphone en autorisant les sources inconnues.

Android 8.0 (API 26) minimum. Au premier lancement, autoriser les notifications (Android 13+) pour garder les contrôles de lecture.

## Build

### GitHub Actions

Le workflow `.github/workflows/build.yml` compile l'APK de debug à chaque push sur `main`. On peut aussi le lancer manuellement avec **Run workflow**.

### En local

Prérequis : JDK 17, Gradle 8.9 et Android SDK 34. Aucun wrapper Gradle n'est versionné, il faut donc utiliser un `gradle` installé sur la machine.

```bash
gradle assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

Un seul module `:app` (Kotlin, Jetpack Compose, Media3/ExoPlayer), package `fr.florian.radio` :

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | Interface Compose (onglets, listes, lecteur) et favoris. Pilote la lecture via un `MediaController`. |
| `PlaybackService.kt` | `MediaSessionService` qui porte l'`ExoPlayer` et la session média. Media3 gère la notification. |
| `RadioBrowser.kt` | Client HTTP minimal de l'API Radio Browser, avec bascule entre plusieurs serveurs. Contient aussi le modèle `Station` et le téléchargement des logos. |

Les favoris sont enregistrés en JSON dans les `SharedPreferences` (`radio` / `favorites`), au même format que les réponses de l'API.

## Permissions

| Permission | Usage |
|---|---|
| `INTERNET` | Annuaire Radio Browser et flux audio |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Lecture en arrière-plan |
| `WAKE_LOCK` | Garder la connexion réseau active écran éteint |
| `POST_NOTIFICATIONS` | Notification de lecture (Android 13+) |

Le trafic HTTP non chiffré est autorisé, car beaucoup de flux radio ne sont pas en HTTPS.

## Limites connues

- Pas de réorganisation des favoris : ils apparaissent dans l'ordre d'ajout.
- Les logos sont gardés en mémoire seulement le temps de la session. Les formats SVG et ICO ne s'affichent pas.
- Une station relancée depuis la notification, si elle n'apparaît dans aucune liste, ne peut pas être ajoutée aux favoris depuis le lecteur.
- APK de debug uniquement, sans signature de release.

## Crédits

Données des stations : [Radio Browser](https://www.radio-browser.info/), annuaire communautaire et libre. L'application signale chaque écoute à l'API, comme celle-ci le demande, pour améliorer le classement des stations.
