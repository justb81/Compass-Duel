# Compass Duel ðŸ§­âš”ï¸
### Complete Game Concept & Technical Specification

***

## Executive Summary

**Compass Duel** is a local Android multiplayer game for 2â€“4 players that uses physical device orientation as its primary game mechanic. Players aim their smartphone at opponents using the built-in magnetometer and gyroscope, then execute attacks, defensive moves, and special abilities by tilting the device. All communication runs fully offline via the **Google Nearby Connections API** (BLE + Wi-Fi P2P). The game works on trains, in cars, or on buses â€” no internet connection required, no large body movements needed.

***

## Game Concept

### Vision

Every player holds their smartphone like a **magic wand or flashlight**. The screen shows in which direction opponents are sitting relative to you. To attack an opponent, you physically tilt your phone in their direction and trigger an action. Whoever twitches their phone first has attacked â€” but whoever keeps their phone flat is in shield mode. The tension comes from ambiguity: everyone can see who is aiming at whom.

### Thematic Setting: "Elemental Duel"

Each player chooses an element (Fire ðŸ”¥, Water ðŸ’§, Earth ðŸŒ¿, Lightning âš¡). Elements have classic strength/weakness relationships:

| Attacker | Strong Against | Weak Against |
|---|---|---|
| Fire ðŸ”¥ | Earth | Water |
| Water ðŸ’§ | Fire | Lightning |
| Earth ðŸŒ¿ | Lightning | Fire |
| Lightning âš¡ | Water | Earth |

The chosen element also determines the attack gesture (see Game Mechanics).

***

## Game Mechanics

### Core Principle

1. At game start, one player becomes the **Host**. All others join via automatic BLE discovery.
2. Players perform a **"bow to greet" handshake**: each player aims at every opponent and bows (tilts the phone forward and back), capturing the absolute bearing toward them. The Host stores these bearings and uses them for hit detection. No manual seat entry and no aim calibration.
3. Each player's screen displays a **compass ring** with colored markers for each opponent â€” corresponding to their real position in the space.
4. A **targeting reticle** (based on current phone orientation) rotates live. When it points at an opponent within a tolerance angle â†’ opponent is locked on.

### Actions

| Action | Gesture | Description |
|---|---|---|
| **Fire** | Aim at an opponent + a quick swing/jerk | Deals damage if the opponent is in the targeting zone; a swing also drops the shield |
| **Shield** | Hold the phone upright (screen toward you) and steady for >1 s | Fully blocks incoming attacks; limited to a **per-round budget of 50% of the round time** |
| **Special** | Double quick shake | Element-specific attack (reserved, not yet implemented) |

The shield has a **time budget equal to half the round duration**. Holding the
shield consumes the budget; once it is exhausted the shield can no longer be
held for the rest of the round. The remaining budget and the <1 s arming
progress are shown by a shield indicator in the center of the compass.

#### Touch controls (accessibility)

For players who find the sustained upright-hold and the fire swing tiring, the
game screen always offers on-screen alternatives **alongside** the motion
gestures (no toggle — use whichever fits at any moment): **double-tap the play
area to Fire** and **press-and-hold the shield button to Shield**. Aiming stays
motion-based. Touch and gesture inputs are folded into the same client→host
`PlayerInput` stream (`InputPipeline`), so the Host treats them identically — the
50%-of-round shield budget and authoritative hit detection are unchanged.

### Hit Detection

A hit is only registered when:
- The attacker's **azimuth angle** is within **Â±25Â°** of the actual bearing toward the target [^1]
- A **swing/jerk** (acceleration spike above the fire threshold) is detected [^2]
- The attack arrives at the Host within **200ms** of the gesture timestamp [^3]

All inputs are evaluated centrally on the Host device â€” no client-side hit detection, preventing cheating.

### Round Structure

```
Lobby (greeting handshake) â†’ Get Ready (3s) â†’ Combat Phase (30/60/90s) â†’ Results â†’ Rematch?
```

- **Combat Phase:** Each player starts with 100 HP. First to reach 0 is eliminated.
  The host picks the round length (30/60/90 s) in the lobby; the shield budget is always
  half the round.
- **Last survivor** wins the round.
- **Series:** the host picks best of one, three, or five round wins â†’ overall winner.

### Special Rules for 3â€“4 Players

- Players can "block" each other: a player in shield mode absorbs attacks that would fly "through" them.
- **Free-for-All:** Everyone targets everyone. Optional: 2v2 team mode with shared HP pools.
- The screen shows when someone is aiming at you (warning indicator) â€” giving time to raise the shield.

***

## Technical Architecture

### Overview

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚           HOST (Player 1)           â”‚
â”‚  â€¢ Game State Manager               â”‚
â”‚  â€¢ Hit Detection Engine             â”‚
â”‚  â€¢ Compass/Position Registry        â”‚
â”‚  â€¢ Nearby Connections Advertiser    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
             â”‚ Nearby Connections (BLE P2P, low power)
    â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”
    â”‚                 â”‚
â”Œâ”€â”€â”€â–¼â”€â”€â”€â”         â”Œâ”€â”€â”€â–¼â”€â”€â”€â”
â”‚Client â”‚         â”‚Client â”‚
â”‚Player2â”‚  ...    â”‚Player4â”‚
â”‚Sensor â”‚         â”‚Sensor â”‚
â”‚Input  â”‚         â”‚Input  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”˜         â””â”€â”€â”€â”€â”€â”€â”€â”˜
```

**Data flow per frame (~100ms tick):**
1. Each client reads sensor data (azimuth, pitch, roll) + registers gesture events
2. Client sends a compact input payload to Host (`BYTES` payload, ~50 bytes)
3. Host evaluates all inputs, calculates new game state
4. Host broadcasts game state to all clients (~200 bytes)
5. Clients render their respective screens

### Nearby Connections API

The **Google Nearby Connections API** serves as the communication layer. It abstracts Bluetooth Classic, BLE, and Wi-Fi behind a unified peer-to-peer API and works completely offline [^4]. The recommended topology is **P2P_STAR**: one hub (Host) accepts connections from up to N spokes (Clients) [^5].

All advertise/discover/connect calls are pinned to **low power** (`setLowPower(true)`), which constrains Nearby to the **BLE** medium and forgoes the automatic Wi-Fi/Bluetooth-Classic bandwidth upgrade. The upgrade negotiation raises an OS Bluetooth pairing/coupling dialog on join, which breaks the game's flow; BLE-only is sufficient because the game is same-room and exchanges only small, event-based `BYTES` payloads.

```kotlin
// Host: Start advertising
connectionsClient.startAdvertising(
    localEndpointName,
    SERVICE_ID,
    connectionLifecycleCallback,
    AdvertisingOptions.Builder()
        .setStrategy(Strategy.P2P_STAR)
        .setLowPower(true)
        .build()
)

// Client: Start discovery
connectionsClient.startDiscovery(
    SERVICE_ID,
    endpointDiscoveryCallback,
    DiscoveryOptions.Builder()
        .setStrategy(Strategy.P2P_STAR)
        .setLowPower(true)
        .build()
)

// Send payload (Host â†’ all Clients)
connectionsClient.sendPayload(
    endpointIds,  // List of all connected endpoints
    Payload.fromBytes(gameStateBytes)
)
```

Initial connection setup takes 2â€“7 seconds [^6]. After that, latency for small `BYTES` payloads over BLE (~20ms) is fully acceptable [^3]. Game actions don't require a continuous data stream â€” only event-based payloads â€” which significantly relaxes latency requirements.

#### Reliable control delivery

Nearby `BYTES` delivery is best-effort, so the two message classes are treated differently.
The high-frequency `StateBroadcast` stream (~10 Hz) stays lossy — a dropped snapshot is
superseded by the next one. One-shot **control messages** (`RoundStart`, `RoundEnd`,
`LobbyState`, `Rematch`, `Regreet`, and the lobby-setup client→host messages) cannot be
reconstructed, so `ReliableMessageTransport` wraps them in a sequenced `Reliable` envelope:
the receiver returns a `ControlAck` and delivers each sequence number exactly once, while the
sender retransmits until the ack arrives (or the endpoint drops). This prevents a lost
`RoundStart`/`RoundEnd` from stranding a client on the wrong screen.

#### Disconnects and reconnects

A mid-round disconnect no longer aborts an otherwise-viable match. The Host holds the dropped
player's seat through a short reconnect grace window (the player simply goes idle); if they
rejoin in time they resume the round, otherwise the Host forfeits them and **continues the
round when at least `MIN_PLAYERS` remain**, ending the match only when too few players are
left. On the Client, a lost Host link opens a matching grace window that re-discovers and
re-requests the Host before falling back to a terminal "connection lost". Host *migration*
(promoting a Client to Host) remains out of scope.

### Sensor Stack

#### Compass (Azimuth)

Phone orientation is determined via **`Sensor.TYPE_ROTATION_VECTOR`** â€” a software fusion sensor combining accelerometer, magnetometer, and gyroscope, significantly more stable than raw `TYPE_MAGNETIC_FIELD` [^7]. Output is a quaternion from which azimuth (north = 0Â°), pitch, and roll are extracted via `SensorManager.getRotationMatrix()` + `SensorManager.getOrientation()` [^1].

```kotlin
sensorManager.registerListener(
    sensorEventListener,
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
    SensorManager.SENSOR_DELAY_GAME  // ~50ms update rate
)

// In onSensorChanged():
val rotationMatrix = FloatArray(9)
SensorManager.getRotationMatrix(rotationMatrix, null, event.values)

// Remap coordinate system for portrait-held phone
val remappedMatrix = FloatArray(9)
SensorManager.remapCoordinateSystem(
    rotationMatrix,
    SensorManager.AXIS_X,
    SensorManager.AXIS_Z,
    remappedMatrix
)

val orientation = FloatArray(3)
SensorManager.getOrientation(remappedMatrix, orientation)
// orientation = Azimuth (compass direction in radians)
// orientation[^1] = Pitch (forward/backward tilt)
// orientation[^2] = Roll (sideways tilt)
```

Important: `remapCoordinateSystem()` is required when the device is not lying flat on a table â€” when held upright, the coordinate system must be adjusted [^2][^8].

#### Gesture Detection (Fire & Shield)

Two gestures are detected from the fused orientation + accelerometer stream
(`GestureClassifier`):

- **Fire** — a swing/jerk: an acceleration spike above the fire threshold
  (`~2.5 m/s²` Standard, `~1.5` Kids), debounced. The current aim azimuth selects
  the target; a fire also drops the shield.
- **Shield** — held upright and steady: `|pitch| ≤ 25°` (upright, screen toward
  the player) **and** linear acceleration `≤ ~1.2 m/s²` (steady) sustained for
  `>1 s`. It stays active while upright until a fire swing or leaving the upright
  band. Subject to the per-round 50%-of-round time budget (host-enforced).

`InputPipeline` also folds the **touch controls** into this same stream: a
double-tap emits an `ATTACK` with the latest aim, and the held shield button is
OR-ed with the gesture shield in the 100 ms cadence emission. The host is
source-agnostic — it only sees the resulting `PlayerInput` actions.

```kotlin
// Swing detection via acceleration delta
val acceleration = sqrt(x*x + y*y + z*z) - SensorManager.GRAVITY_EARTH
if (acceleration >= FIRE_SWING_THRESHOLD) {  // ~2.5 m/s²
    onFire(currentAzimuth)
}
```

#### Magnetometer Interference in Vehicles

Vehicle bodies and electric motors can interfere with the magnetometer [^9][^10]. The "bow to greet" handshake handles this robustly:

1. **Per-device self-cancellation of static distortion:** hit detection compares each phone's live aim against a bearing *that same phone captured during its bow* — both in the same magnetic frame. Any static distortion the frame carries (hard-iron offset, soft-iron warping, the vehicle's constant bias) is present in both halves and cancels when the player points back at the target.
2. **Common-mode rotation compensation:** when the whole vehicle turns, every phone's heading shifts together. The Host estimates this shared rotation as the median of all players' heading drift since round start (rejecting the few who are actively aiming) and subtracts it before hit detection. See [`CommonModeEstimator`](../app/src/main/java/com/justb81/compassduel/game/CommonModeEstimator.kt).

The only residual effect is a vehicle turning *between* greeting and the round, or a player physically relocating; both are handled by a cheap re-greet. If a player gets up and walks (detected via step / significant-motion sensors) they forfeit the current round and must re-greet before the next.

### Payload Format

**Client â†’ Host (Input Payload, ~48 bytes):**
```json
{
  "pid": 2,           // Player ID
  "az": 187.3,        // Azimuth in degrees
  "pt": 32.1,         // Pitch in degrees
  "rl": -5.2,         // Roll in degrees
  "act": "ATTACK",    // IDLE | ATTACK | SHIELD | SPECIAL
  "ts": 1718124523445 // Timestamp (ms since epoch)
}
```

**Host â†’ all Clients (Game State, ~120 bytes):**
```json
{
  "seq": 4821,
  "players": [
    { "pid": 1, "hp": 85, "status": "SHIELDING" },
    { "pid": 2, "hp": 60, "status": "ATTACKING", "target": 3 },
    { "pid": 3, "hp": 100, "status": "IDLE" }
  ],
  "events": ["HIT:2â†’3:15", "MISS:1â†’2"],
  "ts": 1718124523500
}
```

### Hit Detection Algorithm (Host)

```kotlin
fun evaluateAttack(attacker: Player, target: Player, azimuth: Float): HitResult {
    // The bearing was captured during the greeting handshake (attacker bowing at target);
    // no geometry is computed from positions.
    val requiredBearing = bearings[attacker.id][target.id]
    val angleDiff = abs(normalizeAngle(azimuth - requiredBearing))

    if (angleDiff > TOLERANCE_DEGREES) return HitResult.MISS  // outside Â±25Â°

    if (target.status == PlayerStatus.SHIELDING) return HitResult.BLOCKED

    val baseDamage = attacker.element.getAttackDamage()
    val modifier = getElementModifier(attacker.element, target.element)
    val finalDamage = (baseDamage * modifier).roundToInt()

    return HitResult.HIT(finalDamage)
}
```

***

## Player Positions: the "Bow to Greet" handshake

v1 establishes relative bearings with a diegetic greeting handshake (a refinement of the
old "calibration round" idea). To join the table, a player aims at each opponent and bows
— tilts the phone forward and back — and the opponent bows back to accept. Each bow
captures the absolute azimuth from the bowing phone toward the opponent at bow onset
(while still aimed; the bottom of the tilt faces the floor and is discarded). The Host
assembles a bearing matrix `actorId → (targetId → degrees)` and a match starts only once
every ordered pair has greeted. See
[`BowDetector`](../app/src/main/java/com/justb81/compassduel/game/gesture/BowDetector.kt).

Because each phone's captured bearing and its live aim share one magnetic frame, static
vehicle distortion cancels per device, and shared (vehicle-turn) rotation is removed by the
[`CommonModeEstimator`](../app/src/main/java/com/justb81/compassduel/game/CommonModeEstimator.kt).

### Superseded alternatives

- **Manual seat grid:** a 3×3 tap-to-seat UI. Removed — the bow handshake needs no manual
  entry and is at least as robust to magnetometer distortion.
- **BLE-RSSI triangulation (experimental):** signal strength as a distance estimate [^11];
  ±1–2 m accuracy. Not used — too imprecise for an aiming game.

***

## UI/UX Design

### Main Game Screen

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  HP: â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–‘â–‘  80/100     â”‚
â”‚  Element: ðŸ”¥ Fire            â”‚
â”‚                             â”‚
â”‚         [Compass Ring]      â”‚
â”‚    N                        â”‚
â”‚  W  +  E    â† Targeting     â”‚
â”‚    S                        â”‚
â”‚  â— Player2 (red)  â€“ 45Â° SW  â”‚
â”‚  â— Player3 (blue) â€“ 180Â° S  â”‚
â”‚                             â”‚
â”‚  [Target: Player2 locked!]  â”‚
â”‚  â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ â”‚
â”‚  Status: READY TO ATTACK    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

- The **compass ring** rotates with the phone in real time
- **Colored dots** mark opponent positions
- A **red targeting frame** appears when an opponent is within the Â±25Â° cone
- **Vibration** triggers on hit, incoming attack, and successful shield

### Feedback System

| Event | Visual | Haptic | Audio |
|---|---|---|---|
| Hit landed | Screen flashes green | Short buzz | Hit sound |
| Hit received | Screen flashes red + HP drops | Long buzz | Impact sound |
| Attack blocked | Shield particle effect | Double buzz | Clang |
| Opponent knocked out | Explosion overlay | Rhythmic buzz | KO sound |
| In someone's crosshairs | Pulsing red ring | Light pulse | Warning tone |

***

## Tech Stack & Implementation Plan

### Recommended Stack

| Component | Technology | Rationale |
|---|---|---|
| Language | Kotlin | Native Android, best sensor API support |
| UI | Jetpack Compose + Canvas | Compass ring as Custom Canvas, rest in Compose |
| Networking | Nearby Connections API (Google Play Services) | Offline P2P, BLE-only (low power), easy integration [^4] |
| Sensors | Android SensorManager | TYPE_ROTATION_VECTOR [^7] + Accelerometer |
| Build | Gradle + Android Studio | Standard toolchain |
| Min SDK | API 26 (Android 8.0) | Nearby Connections requirement |

### Phase Plan

#### Phase 1: Foundation (Weekend 1, ~8h)
- [ ] Nearby Connections setup: establish Host/Client connection [^5]
- [ ] Basic compass screen with live-rotating ring
- [ ] Define payload format + send/receive
- [x] Bow-to-greet handshake for relative bearings

#### Phase 2: Game Logic (Weekend 2, ~10h)
- [ ] Tilt/shake gesture detection
- [ ] Hit detection algorithm on Host
- [ ] HP system + game state sync
- [ ] Shield mechanics (upright hold + 50%-of-round budget)

#### Phase 3: UX & Polish (Weekend 3, ~8h)
- [ ] Vibration feedback via `Vibrator`/`VibrationEffect`
- [ ] Element system + damage multipliers
- [ ] Lobby screen + matchmaking flow
- [x] Common-mode rotation compensation + movement forfeit/re-greet

#### Phase 4: Testing & Tuning (ongoing)
- [ ] Extensive in-vehicle and train testing
- [ ] Balance tolerance angles and damage values
- [ ] Harden magnetometer fallback behavior

***

## Known Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Magnetometer interference in car | Medium | Static distortion self-cancels per device in the bow handshake; vehicle-turn rotation removed by common-mode estimation [^9][^10] |
| Nearby Connections discovery too slow | Low | Connect once at start, keep session open throughout [^6] |
| Inconsistent BLE in moving train | Low | BYTES payloads are small and event-based, well within BLE throughput; the connection is pinned to low-power BLE on purpose (the Wi-Fi/Bluetooth-Classic upgrade raises an OS pairing dialog on join) [^12] |
| Different coordinate systems per phone orientation | Medium | Implement `remapCoordinateSystem()` for all device orientations [^8][^13] |
| Sensor accuracy warning (SENSOR_STATUS_UNRELIABLE) | Low | Check at game start, prompt user to perform figure-8 calibration [^14] |

***

## Extension Ideas

- **Spectator Mode:** Observers see the direction arrows of all players live
- **Power-Ups:** Randomly appearing bonuses (e.g., "Firewall: next attack hits from any direction") collected by quick shake
- **Campaign Mode:** One player acts as Boss, all others cooperate against them
- **Sound Spatialization:** Sounds come from the opponent's direction (via stereo panning)
- **AR Overlay:** Camera view with AR markers for opponent positions (requires camera permission)

***

## Summary

Compass Duel combines **physical interaction** (compass orientation, tilt gestures), **offline P2P networking** (Nearby Connections), and **tactical gameplay** (elements, shield) into a game explicitly designed for mobile use in moving vehicles. The biggest technical challenge â€” magnetometer interference â€” is mitigated by robust fallback strategies. A first playable prototype is realistically achievable in 2â€“3 weekends.

---

## References

1. [SensorManager.GetOrientation(Single[], Single[]) Method (Android ...](https://learn.microsoft.com/en-us/dotnet/api/android.hardware.sensormanager.getorientation?view=net-android-35.0) - Computes the device's orientation based on the rotation matrix. When it returns, the array values ar...

2. [Android SensorManager strange how to remapCoordinateSystem](https://stackoverflow.com/questions/18782829/android-sensormanager-strange-how-to-remapcoordinatesystem) - API Demos -> Graphics -> Compass It works properly only, until you don't change the device natural o...

3. [PaperUsing Bluetooth on Android Devices to Implement Real-Time Multiplayer Games](https://www.slideshare.net/slideshow/paperusing-bluetooth-on-android-devices-to-implement-realtime-multiplayer-games/15720444) - The document discusses a study conducted by undergraduate computer science students to research the ...

4. [Nearby Connections - Google for Developers](https://developers.google.com/nearby/overview)

5. [GitHub - riontech-xten/NearByConnectionAPI: P2P_STAR: NearBy Connection API sample code of persistence connection across the activities with chat implementation](https://github.com/riontech-xten/NearByConnectionAPI) - P2P_STAR: NearBy Connection API sample code of persistence connection across the activities with cha...

6. [How can I speed up Nearby Connections API discovery?](https://stackoverflow.com/questions/52825617/how-can-i-speed-up-nearby-connections-api-discovery) - Unfortunately, the best you can do is to try to connect in one direction. That should lower the conn...

7. [Difference between Sensor.TYPE_ROTATION_VECTOR and getOrientation by combining TYPE_ACCELEROMETER and TYPE_MAGNETIC_FIELD](https://stackoverflow.com/questions/37531143/difference-between-sensor-type-rotation-vector-and-getorientation-by-combining-t) - I'm developing an application which steers a RC Car according to the actual position of a mobile pho...

8. [SensorManager.RemapCoordinateSystem(Single[], Axis ...](https://learn.microsoft.com/en-us/dotnet/api/android.hardware.sensormanager.remapcoordinatesystem?view=net-android-35.0) - Rotates the supplied rotation matrix so it is expressed in a different coordinate system.

9. [Magnetometers, accelerometers, and how to calibrate them on your ...](https://stonekick.com/blog/magnometers-accelerometers-and-calibrating-your-android-device.html) - How to calibrate the accelerometer and magnetometer sensors on your phone.

10. [Smartphone Compass Inaccurate? Here's How to Fix It](https://eathealthy365.com/your-ultimate-guide-to-smartphone-compass-accuracy-issues/) - Is your smartphone compass unreliable? Learn why it fails from magnetic interference and get step-by...

11. [Distance Calculations - Android Beacon Libraryaltbeacon.github.io â€º android-beacon-library â€º distance-calculations](https://altbeacon.github.io/android-beacon-library/distance-calculations.html) - Android Beacon Library : An Android library providing APIs to interact with Beacons

12. [Android Nearby Connection send payload partially wifi aware and bluetooth](https://stackoverflow.com/questions/71890294/android-nearby-connection-send-payload-partially-wifi-aware-and-bluetooth) - I'm sending image payload between two devices using P2P_POINT_TO_POINT (STAR too and every payload t...

13. [Remapping sensor coordinates](https://stackoverflow.com/questions/10918948/remapping-sensor-coordinates/17813154) - What I want to happen, is to remap the coordinate system, when the phone is turned away from it's "n...

14. [How to Calibrate the Compass on Android to Improve Device](https://smartupworld.com/how-to-calibrate-the-compass-on-android-to-improve-device/) - Modern Android smartphones come equipped with a variety of sensors, one of which is the magnetometer...

---

## v1 Implementation Notes

This section documents deviations and design decisions from the spec above that
were made during the v1 implementation (see `app/src/main/java/com/justb81/compassduel/`).

### Attack cooldown replaces cross-device timestamp freshness

The spec (§Hit Detection, footnote 3) requires the host to reject attack inputs
that arrive more than 200 ms after the gesture timestamp. Because device clocks
are not synchronised across phones, comparing `clientTimeMillis` to the host's
clock is unreliable. Instead, v1 enforces a **700 ms per-player attack cooldown**
on the host (`StandardRules.ATTACK_COOLDOWN_MILLIS`). The `clientTimeMillis` field
is included in the payload for diagnostics only and is never used for timing
decisions.

### Simplified gestures: hold-upright Shield + swing-to-Fire; Dodge removed

The original tilt-forward+shake attack, flat-hold shield, and reverse-tilt dodge
proved hard to trigger reliably. v1 ships a simpler two-gesture model
(`GestureClassifier`): **Fire** is a quick swing/jerk that fires toward the
current aim and drops the shield; **Shield** is activated by holding the phone
upright and steady for >1 s. **Dodge has been removed entirely** — there is no
`PlayerAction.DODGE`, `PlayerStatus.DODGING`, or `GameEventType.DODGED`.

The shield is limited by a **per-round time budget of 50% of the round duration**
(`StandardRules.SHIELD_BUDGET_MILLIS`), consumed by the host while a player
shields and reported per player as `PlayerSnapshot.shieldRemainingMillis`. The
client renders a center-of-compass `ShieldIndicator` showing the <1 s arming
progress, the active state, and the remaining budget.

### Special attack deferred

The `SPECIAL` action is reserved in the `PlayerAction` enum and the payload schema
(`net/protocol/Messages.kt`), but the host `StandardRuleSet` treats it as `IDLE` in
v1. The double-shake gesture is not yet detected by `GestureClassifier`.

### 3–4 player shield interception deferred

The spec §Special Rules describes a shield-interception rule where a shielding
player absorbs attacks that cross through their position toward another player.
This is not implemented in v1; only the direct attacker–target pair is evaluated.

### Position & aim — the greeting handshake (no calibration)

v1 establishes relative bearings via the bow-to-greet handshake in the lobby
(`BowDetector` → `GameSession.bearingMatrix`), and reports **raw** absolute azimuth as
aim during the round (`InputPipeline`). There is no seat grid and no facing-offset
calibration: the host hit-check is `Bearing.isOnTarget(rawAzimuth, bearing[actor][target])`,
where both quantities live in the same per-device magnetic frame so distortion cancels.
The COUNTDOWN phase is now just a 3-second "get ready" window. Vehicle-turn drift is
removed by `CommonModeEstimator`; a player who physically leaves their seat (detected by
`MovementDetector` → `MovementPolicy`) forfeits the round and must re-greet.

### Standard-mode timeout outcome

When the round timer (30/60/90 s, host-selected) expires with more than one player
still alive, the player with the highest HP wins the round. If two or more players are
exactly tied on HP at timeout, the round is scored as a draw (no round win awarded to either).

### Payload schema location

All Nearby Connections message types are defined as a sealed `NetMessage` hierarchy
in `net/protocol/Messages.kt`. The JSON discriminator field is `"type"` (via
`@SerialName`). No version negotiation — updating the schema requires updating all
producers and consumers in the same commit.
