# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

SimpleRadio: minimal Android internet-radio player (Kotlin, Jetpack Compose, Media3/ExoPlayer). Station search via the public Radio Browser API, background playback with a media notification. UI strings and code comments are in French — keep it that way.

## Build

- No Gradle wrapper is committed: use a system `gradle` (8.9) with JDK 17 and Android SDK 34.
- `gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- CI (`.github/workflows/build.yml`) runs the same command on every push to `main` and uploads the APK as the `simple-radio-apk` artifact.
- Releases: pushing a `v*` tag runs `.github/workflows/release.yml`, which builds `assembleRelease` signed with a fixed key from repo secrets and attaches the APK to a GitHub Release. Signing, `versionName` (tag without the leading `v`) and `versionCode` (workflow run number) come from env vars read in `app/build.gradle.kts`, so a local `assembleRelease` without those vars fails at signing.
- There are no tests and no lint config.

## Architecture

Single module `:app`, package `fr.florian.radio`, three files:

- `RadioBrowser.kt` — blocking HTTP client (`HttpURLConnection` + `org.json`, no networking library) for api.radio-browser.info. Fails over across a hard-coded host list. Callers must run it on `Dispatchers.IO`. Also defines the `Station` model.
- `PlaybackService.kt` — `MediaSessionService` owning the `ExoPlayer` and `MediaSession`; Media3 builds the foreground notification automatically. The session callback `onAddMediaItems` restores the stream URI from `requestMetadata.mediaUri`, because a `MediaItem`'s local URI is dropped when sent from controller to session — so any code that builds a `MediaItem` must set both `setUri` and `RequestMetadata.mediaUri` (see `MainActivity.play`).
- `MainActivity.kt` — whole Compose UI. Talks to the service only through a `MediaController` connected in `onStart` / released in `onStop`; player state is mirrored into Compose state via `syncState`. Live radio: the play/pause button calls `stop()` (not `pause()`) so resuming restarts at the live edge.

Cleartext HTTP is allowed in the manifest (`usesCleartextTraffic`) since many streams are plain HTTP.
