# SimpleRadio

Lecteur radio Android minimal : recherche via l'API Radio Browser, lecture en arrière-plan (Media3/ExoPlayer), notification avec contrôles.

## Build via GitHub Actions
1. Pousse ce dossier sur un dépôt GitHub (branche `main`).
2. Onglet **Actions** → workflow **Build APK** (se lance au push, ou via *Run workflow*).
3. Télécharge l'artefact `simple-radio-apk` → `app-debug.apk`.
4. Installe sur le téléphone (autoriser les sources inconnues).

## Build local
`gradle assembleDebug` (Gradle 8.9 + JDK 17 + Android SDK 34)
