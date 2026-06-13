# Compass Duel — Kids Mode 🌟 ("Star Catchers")

### Child-Friendly Game Variant Specification

***

## Why a Kids Mode?

The standard game (see [`game-spec.md`](game-spec.md)) is a competitive duel:
players deal damage, lose HP, and are eliminated. That framing works for teens
and adults, but it is a poor fit for younger children (roughly ages 5–9):

- **Elimination hurts.** Being knocked out first and watching everyone else
  keep playing is the classic way a family game ends in tears.
- **Combat vocabulary.** "Attack", "damage", "knocked out" and explosion
  overlays set an aggressive tone.
- **Precision requirements.** A ±25° aim cone, a 2.5 m/s² shake spike and
  element counter-picking demand fine motor skills and tactical reading that
  young kids don't have yet — they would simply lose every round against an
  older sibling.

Kids Mode keeps everything that makes the game magical — pointing your phone
like a magic wand, the live compass ring, the offline P2P session — and
replaces the combat layer with a **friendly game of magic tag**.

## Concept: Star Catchers

Every player is a cheerful sprite holding a magic wand (the phone). Players
toss **sparkles** at each other; when a sparkle lands, the catcher collects a
**star** from the friend it touched. Stars only ever go **up** — nobody loses
anything, nobody is eliminated, and the round runs for a fixed time so every
child plays from the first to the last second.

### Rule mapping (standard → kids)

| Standard mode | Kids Mode |
|---|---|
| Attack (deal damage) | Toss a sparkle (collect a star on a catch) |
| HP, damage, knockout | Stars; counters only increase, no elimination |
| Shield (upright hold, 50%-of-round budget) | **Magic bubble** — blocks the sparkle *and earns the defender a star*; no budget |
| Element strengths/weaknesses | Sprites are purely cosmetic; no matchup math |
| ±25° aim tolerance | **±40°** — generous for small hands |
| Shake threshold 2.5 m/s² | **1.5 m/s²** — a soft wiggle is enough |
| 90s round, last survivor wins | **60s** round, everyone plays the whole time |
| Best of 3, one winner | One round at a time; **every player gets an award** |

### Anti-frustration rules

1. **Rest window:** after being caught, a player "rests" for **3 seconds** and
   cannot be caught again. This prevents an older child from farming a younger
   one and gives the caught player a calm beat to reorient.
2. **Catch-up bonus:** catching the player currently *strictly* ahead on stars
   is worth **2 stars** instead of 1. When the lead is shared there is no
   leader and no bonus. This rubber-bands the score without ever taking
   anything away from the leader.
3. **Rewarded defense:** a successful magic-bubble block earns the *defender*
   a star. Defense is a fun, positive choice rather than passive cowering.
4. **No negative feedback:** there is no red damage flash, no HP bar draining,
   no KO explosion. Being caught shows a soft "twinkle" animation and the rest
   countdown.

### End of round: awards instead of rankings

The results screen never shows a loser. Every player receives exactly **one**
award, assigned in priority order with ties going to the lower player id
(deterministic on every device):

| Award | Earned by |
|---|---|
| ⭐ **Star Champion** | Most stars collected |
| 🫧 **Bubble Hero** | Most sparkles blocked with the magic bubble |
| 🐝 **Busy Bee** | Most sparkles tossed (hit or miss — rewards joining in) |
| ✨ **Super Sparkler** | Everyone else — played the whole round |

A category award is only handed out when it was actually earned (metric > 0);
otherwise the player falls through to Super Sparkler. A single player never
collects two awards, so category awards cascade to the runners-up.

## Technical Notes

### Architecture is unchanged

Kids Mode is a **rule set, not a fork**. It reuses the entire standard
architecture from `game-spec.md` unchanged:

- Nearby Connections P2P_STAR topology, host-authoritative evaluation
  (clients never decide catches — same anti-cheat property as hit detection).
- Sensor stack (`TYPE_ROTATION_VECTOR` + accelerometer), calibration modes
  A/B/C, magnetometer fallback behavior.
- Bearing math: `Bearing.calculate` / `Bearing.isOnTarget` with the wider
  Kids Mode tolerance passed as the `tolerance` parameter.

The game mode is chosen by the host in the lobby and is part of the current
payload/game-state schema (no cross-version fallbacks, per repo convention).

### Domain implementation

The pure, unit-tested domain lives in
`app/src/main/java/com/justb81/compassduel/game/kids/`:

- **`KidsMode.kt`** — `KidsRules` (all tuning constants below), `KidsPlayer`,
  `evaluateCatch()` (host-side catch evaluation: miss / resting / bubbled /
  caught) and `starLeaderId()` (strict leader for the catch-up bonus).
- **`KidsAwards.kt`** — `KidsAward`, `KidsRoundStats` and `assignAwards()`.

| Constant | Value |
|---|---|
| `AIM_TOLERANCE_DEGREES` | 40° |
| `SHAKE_THRESHOLD_MPS2` | 1.5 m/s² |
| `ROUND_DURATION_SECONDS` | 60 s |
| `REST_AFTER_CAUGHT_MILLIS` | 3 000 ms |
| `STARS_PER_CATCH` | 1 |
| `STARS_PER_LEADER_CATCH` | 2 |
| `STARS_PER_BUBBLE_BLOCK` | 1 |

### UI/UX guidelines (for the upcoming screens)

- **Vocabulary:** "catch", "sparkle", "star", "magic bubble", "rest" — never
  "attack", "damage", "dead", "lose". Applies to default English strings and
  the German localization (`values-de/`).
- **Visuals:** bright pastel palette, large touch targets and oversized text,
  star counter instead of an HP bar, soft twinkle effects instead of flashes.
- **Audio/haptics:** chimes and giggle-adjacent sounds; short, gentle
  vibration only — no long "you got hit" buzzes.
- **Reading-free flow:** every lobby/result action must be understandable
  from icons alone so pre-readers can navigate.
- **Safety:** like the standard mode, fully offline, no accounts, no chat, no
  ads, no purchases — nothing beyond the local Nearby session.

## Out of Scope (for now)

- Team mode ("catch together") and the 3–4 player blocking rule.
- Power-ups; if added later they must stay strictly positive (gain-only).
- Difficulty handicaps per player (e.g., an even wider cone for the youngest
  player) — promising, but needs play-testing first.
