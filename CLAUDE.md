# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

SimpleRadio: minimal Android internet-radio player (Kotlin, Jetpack Compose, Media3/ExoPlayer). Station search via the public Radio Browser API, background playback with a media notification. UI strings and code comments are in French — keep it that way.

## Build

- No Gradle wrapper is committed: use a system `gradle` (9.9.1) with JDK 17 and Android SDK 37 (compileSdk 37, targetSdk still 34). AGP 9 has built-in Kotlin: `org.jetbrains.kotlin.android` is only declared `apply false` in the root build to pin the Kotlin version, never applied in `:app`, and there is no `kotlinOptions` block (jvmTarget follows `compileOptions`).
- `gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- CI (`.github/workflows/build.yml`) runs the same command on every push to `main` and on every pull request, and uploads the APK as the `simple-radio-apk` artifact. On a PR it also posts (then edits) a bot comment linking to that artifact.
- Releases: pushing a `v*` tag runs `.github/workflows/release.yml`, which builds `assembleRelease` signed with a fixed key from repo secrets and attaches the APK to a GitHub Release. Signing, `versionName` (tag without the leading `v`) and `versionCode` (workflow run number) come from env vars read in `app/build.gradle.kts`, so a local `assembleRelease` without those vars fails at signing.
- Release procedure (full user-facing version in the README, "Publier une version"):
  - Tag must start with `v` (`git tag v1.1 && git push origin v1.1`). A tag like `1.0.0` triggers nothing.
  - A release published from the GitHub UI with a new `v*` tag also works: creating the tag fires the workflow, whose publish step uploads the APK to the existing release (`gh release upload --clobber`) instead of creating it. A release on a tag that already existed triggers nothing.
  - Required repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. `KEY_ALIAS` must match the alias inside the keystore exactly (`simpleradio`), otherwise "Build signed APK" fails.
  - To retry a failed release: "Re-run jobs" on the run, or delete and re-push the tag (`git push origin :refs/tags/vX` then `git push origin vX`).
  - Keystore generation without a JDK: OpenSSL `req -x509` + `pkcs12 -export -name simpleradio`. The resulting PKCS12 file works as `.jks`.
  - Debug and release APKs are signed with different keys. Switching a phone from a debug build to the first release requires uninstalling the app first; later releases update in place as long as the same keystore is used.
- There are no tests and no lint config.

## Architecture

Single module `:app`, package `fr.florian.radio`, three files:

- `RadioBrowser.kt` — blocking HTTP client (`HttpURLConnection` + `org.json`, no networking library) for api.radio-browser.info. Fails over across a hard-coded host list. `logo()` follows redirects manually (incl. http→https) and renders SVG with AndroidSVG; Logo requests use a browser User-Agent (some CDNs reject others). `Station.logoUrls` adds the homepage's `apple-touch-icon.png` / `favicon.ico`, then Google's favicon service, as fallbacks; `StationLogo` tries them in order, keeps the first image ≥ 64 px (else the largest), with a memory cache + per-session failure set. Callers must run it on `Dispatchers.IO`. Also defines the `Station` model and `Tags.display`, which cleans the raw directory tags at display time (drop formats/generic words, translate common English genres, max 2) so stored favorites benefit too.
- `PlaybackService.kt` — `MediaSessionService` owning the `ExoPlayer` and `MediaSession`; Media3 builds the foreground notification automatically. The session callback `onAddMediaItems` restores the stream URI from `requestMetadata.mediaUri`, because a `MediaItem`'s local URI is dropped when sent from controller to session — so any code that builds a `MediaItem` must set both `setUri` and `RequestMetadata.mediaUri` — always go through `Station.toMediaItem()`. `onPlaybackResumption` replays the last station (`LastStation`, saved in the same `radio` prefs as favorites) when a media button or the system media card asks to play with nothing loaded; `MediaButtonReceiver` is declared in the manifest for that. `onTaskRemoved` always stops the player and the service: swiping the app away from recents ends playback (screen off / other apps keep playing). `NowPlayingListener` copies ICY/ID3 track titles into the current item's `artist` (station description kept in `subtitle`).
- `MainActivity.kt` — whole Compose UI. Talks to the service only through a `MediaController` connected in `onStart` / released in `onStop`; player state is mirrored into Compose state via `syncState`. Live radio: the play/pause button calls `stop()` (not `pause()`) so resuming restarts at the live edge.

Cleartext HTTP is allowed in the manifest (`usesCleartextTraffic`) since many streams are plain HTTP.

Version label: debug builds get a `-debug` versionName suffix, plus `.<GITHUB_RUN_NUMBER>` on CI. `buildConfig` is enabled so the UI shows `BuildConfig.VERSION_NAME` next to the title.
