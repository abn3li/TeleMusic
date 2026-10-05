# Third-party notices

Original TeleMusic code is GPL-3.0-only, with the additional permission in
[OPENSSL_PERMISSION](OPENSSL_PERMISSION). Third-party components retain their
own copyright notices and license terms. The APK includes these documents and
the complete reviewed notices in Settings > About > Open-source Licenses.

## Android and JVM dependencies

[licenses/maven-components.json](licenses/maven-components.json) records all 96
resolved Maven release artifacts, their binary and source hashes, source URLs,
and preserved notices. These include AndroidX, Media3, Kotlin, coroutines, Coil,
Retrofit, OkHttp, Okio, Gson, Guava, Tink and the TDLib Android wrapper. Their
licenses are Apache-2.0. Individual third-party notices remain in
[licenses/maven](licenses/maven), including notices found in source and binary
archives. The full [Apache License 2.0](licenses/Apache-2.0.txt) is also included.

Tink 1.8.0 contains a shaded Protobuf runtime. Its release build declares
Protobuf 3.19.6; the additional BSD terms and source are preserved separately in
[licenses/Protobuf-3.19.6-LICENSE.txt](licenses/Protobuf-3.19.6-LICENSE.txt).

## FFmpeg and the Media3 decoder

The application uses FFmpeg 6.1.4, built from the unmodified checked-in
upstream source archive, and the Apache-2.0 Media3 1.4.0 audio decoder sources.
The former decoder AAR and its unidentified ARM32 binary are no longer used.

FFmpeg is built with `--disable-gpl`, `--disable-nonfree`,
`--disable-autodetect` and `--enable-version3`; its configured license is
LGPL-3.0-or-later. Original source copyright notices are retained. See
[FFmpeg's LGPL text](licenses/FFmpeg-LGPL-3.0.txt), its
[upstream license description](licenses/FFmpeg-LICENSE.md), the complete source
archive in `third_party/sources`, and the exact
[build manifest](third_party/media3-ffmpeg/build-manifest.json).

The arm64-v8a and x86_64 JNI libraries are built from those sources with Android
NDK r26b. The modified application and decoder can be rebuilt together from the
supplied source and recipe; see [BUILDING.md](BUILDING.md).

## Telegram library and legacy OpenSSL

The TDLib Android wrapper 1.8.56 is Apache-2.0. The bundled TDLib native core is
under the [Boost Software License 1.0](licenses/TDLib-Boost-LICENSE.txt).
Both shipped ABIs match the binaries in the wrapper's pinned upstream tree
`77439c09d316e17a55a1e6a490c537084380288a`. They embed the TDLib upstream commit
`dd1b761fda7e47f4e0275c4d319f80a04db1997f` and OpenSSL 1.1.1w.

OpenSSL 1.1.1w retains its original OpenSSL and SSLeay licenses, copyright
notices and acknowledgments in
[licenses/OpenSSL-1.1.1w-LICENSE.txt](licenses/OpenSSL-1.1.1w-LICENSE.txt).
The extra permission for original TeleMusic code is in
[OPENSSL_PERMISSION](OPENSSL_PERMISSION); it does not grant permission on
behalf of other copyright holders.

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (https://www.openssl.org/). This product includes cryptographic
software written by Eric Young (eay@cryptsoft.com). This product includes
software written by Tim Hudson (tjh@cryptsoft.com).

## Python, downloader and metadata writer

- Mutagen 1.48.1: GPL-2.0-or-later, with original contributor notices retained
  in its supplied source. Copyright (C) 2005 Michael Urman and the contributors
  identified in individual source files. See
  [Mutagen-COPYING.txt](licenses/Mutagen-COPYING.txt).
- yt-dlp 2026.8.19: Unlicense/public-domain dedication for its Python package.
  See [yt-dlp-LICENSE.txt](licenses/yt-dlp-LICENSE.txt). Optional dependencies
  retain their own licenses, including Mutagen.
- Chaquopy 17.0.0: MIT, copyright (c) 2017-2025 Chaquo Ltd and contributors.
  See [its license](licenses/Chaquopy-17.0.0-LICENSE.txt) and the retained
  [runtime vendor notices](licenses/chaquopy-runtime).
- CPython 3.13.9: its PSF license agreement and historical notices are preserved
  in [Python-3.13.9-LICENSE.txt](licenses/Python-3.13.9-LICENSE.txt), with
  [extension notices](licenses/python-runtime) retained separately.
- Python's OpenSSL 3.0.18 is Apache-2.0, separate from TDLib's legacy OpenSSL.
  The Python runtime also includes bzip2 1.0.8, libffi 3.4.4, SQLite 3.50.4,
  and liblzma from xz 5.4.6, with their individual notices preserved.
- The Certifi CA bundle 2025.8.3 retains its MPL-2.0 terms and source data.
  See [its license](licenses/Certifi-2025.8.3-LICENSE.txt).

The native/runtime source archives, identities and license files are recorded
in [licenses/native-source-components.json](licenses/native-source-components.json).
NDK runtime/toolchain notices are preserved in
[NOTICE](licenses/Android-NDK-r26b-NOTICE.txt) and
[NOTICE.toolchain](licenses/Android-NDK-r26b-NOTICE.toolchain.txt).
The packaged runtime hashes are recorded in
[licenses/packaged-runtime.json](licenses/packaged-runtime.json); a release
fails verification if those native libraries, extensions or CA data change.

## Release source and notices

Each successful release build verifies the reviewed inputs and emits a matching
corresponding-source ZIP, bound to the APK by SHA-256 and an embedded source
snapshot. Publish that ZIP alongside its APK. It includes the actual working
sources, original native/Python source archives, Maven source jars and build
instructions; signing/account credentials are excluded. Existing third-party
licenses are preserved and are not replaced by TeleMusic's GPL file.
