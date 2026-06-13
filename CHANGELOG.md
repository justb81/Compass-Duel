# Changelog

## [0.8.0](https://github.com/justb81/Compass-Duel/compare/v0.7.0...v0.8.0) (2026-06-13)


### Features

* add always-on touch controls for fire and shield ([#96](https://github.com/justb81/Compass-Duel/issues/96)) ([8bc4e0b](https://github.com/justb81/Compass-Duel/commit/8bc4e0beaa07051daae8c535af44616cddddb032))
* reliable control delivery and graceful disconnect/reconnect ([#94](https://github.com/justb81/Compass-Duel/issues/94)) ([5236e26](https://github.com/justb81/Compass-Duel/commit/5236e26946b2bbd46b70fd810c7518b2ec43f340))


### Bug Fixes

* **build:** exclude DataStore native lib to clear Play debug-symbols warning ([#92](https://github.com/justb81/Compass-Duel/issues/92)) ([2896b10](https://github.com/justb81/Compass-Duel/commit/2896b1064153aa496d13ddf3b91689ef52662ef3))
* correct inverted bow-to-greet gesture direction ([#95](https://github.com/justb81/Compass-Duel/issues/95)) ([c9745a5](https://github.com/justb81/Compass-Duel/commit/c9745a59da412d579ed0970443b1714855554f16))


### Performance Improvements

* **sensor:** share hot sensor flows, off-main-thread callbacks, lifecycle teardown ([#97](https://github.com/justb81/Compass-Duel/issues/97)) ([2058675](https://github.com/justb81/Compass-Duel/commit/20586758258b76222723f227d09656cf69af4de5))

## [0.7.0](https://github.com/justb81/Compass-Duel/compare/v0.6.0...v0.7.0) (2026-06-13)


### Features

* **home:** merge home and lobby into a discovery-first entry screen ([#90](https://github.com/justb81/Compass-Duel/issues/90)) ([475e692](https://github.com/justb81/Compass-Duel/commit/475e6923ae993e915bc622406484d13b7bc4fd5a))
* **lobby:** add live visual and haptic feedback to the bow-to-greet gesture ([#87](https://github.com/justb81/Compass-Duel/issues/87)) ([8e72652](https://github.com/justb81/Compass-Duel/commit/8e72652758ab75ea0b536e7f61c4c3c432bbbd97))
* selectable persisted theme (System/Light/Dark) and remembered player name ([#91](https://github.com/justb81/Compass-Duel/issues/91)) ([5bdc1ad](https://github.com/justb81/Compass-Duel/commit/5bdc1ad698fefd4bd7db5ded657797e208586683))

## [0.6.0](https://github.com/justb81/Compass-Duel/compare/v0.5.1...v0.6.0) (2026-06-13)


### Features

* **gesture:** swing-to-Fire + hold-upright Shield with budget; remove Dodge ([#82](https://github.com/justb81/Compass-Duel/issues/82)) ([2ba8992](https://github.com/justb81/Compass-Duel/commit/2ba89926d23eae1b433a130a666cbec5e497e1e1))
* replace manual seat selection with a "bow to greet" handshake ([#85](https://github.com/justb81/Compass-Duel/issues/85)) ([eafc883](https://github.com/justb81/Compass-Duel/commit/eafc883cec55d978c560853e40c37f2f3f430340))


### Bug Fixes

* **game:** make keep-screen-on and immersive full-screen actually take effect ([#84](https://github.com/justb81/Compass-Duel/issues/84)) ([a6fee8b](https://github.com/justb81/Compass-Duel/commit/a6fee8b9af4784d18882799739399b5ac5fa941e))

## [0.5.1](https://github.com/justb81/Compass-Duel/compare/v0.5.0...v0.5.1) (2026-06-13)


### Bug Fixes

* resolve 23 open bug issues across domain, session, security, sensor, UI and build ([#79](https://github.com/justb81/Compass-Duel/issues/79)) ([d48ee6f](https://github.com/justb81/Compass-Duel/commit/d48ee6f2c2c58f8b062d565bf139da40ed52a222))

## [0.5.0](https://github.com/justb81/Compass-Duel/compare/v0.4.2...v0.5.0) (2026-06-13)


### Features

* implement play-session improvements ([#29](https://github.com/justb81/Compass-Duel/issues/29), [#30](https://github.com/justb81/Compass-Duel/issues/30), [#31](https://github.com/justb81/Compass-Duel/issues/31), [#32](https://github.com/justb81/Compass-Duel/issues/32), [#34](https://github.com/justb81/Compass-Duel/issues/34)) ([#36](https://github.com/justb81/Compass-Duel/issues/36)) ([7480db0](https://github.com/justb81/Compass-Duel/commit/7480db0151cf79bb21feb037caad1e7e8c9acd61))

## [0.4.2](https://github.com/justb81/Compass-Duel/compare/v0.4.1...v0.4.2) (2026-06-13)


### Bug Fixes

* **build:** exclude unused native lib to clear Play debug-symbols warning ([#27](https://github.com/justb81/Compass-Duel/issues/27)) ([3a03338](https://github.com/justb81/Compass-Duel/commit/3a033381815da9619a6fe5dea690e4d03ea5d642))

## [0.4.1](https://github.com/justb81/Compass-Duel/compare/v0.4.0...v0.4.1) (2026-06-13)


### Bug Fixes

* **ci:** install NDK so release build generates native debug symbols ([#25](https://github.com/justb81/Compass-Duel/issues/25)) ([66e69aa](https://github.com/justb81/Compass-Duel/commit/66e69aaf90982c34f7c865fd678c4fc891bae914))

## [0.4.0](https://github.com/justb81/Compass-Duel/compare/v0.3.2...v0.4.0) (2026-06-13)


### Features

* **crash:** capture uncaught exceptions and surface them on next launch ([#22](https://github.com/justb81/Compass-Duel/issues/22)) ([45b78f8](https://github.com/justb81/Compass-Duel/commit/45b78f8110a57b1c88379326137e95814bc8adfa))

## [0.3.2](https://github.com/justb81/Compass-Duel/compare/v0.3.1...v0.3.2) (2026-06-13)


### Bug Fixes

* **build:** embed native debug symbols in release AAB for Play Console ([#20](https://github.com/justb81/Compass-Duel/issues/20)) ([23e3263](https://github.com/justb81/Compass-Duel/commit/23e32634561d261a231dc8b2de0930c84f32ce80))

## [0.3.1](https://github.com/justb81/Compass-Duel/compare/v0.3.0...v0.3.1) (2026-06-13)


### Bug Fixes

* **net:** prevent crash when starting host (or client) over Nearby ([#19](https://github.com/justb81/Compass-Duel/issues/19)) ([7ee12bd](https://github.com/justb81/Compass-Duel/commit/7ee12bd58d77fa2f077178ca8a812e068468b45b)), closes [#17](https://github.com/justb81/Compass-Duel/issues/17)
* resolve Android Lint warnings on the v1 sources ([#16](https://github.com/justb81/Compass-Duel/issues/16)) ([bfd1684](https://github.com/justb81/Compass-Duel/commit/bfd1684313a8f52c0840fa0ace4ff03fc8bb03a5))

## [0.3.0](https://github.com/justb81/Compass-Duel/compare/v0.2.0...v0.3.0) (2026-06-13)


### Features

* first playable version with Elemental Duel and Kids Mode ([#15](https://github.com/justb81/Compass-Duel/issues/15)) ([a1739a4](https://github.com/justb81/Compass-Duel/commit/a1739a43730381beda280e85b399117706225f01))
* **game:** add Kids Mode (Star Catchers) domain rules and spec ([#14](https://github.com/justb81/Compass-Duel/issues/14)) ([0bae146](https://github.com/justb81/Compass-Duel/commit/0bae146189f0a397c0bb259859afab765bfa4302))


### Bug Fixes

* **ci:** pass KEYSTORE_BASE64 via env instead of inline interpolation ([#11](https://github.com/justb81/Compass-Duel/issues/11)) ([fbca4b3](https://github.com/justb81/Compass-Duel/commit/fbca4b3430139353e10ac351b1468c984a9d6fd3))

## [0.2.0](https://github.com/justb81/Compass-Duel/compare/v0.1.2...v0.2.0) (2026-06-12)


### Miscellaneous Chores

* release 0.2.0 ([#9](https://github.com/justb81/Compass-Duel/issues/9)) ([572da2e](https://github.com/justb81/Compass-Duel/commit/572da2e3c68b4a31ff10ee184aace4e76cc050b3))

## [0.1.2](https://github.com/justb81/Compass-Duel/compare/v0.1.1...v0.1.2) (2026-06-12)


### Bug Fixes

* **release:** don't fail when no signed artifacts are produced ([#3](https://github.com/justb81/Compass-Duel/issues/3)) ([fd0d138](https://github.com/justb81/Compass-Duel/commit/fd0d1388ea75967921652abef8e360313413bffd))

## [0.1.1](https://github.com/justb81/Compass-Duel/compare/v0.1.0...v0.1.1) (2026-06-11)


### Bug Fixes

* **app:** add monochrome icon and fullBackupContent rules ([ff5dc6f](https://github.com/justb81/Compass-Duel/commit/ff5dc6f74c2d87340f966b427f96f269d3b301b8))

## Changelog
