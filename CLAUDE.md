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

A child-friendly variant, **Kids Mode ("Star Catchers")**, replaces combat
with magic tag: no HP/damage/elimination, stars only go up, every player gets
an end-of-round award. Spec: `docs/kids-mode-spec.md`; pure domain rules in
`app/.../game/kids/` (unit-tested).

## Repository Structure

```
Compass-Duel/
├── app/                 The Android app (single module)
│   ├── build.gradle.kts        Applies compassduel.android.application + .detekt; GPP config
│   ├── proguard-rules.pro
│   └── src/main/java/com/justb81/compassduel/
│       ├── CompassDuelApp.kt   @HiltAndroidApp entry point
│       ├── di/                 Hilt AppModule — Android framework + engine singleton bindings
│       ├── game/               Pure game domain — Element matchups, Bearing/hit math (unit-tested)
│       │   ├── kids/           Kids Mode domain — catch evaluation, star scoring, awards
│       │   ├── standard/       Standard Mode domain — DuelPlayer, AttackResult, MatchScore
│       │   ├── gesture/        Pure gesture classifier — MotionSample, GestureClassifier
│       │   └── engine/         Host-authoritative game engine — GameEngine, ModeRuleSet, rule sets
│       ├── net/                NearbyConnectionManager (MessageTransport impl)
│       │   └── protocol/       Nearby payload schema — NetMessage sealed hierarchy, MessageCodec
│       │                       (canonical source for all on-wire message types)
│       ├── sensor/             OrientationSensor, ShakeDetector, AimCalibration, InputPipeline
│       ├── session/            GameSession facade — host/client roles, SessionEvent, SessionRole
│       ├── haptics/            HapticFeedback — mode-aware vibration wrapper (Standard / Kids)
│       └── ui/
│           ├── navigation/     CompassDuelNavGraph — type-safe routes, AppViewModel, SessionEvent fan-out
│           ├── permissions/    NearbyPermissionsGate — Compose runtime-permission flow
│           ├── components/     CompassRing (Canvas), PlayerBadge, SeatGrid
│           └── screens/
│               ├── home/       HomeScreen + HomeViewModel
│               ├── lobby/      LobbyScreen + LobbyViewModel
│               ├── game/       GameScreen + GameViewModel (COUNTDOWN / PLAYING / ROUND_OVER)
│               └── results/    ResultsScreen + ResultsViewModel (Standard score + Kids awards)
├── build-logic/convention/     Included build with two convention plugins:
│       compassduel.android.application  compileSdk/Java 17/Hilt/KSP/signing/lint/test deps
│       compassduel.detekt               detekt wiring (shared ruleset + per-module baseline)
├── config/detekt/detekt.yml    Shared detekt ruleset
├── docs/                       game-spec.md, kids-mode-spec.md
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
- Gradle (AGP 9, version catalog, convention plugins), minSdk 35 (Android 15) / targetSdk 35 / compileSdk 37
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

#### CI-only failure modes (no SDK locally → these only surface in CI)

When the Gradle scope is skipped, CI is the sole gate for Kotlin. These recur —
check them before pushing to save a red-CI round-trip:

- **`@Volatile` applies to fields, not local variables.** A `var` captured by a
  closure (e.g. a `SensorEventListener` inside a `callbackFlow`) cannot be
  `@Volatile`; use `AtomicInteger`/`AtomicReference` for cross-thread visibility.
  `@Volatile` is valid only on class-level properties.
- **Test doubles need `open`.** `GameEngine` is `open` and injected via the
  `GameEngineFactory` fun-interface so tests can supply fakes. Any engine method
  a test overrides (`submitInput`, `roundOutcome`, …) must also be `open`, and a
  file-private test helper (e.g. `NoOpEngine`) must be `open` to be subclassed by
  an anonymous `object :` in another test.
- **detekt is strict and has no baseline** (`config/detekt/detekt.yml`). Frequent
  trip-ups: `ReturnCount` max 4 (extract per-branch helpers instead of stacking
  guard `return`s), `LargeClass` threshold 600 (split large suites or
  `@Suppress("LargeClass")` with a justifying comment), `NoUnusedImports`,
  `NoConsecutiveBlankLines`, and the spacing rules around
  annotated/commented declarations (`SpacingBetweenDeclarationsWith…`).
- **Host trust-boundary tests.** The client now rejects messages whose
  `endpointId != hostEndpointId` (and the host rejects host→client message
  types). Client-incoming tests must call `connectTo("host-ep")` to register the
  host endpoint before emitting host messages, or they are correctly dropped.

### Git Workflow

- **Never push directly to `main`.** All changes go through a PR.
- Branch from `main`; use Conventional Commits (`feat:`, `fix:`, `chore:`, …).
- Open a PR, wait for a green `Test & Build` check, then squash-merge.
- Branch naming: `feature/`, `fix/`, `docs/`, `chore/`. The `release-please--`
  prefix is reserved for the release-please bot.
- **To auto-close issues on merge, put `Closes #N` in the PR description — one
  keyword per issue.** A bare `(#N)` in a commit message only links, and
  `Closes #1, #2` closes only the first; use `Closes #1, closes #2, …`.

### Versioning & Release

- release-please with Conventional Commits; version in `.release-please-manifest.json`.
- `versionCode` derives from `github.run_number` in CI; `versionName` from release-please.
- Merging the release-please PR tags a release and triggers `release.yml`:
  signed APK + AAB → GitHub Release (with the R8 mapping) and the Play Store
  **internal** track via Gradle Play Publisher.
- The app ships **no native code**. Its only transitive `.so`
  (`libandroidx.graphics.path.so`, a pre-API-34 fast path from
  `androidx.graphics:graphics-path`) is excluded via `packaging.jniLibs` in
  `app/build.gradle.kts` — on minSdk 35 the platform `PathIterator` is used and
  the lib is never loaded. Excluding it stops Play Console flagging the bundle
  for missing native debug symbols (which can't be generated for a prebuilt
  stripped lib anyway). Do not re-add `ndk.debugSymbolLevel` / NDK install steps.

### Required CI secrets

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, and
`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. When unset, the release workflow skips
signing/publishing gracefully instead of failing.

## Documentation Maintenance

Keep `README.md`, `docs/game-spec.md`, and this `CLAUDE.md` in sync with the
code. When adding modules, permissions, payload fields, or CI steps, update the
relevant files in the same PR.
