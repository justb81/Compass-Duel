# Compass Duel 🧭⚔️

A local, offline Android multiplayer game for 2–4 players that turns physical
device orientation into the core game mechanic. Aim your phone at opponents
using the magnetometer/gyroscope, then attack, shield and dodge by tilting and
shaking. All communication runs fully offline over the **Google Nearby
Connections API** (BLE + Wi-Fi P2P) — built for trains, cars and buses, no
internet required.

See [`docs/game-spec.md`](docs/game-spec.md) for the complete game concept and
technical specification.

## Tech Stack

- **Language:** Kotlin, JDK 17
- **UI:** Jetpack Compose (Material 3) + Canvas for the compass ring
- **DI:** Hilt (Dagger)
- **Networking:** Google Nearby Connections API (offline P2P: BLE + Wi-Fi)
- **Sensors:** Android `SensorManager` (`TYPE_ROTATION_VECTOR` + accelerometer)
- **Serialization:** kotlinx.serialization (compact game-state payloads)
- **Build:** Gradle with version catalog, AGP 9, convention plugins
- **CI/CD:** GitHub Actions, release-please (Conventional Commits), Gradle Play Publisher
- **Min SDK:** 26 (Android 8.0 — Nearby Connections requirement)

Single source of truth for all versions: `gradle/libs.versions.toml`.

## Repository Structure

```
Compass-Duel/
├── app/                 The Android app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/justb81/compassduel/
│       ├── CompassDuelApp.kt   Hilt application entry point
│       ├── game/               Game domain (Element matchups, bearing/hit math)
│       └── ui/                 MainActivity, Compose theme & screens
├── build-logic/         Gradle convention plugins (included build)
│   └── convention/      compassduel.android.application + compassduel.detekt
├── config/detekt/       Shared detekt ruleset
├── docs/                game-spec.md
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
  them (plus native debug symbols and the R8 mapping) to the GitHub Release, and
  uploads the AAB to the Google Play **internal** track.

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
