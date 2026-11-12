# Third-party notices

Piko includes protocol logic and test cases derived from the following open-source projects.
The corresponding license texts are kept in `docs/licenses/`.

## Plezy

- Project: https://github.com/edde746/plezy
- Reference commit: `847842c3d075b56d686409850e12fa1cca0b19d9`
- License: GNU General Public License, version 3 or later
- License text: `docs/licenses/Plezy-GPL-3.0.txt`

Plex PIN authentication, endpoint selection, Home-user switching, playback-part selection and
the related mock-server tests in `shared/src/jvmSharedMain/kotlin/dev/piko/shared/plex/` and
`shared/src/desktopTest/kotlin/dev/piko/shared/plex/` were ported to Kotlin and adapted to
Piko's repository, storage and UI architecture. The implementation does not include Plezy's
Flutter UI, trademarks, icons, hosted services or app identifiers.

## PlexKodiConnect

- Project: https://github.com/croneter/PlexKodiConnect
- Reference commit: `aab8dbf3945e194b6bb4122f188f4ac2cfe8aaec`
- License: GNU General Public License, version 2 or later
- License text: `docs/licenses/PlexKodiConnect-GPL-2.0.txt`

Plex Media Server resource fields, pagination parameters, media-part URL handling and playback
timeline behavior were checked against PlexKodiConnect. Piko's Kotlin implementation is a new
implementation; no Python source files were copied.

## ZXing

- Project: https://github.com/zxing/zxing
- License: Apache License 2.0

ZXing Core generates the in-app Plex sign-in QR matrix. ZXing is consumed as a normal Gradle
dependency and is not vendored into this repository.
