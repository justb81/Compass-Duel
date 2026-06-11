# CLAUDE.md — Compass Duel Agent Guide

This file provides context for AI coding agents so they can work effectively in
this repository without re-analyzing the entire codebase each time.

## Project Overview

Compass Duel is a local, offline Android multiplayer game (2–4 players) that
uses physical device orientation as its core mechanic. Players aim their phone
at opponents via the magnetometer/gyroscope and attack, shield or dodge by
tilting and shaking. All networking runs offline over the **Google Nearby
Connections API** (P2P_STAR topology: one Host + N Clients). The Host is
authoritative for hit detection — clients never decide hits, which prevents
cheating. See `docs/game-spec.md` for the full concept and technical spec.

## Repository Structure

```
Compass-Duel/
├── app/                 The Android app (single module)
│   ├── build.gradle.kts        Applies compassduel.android.application + .detekt; GPP config
│   ├── proguard-rules.pro
│   └── src/main/java/com/justb81/compassduel/
│       ├── CompassDuelApp.kt   @HiltAndroidApp entry point
│       ├── game/               Pure game domain — Element matchups, Bearing/hit math (unit-tested)
│       └── ui/                 MainActivity (@AndroidEntryPoint), Compose theme
├── build-logic/convention/     Included build with two convention plugins:
│       compassduel.android.application  compileSdk/Java 17/Hilt/KSP/signing/lint/test deps
│       compassduel.detekt               detekt wiring (shared ruleset + per-module baseline)
├── config/detekt/detekt.yml    Shared detekt ruleset
├── docs/game-spec.md
├── scripts/
│   ├── precommit.sh                  Scoped local mirror of CI checks
│   └── validate-release-security.py  Validates release.yml signing-secret hygiene
├── .githooks/pre-commit        Delegates to scripts/precommit.sh
├── .github/
│   ├── actions/setup-android-build/action.yml   Composite: checkout + JDK 17 + Gradle
│   ├── scripts/append-download-links.sh
│   └── workflows/{build-android,release}.yml
└── gradle/libs.versions.toml   Version catalog (single source of truth)
```

## Tech Stack

- Kotlin, JDK 17, Jetpack Compose (Material 3), Hilt
- Google Nearby Connections API (`com.google.android.gms:play-services-nearby`)
- Android `SensorManager` (`TYPE_ROTATION_VECTOR` + accelerometer)
- kotlinx.serialization (compact payloads), kotlinx.coroutines
- Gradle (AGP 9, version catalog, convention plugins), minSdk 26 / targetSdk 35 / compileSdk 37
- CI/CD: GitHub Actions, release-please, Gradle Play Publisher

## Key Conventions

### Language — MANDATORY

All content in this repository must be written in **English**: code, comments,
commit messages, PR titles/descriptions, issues, and Markdown. The only
exception is localized string resources (`values-de/`); the default
`values/strings.xml` stays English. Supported languages: English (default) and
German. Additional languages may be added later.

### Active Early Development — No Cross-Version Fallbacks

The app ships from a single module. The Nearby Connections payload format and
game-state schema support only the current version — do not add legacy schema
fallbacks or version negotiation. When changing a shared payload/format, update
every producer and consumer in the same commit.

### Code Style

- Kotlin official code style (`kotlin.code.style=official`)
- Single-Activity; screen-level Composables; ViewModels expose `StateFlow`
- Hilt `@HiltViewModel` for ViewModels; dependencies provided via Hilt `@Module`/`@Provides`

### Build

- `./gradlew :app:assembleDebug` — debug APK
- `./gradlew testDebugUnitTest` — unit tests (JUnit 5)
- `./gradlew detektAll` — detekt on every module (config: `config/detekt/detekt.yml`; baselines: `<module>/detekt-baseline.xml`)
- `./gradlew :app:lintDebug` — Android Lint (baseline: `app/lint-baseline.xml` when present)
- Secrets via `local.properties` (not checked in) or CI environment variables.

**Release signing credentials** are passed via Gradle project properties
(`compassduel.signing.*`), not environment variables. CI writes them to
`~/.gradle/gradle.properties` (chmod 600) in `release.yml`. For local release
builds add them to your personal `~/.gradle/gradle.properties`:

```
compassduel.signing.storePassword=<keystore password>
compassduel.signing.keyAlias=<key alias>
compassduel.signing.keyPassword=<key password>
```

The keystore path is supplied via the `KEYSTORE_FILE` environment variable (not
a secret). The convention plugin reads the signing properties via
`providers.gradleProperty("compassduel.signing.*")`.

### Local pre-commit checks

`scripts/precommit.sh` runs the same test / detekt / Android Lint / workflow-YAML
checks CI runs, scoped to whatever is staged (mirrors the `paths-filter` in
`build-android.yml`). Run it before every commit, or enable the shared hook once
per clone:

```sh
git config core.hooksPath .githooks
```

**Scoping rules** (must match the CI `paths-filter`):

- `app/**`, `build-logic/**`, `*.gradle.kts`, `gradle/**`, `gradle.properties`, `config/detekt/**`, `.github/actions/**` → `./gradlew test detektAll :app:lintDebug`
- `.github/workflows/*.y?ml` → `python3 yaml.safe_load` on each changed file + `actionlint` when installed + `validate-release-security.py` when `release.yml` is staged

**Sandboxed environments without the Android SDK** (Claude Code on the web,
ephemeral runners): `precommit.sh` detects a missing SDK and **skips only the
Gradle scope** with a loud warning, while still running the workflow-YAML
checks. The commit proceeds and CI (`build-android.yml`) becomes the real gate
for Kotlin/Android changes. In that situation, surface the skip in the PR
description and treat a red `Test & Build` check as a blocker. Do **not** pass
`--no-verify`.

### Git Workflow

- **Never push directly to `main`.** All changes go through a PR.
- Branch from `main`; use Conventional Commits (`feat:`, `fix:`, `chore:`, …).
- Open a PR, wait for a green `Test & Build` check, then squash-merge.
- Branch naming: `feature/`, `fix/`, `docs/`, `chore/`. The `release-please--`
  prefix is reserved for the release-please bot.

### Versioning & Release

- release-please with Conventional Commits; version in `.release-please-manifest.json`.
- `versionCode` derives from `github.run_number` in CI; `versionName` from release-please.
- Merging the release-please PR tags a release and triggers `release.yml`:
  signed APK + AAB → GitHub Release (with native debug symbols + R8 mapping) and
  the Play Store **internal** track via Gradle Play Publisher.

### Required CI secrets

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and
`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. When unset, the release workflow skips
signing/publishing gracefully instead of failing.

## Documentation Maintenance

Keep `README.md`, `docs/game-spec.md`, and this `CLAUDE.md` in sync with the
code. When adding modules, permissions, payload fields, or CI steps, update the
relevant files in the same PR.
