# Compass Duel 🧭⚔️

A local, offline Android multiplayer game for 2–4 players that turns physical
device orientation into the core game mechanic. Aim your phone at opponents
using the magnetometer/gyroscope, then fire with a quick swing or raise a shield
by holding the phone upright and steady. All communication runs fully offline over the **Google Nearby
Connections API** (BLE + Wi-Fi P2P) — built for trains, cars and buses, no
internet required.

See [`docs/game-spec.md`](docs/game-spec.md) for the complete game concept and
technical specification.

## Game Modes

### Standard — Elemental Duel

Players choose an element (Fire, Water, Earth, Lightning) with classic
strength/weakness matchups. Each round is 90 seconds; players start with
100 HP. Attacks deal elemental damage; shields fully block but are limited to a
budget of half the round time. The last survivor wins the round. First to two round wins takes the
match. Exact ties on the 90 s timeout are decided by highest HP; a tie on HP
counts as a draw.

### Kids Mode — Star Catchers

No HP, no elimination. Players toss sparkles at friends to earn stars; holding
the phone flat activates a magic bubble that blocks incoming sparkles. Stars only
go up. A round runs for a fixed time (60 s) and every player stays in until the
end. At the end every player receives exactly one award (Star Champion, Bubble
Hero, Busy Bee, or Super Sparkler).

## Match Flow

```
Host / Join → Lobby (bow-to-greet handshake + element/sprite pick) →
3 s get-ready countdown → Round → Results / Rematch
```

1. One device hosts; others discover it automatically via BLE and join.
2. In the lobby each player greets every opponent: aim at them and bow (tilt the
   phone forward and back); they bow back to accept. This captures the relative
   bearings — no manual seat grid and no aim calibration. Then pick a character.
3. At round start a 3-second "get ready" countdown begins.
4. The round runs; the host is the sole authority for hit/catch evaluation and
   cancels shared vehicle rotation so aiming stays accurate as the car/train turns.
5. The results screen shows round winner, match score (Standard) or award cards
   (Kids). The host can start a rematch or leave.

## Runtime Permissions Required

The game requests these permissions at first launch:

- `BLUETOOTH_SCAN` — discover nearby devices
- `BLUETOOTH_ADVERTISE` — advertise as a host
- `BLUETOOTH_CONNECT` — establish connections
- `NEARBY_WIFI_DEVICES` — Wi-Fi Direct upgrade for larger payloads
- `ACTIVITY_RECOGNITION` — optional step detection to flag a player who leaves
  their seat mid-round; the game degrades to significant-motion-only if denied

The Bluetooth/Wi-Fi permissions are required by the Google Nearby Connections API
and are only used for local, offline peer-to-peer play.

## Deferred for a Future Release

The following features are specified but not implemented in v1:

- **Special attack** — double-shake gesture (schema reserved as `SPECIAL`; host treats it as `IDLE`)
- **2v2 teams** — Free-for-all only in v1
- **Shield interception** — 3–4 player shield-interception rule (spec §Special Rules)
- **Host migration** — if the host disconnects all clients return to the home screen
- **Sound effects** — haptic feedback only in v1

For younger players there is **Kids Mode ("Star Catchers")** — a friendly game
of magic tag with no damage, no elimination and an award for every child. See
[`docs/kids-mode-spec.md`](docs/kids-mode-spec.md).

## Tech Stack

- **Language:** Kotlin, JDK 17
- **UI:** Jetpack Compose (Material 3) + Canvas for the compass ring
- **DI:** Hilt (Dagger)
- **Networking:** Google Nearby Connections API (offline P2P: BLE + Wi-Fi)
- **Sensors:** Android `SensorManager` (`TYPE_ROTATION_VECTOR` + accelerometer)
- **Serialization:** kotlinx.serialization (compact game-state payloads)
- **Build:** Gradle with version catalog, AGP 9, convention plugins
- **CI/CD:** GitHub Actions, release-please (Conventional Commits), Gradle Play Publisher
- **Min SDK:** 35 (Android 15 — no backward compatibility below it)

Single source of truth for all versions: `gradle/libs.versions.toml`.

## Repository Structure

```
Compass-Duel/
├── app/                 The Android app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/justb81/compassduel/
│       ├── CompassDuelApp.kt   Hilt application entry point
│       ├── di/                 Hilt AppModule — Android framework + engine bindings
│       ├── game/               Pure game domain
│       │   ├── kids/           Kids Mode rules (catch eval, star scoring, awards)
│       │   ├── standard/       Standard Mode rules (DuelPlayer, AttackResult, MatchScore)
│       │   ├── gesture/        Gesture classifier (MotionSample, GestureClassifier)
│       │   └── engine/         Host-authoritative engine (GameEngine, ModeRuleSet)
│       ├── net/                Nearby transport layer (NearbyConnectionManager)
│       │   └── protocol/       Payload schema — NetMessage sealed hierarchy, MessageCodec
│       ├── sensor/             Sensor flows — OrientationSensor, ShakeDetector, InputPipeline
│       ├── session/            GameSession facade (host + client roles, SessionEvent)
│       ├── haptics/            HapticFeedback — mode-aware vibration wrapper
│       └── ui/
│           ├── navigation/     CompassDuelNavGraph, type-safe route objects, AppViewModel
│           ├── permissions/    NearbyPermissionsGate (Compose runtime permission flow)
│           ├── components/     CompassRing (Canvas), PlayerBadge
│           └── screens/
│               ├── home/       HomeScreen + HomeViewModel
│               ├── lobby/      LobbyScreen + LobbyViewModel
│               ├── game/       GameScreen + GameViewModel (COUNTDOWN / PLAYING / ROUND_OVER)
│               └── results/    ResultsScreen + ResultsViewModel (Standard + Kids awards)
├── build-logic/         Gradle convention plugins (included build)
│   └── convention/      compassduel.android.application + compassduel.detekt
├── config/detekt/       Shared detekt ruleset
├── docs/                game-spec.md, kids-mode-spec.md
├── scripts/             precommit.sh, validate-release-security.py
├── .github/
│   ├── actions/setup-android-build/   Composite action: checkout + JDK 17 + Gradle
│   ├── scripts/append-download-links.sh
│   └── workflows/
│       ├── build-android.yml   CI: test + detekt + lint + build on push/PR
│       └── release.yml         CD: release-please + signed APK/AAB + Play Store
└── gradle/libs.versions.toml   Version catalog
```

## Building

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew testDebugUnitTest       # unit tests
./gradlew detektAll               # static analysis
./gradlew :app:lintDebug          # Android Lint
```

Enable the shared pre-commit hook once per clone so CI-equivalent checks run
before every commit:

```bash
git config core.hooksPath .githooks
```

## CI/CD

- **`build-android.yml`** — runs unit tests, detekt and Android Lint, and
  uploads SARIF to GitHub code scanning on every push/PR.
- **`release.yml`** — release-please opens a release PR from Conventional
  Commits; merging it tags a release, builds a **signed** APK + AAB, attaches
  them (plus the R8 mapping) to the GitHub Release, and uploads the AAB to the
  Google Play **internal** track.

## 🔑 Required GitHub secrets

The pipeline is fully wired — you only need to add these repository secrets
(Settings → Secrets and variables → Actions). Until they are set, the release
workflow **skips signing/publishing gracefully** rather than failing.

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_BASE64` | Base64-encoded upload keystore (`base64 -w0 release.keystore`) |
| `KEYSTORE_PASSWORD` | Keystore store password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Signing key password |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service-account JSON with Play Android Developer API access (omit to skip Play upload) |

Signing credentials are written to `~/.gradle/gradle.properties` (chmod 600) at
build time and consumed via Gradle project properties (`compassduel.signing.*`),
so they never appear in environment dumps or logs.

## License

See [`LICENSE`](LICENSE).
