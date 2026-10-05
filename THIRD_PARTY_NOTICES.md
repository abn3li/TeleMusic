# Third-party notices

TeleMusic's original code is licensed under GPL-3.0-only. Dependencies retain
their own copyright notices and license terms. The entries below document the
components verified during the licensing review; this is not a complete list of
transitive dependencies.

## Mutagen 1.48.1

- Copyright (C) 2005 Michael Urman. Individual source files also contain notices
  for their respective contributors, which must be preserved.
- License: GPL-2.0-or-later. Its permission to use a later GPL version permits
  use under GPLv3; the upstream license grant remains GPL-2.0-or-later.
- The upstream license text is preserved in
  [licenses/Mutagen-COPYING.txt](licenses/Mutagen-COPYING.txt).
- [Project documentation](https://mutagen.readthedocs.io/) and
  [source repository](https://github.com/quodlibet/mutagen).

Mutagen is installed through Chaquopy and included in the Android application.

## yt-dlp 2026.8.19

- License for the bundled Python package: Unlicense/public-domain dedication.
- The upstream text, including its warranty disclaimer, is preserved in
  [licenses/yt-dlp-LICENSE.txt](licenses/yt-dlp-LICENSE.txt).
- [Source repository](https://github.com/yt-dlp/yt-dlp).

Dependencies used by yt-dlp retain their separate licenses, including Mutagen.

## Apache-licensed components

The reviewed package metadata identifies Apache-2.0 for AndroidX Media3 1.4.0,
Kotlin coroutines 1.8.1 and AndroidX Security 1.1.0-alpha06. Preserve their
applicable copyright, license and NOTICE files when distributing them.

- [Media3 license](https://github.com/androidx/media/blob/1.4.0/LICENSE)
- [Kotlin coroutines license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/LICENSE.txt)
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

## FFmpeg decoder: source verification pending

The application includes prebuilt FFmpeg decoder binaries, including
`app/libs/media3-ffmpeg-audio.aar`. The exact upstream source, patches, build
configuration and resulting LGPL/GPL license variant have not yet been
established for these binaries. See [FFmpeg's licensing guidance](https://ffmpeg.org/legal.html).

Changing TeleMusic's license does not resolve this outstanding source and notice
requirement. Before distributing a new binary release, establish its matching
source and build configuration, or replace it with a decoder built from known
sources, and provide the applicable notices and source materials.

## Binary releases

A distributed GPLv3 application must have its corresponding source made
available through a method permitted by the license, including the materials
needed to build that release. An upstream project link alone does not supply
those materials for a modified or unidentified binary. Retain third-party
notices and supply source as required by each component's license. See
[LICENSE](LICENSE), particularly sections 1 and 6.
