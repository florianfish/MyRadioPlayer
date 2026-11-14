# SimpleRadio

Lecteur de radios internet pour Android, minimaliste et sans publicité. Les stations viennent de l'annuaire libre [Radio Browser](https://www.radio-browser.info/) ; la lecture continue en arrière-plan avec une notification de contrôle.

## Fonctionnalités

- **Découvrir** : stations les plus écoutées en France à l'ouverture, recherche par nom dans tout l'annuaire.
- **Favoris** : un cœur sur chaque station pour l'ajouter ou la retirer, avec « Annuler » après un retrait. Les favoris sont stockés sur le téléphone et s'affichent même hors ligne.
- **Lecture en arrière-plan** : notification média, contrôles sur l'écran de verrouillage et depuis les écouteurs Bluetooth. La lecture se met en pause quand les écouteurs sont débranchés, et s'arrête quand on ferme l'appli depuis les applis récentes.
- **Direct** : « Arrêter » coupe le flux au lieu de le mettre en pause, pour reprendre au direct à la relance.
- **Dernière station** : à l'ouverture, la barre de lecture propose la dernière radio écoutée. Le bouton lecture du casque Bluetooth, de la voiture ou de la carte média d'Android la relance même appli fermée.
- **Titre en cours** : quand la radio l'annonce dans son flux, le morceau diffusé s'affiche dans la barre de lecture, la notification et sur l'écran de verrouillage.
- **Tags lisibles** : sous chaque station, au plus deux genres en français, sans les formats techniques de l'annuaire (« Info, Service public » plutôt que « aac, information, public radio »).
- **Logos des stations** (PNG, JPEG, WebP, ICO et SVG ; à défaut, icône du site de la radio ou service d'icônes de Google), thème clair/sombre et couleurs Material You (Android 12+).
- **Numéro de version** affiché en petit à côté du titre : `v1.1` pour une release, `v1.0-debug.N` pour un APK de test (N = numéro du run GitHub Actions).
- Flux MP3, AAC et HLS (`.m3u8`), en HTTP comme en HTTPS.

## Installation

Depuis le téléphone, ouvrir la page **Releases** du dépôt, télécharger `SimpleRadio-vX.Y.apk` de la dernière version, puis l'installer en autorisant les sources inconnues. Les versions suivantes s'installent par-dessus, sans perdre les favoris.

Android 8.0 (API 26) minimum. Au premier lancement, autoriser les notifications (Android 13+) pour garder les contrôles de lecture.

### Mises à jour automatiques avec Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) suit les releases GitHub et installe les nouvelles versions. Chaque tag `v*` poussé arrive ainsi sur le téléphone.

1. Installer Obtainium depuis ses releases GitHub (variante `arm64-v8a`), ou via F-Droid / IzzyOnDroid.
2. **Si le dépôt est privé**, créer un token GitHub : Settings → Developer settings → Personal access tokens → **Fine-grained tokens** :
   - *Repository access* : *Only select repositories* → `MyRadioPlayer`
   - *Permissions* : *Contents* → **Read-only** (*Metadata* est ajouté automatiquement)
   - *Expiration* : noter la date. Une fois le token expiré, Obtainium ne voit plus les mises à jour : il faut le régénérer et le recoller.

   Dans Obtainium : Settings → section **GitHub** → coller le token.
3. Onglet **Add App** → `https://github.com/florianfish/MyRadioPlayer` → **Add** (pré-releases décochées).
4. Si l'app est déjà installée depuis une release, l'installer une fois depuis Obtainium : elle passe par-dessus sans perdre les favoris (même signature). Autoriser Obtainium à installer des applications quand Android le demande.

Obtainium vérifie ensuite les releases en arrière-plan et notifie quand une nouvelle version est disponible.

## Build

### GitHub Actions

- `.github/workflows/build.yml` compile un APK de debug à chaque push sur `main` et sur chaque pull request (artefact `simple-radio-apk`, ou lancement manuel avec **Run workflow**). Sur une pull request, un commentaire donne le lien de téléchargement de l'APK, mis à jour à chaque nouveau commit. Ces APK sont signés avec une clé différente à chaque build : ils servent à tester, pas à mettre à jour l'application installée.
- `.github/workflows/release.yml` publie une **Release** avec l'APK signé à chaque tag `v*`.

### Publier une version

**Le plus simple, depuis le téléphone ou le navigateur :** onglet **Actions** → workflow **Release APK** → **Run workflow** (branche `main`) → choisir le type de version → **Run workflow**. Le workflow prend le dernier tag (ex. `v1.4.0`), calcule le suivant (**mineure** `v1.5.0`, **correctif** `v1.4.1`, **majeure** `v2.0.0`), compile l'APK signé et publie la release avec le tag et les notes générées.

Autres façons de faire, en ligne de commande :

```bash
git tag v1.1
git push origin v1.1
```

Le nom de version vient du tag (`1.1`) et le `versionCode` du numéro d'exécution du workflow, qui augmente toujours.

- Le tag **doit commencer par `v`** : un tag `1.1` ne déclenche aucun build.
- Depuis l'interface GitHub (sans ordinateur) : **Releases** → **Draft a new release** → **Choose a tag**, taper `v1.1` puis **Create new tag: v1.1 on publish** sur `main` → **Publish release**. La création du tag lance le workflow, qui ajoute l'APK à cette release.
- Suivre l'exécution dans l'onglet **Actions** (workflow « Release APK ») ; une fois terminé, l'APK `SimpleRadio-v1.1.apk` est attaché à la release.
- Si le build échoue (secret manquant ou erroné…), corriger puis relancer avec **Re-run jobs** sur l'exécution, ou repousser le tag :

  ```bash
  git push origin :refs/tags/v1.1   # supprime le tag distant
  git push origin v1.1              # le repousse, ce qui relance le workflow
  ```

### Clé de signature (une seule fois)

Toutes les releases doivent être signées avec la même clé, sinon Android refuse la mise à jour.

Avec un JDK installé (`keytool`) :

```bash
keytool -genkeypair -v -keystore simpleradio.jks -alias simpleradio \
  -keyalg RSA -keysize 4096 -validity 10000
```

Sans JDK, avec OpenSSL (fourni avec Git pour Windows : `C:\Program Files\Git\mingw64\bin\openssl.exe`) :

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 10000 -subj "/CN=SimpleRadio"
openssl pkcs12 -export -inkey key.pem -in cert.pem -name simpleradio -out simpleradio.jks
rm key.pem cert.pem
```

Le fichier obtenu est au format PKCS12, lu sans problème par Gradle malgré l'extension `.jks`. Le mot de passe de la clé est alors le même que celui du keystore.

Encoder ensuite le keystore en base64 :

```bash
base64 -w0 simpleradio.jks   # Linux / Git Bash
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\simpleradio.jks")) | Set-Clipboard   # PowerShell
```

Dans **Settings → Secrets and variables → Actions** du dépôt, créer :

| Secret | Valeur |
|---|---|
| `KEYSTORE_BASE64` | Keystore encodé en base64 |
| `KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `KEY_ALIAS` | `simpleradio` — doit être exactement l'alias donné à la clé (`-alias` / `-name`), sinon la signature échoue |
| `KEY_PASSWORD` | Mot de passe de la clé (le même que le keystore si `keytool` ne l'a pas demandé) |

Garder `simpleradio.jks` et ses mots de passe en lieu sûr, hors du dépôt (`*.jks` est ignoré par git). Si la clé est perdue, la version suivante ne pourra pas s'installer par-dessus l'ancienne.

### Passer d'un APK de debug à une release

Un APK de debug et un APK de release ne sont pas signés avec la même clé : pour installer la première release sur un téléphone qui a une version de debug, il faut d'abord désinstaller l'application (les favoris sont perdus). Ensuite, les releases successives s'installent par-dessus. Ne pas réinstaller un APK de debug par-dessus une release, pour la même raison.

### En local

Prérequis : JDK 17, Gradle 9.9.1 et Android SDK 37. Aucun wrapper Gradle n'est versionné, il faut donc utiliser un `gradle` installé sur la machine.

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

## Crédits

Données des stations : [Radio Browser](https://www.radio-browser.info/), annuaire communautaire et libre. L'application signale chaque écoute à l'API, comme celle-ci le demande, pour améliorer le classement des stations.
