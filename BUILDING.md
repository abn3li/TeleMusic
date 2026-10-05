# Building TeleMusic

Use JDK 17, Android SDK platform 36, NDK 26.1.10909125, and Python 3.13. On a
different machine, set `TELEMUSIC_BUILD_PYTHON` to the Python executable before
running Gradle. Android SDK location comes from the usual SDK environment or a
machine-local `local.properties` file.

```sh
./gradlew :app:assembleRelease
```

The build verifies the reviewed dependency and native-source hashes, preserves
the dependency notices, and bundles an offline license viewer accessible from
Settings > About > Open-source Licenses. On success it produces:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/release/app-release-corresponding-source.zip`
- `app/build/outputs/apk/release/licensing-verification.json`

Publish the matching source ZIP alongside the APK. The APK contains a source
snapshot identifier, and the ZIP records both that identifier and the APK's
SHA-256. The ZIP includes the actual working sources used by the build,
including any changes not yet committed, source jars for the reviewed Maven
components, and the native/Python source archives and build materials.

Signing keys, passwords, `local.properties`, `.git` and account credentials are
excluded. A fresh checkout uses debug signing unless its owner supplies their
own machine-local release signing configuration. The original signing key is
required to distribute an update compatible with existing official installs;
it is not part of the public source archive.

## Rebuilding the native decoder

The checked-in decoder is built from FFmpeg 6.1.4 and the unmodified AndroidX
Media3 1.4.0 audio decoder sources. The source archive and downloaded-source
hashes are recorded in `third_party/sources.json`. The decoder binary hashes,
configure arguments, toolchain version and recipe hashes are recorded in
`third_party/media3-ffmpeg/build-manifest.json`.

With NDK r26b and SDK CMake 3.26.4 installed, run:

```sh
python tools/licensing/build_ffmpeg.py --sdk /path/to/Android/Sdk
```

On Windows, install Git for Windows with Git Bash. The script supports SDK
Ninja or the NDK's Make. It builds both application ABIs without running an
emulator. FFmpeg has external autodetection, GPL-only components and nonfree
components disabled, and selects LGPL version 3 or later. The static FFmpeg
libraries are linked into the Media3 JNI decoder. The complete source and
application build recipe allow rebuilding the combined GPLv3 application with
a modified decoder. The CMake include search uses `-idirafter` to avoid FFmpeg's
`VERSION` file shadowing libc++'s `<version>` on Windows; FFmpeg itself is
unmodified.

## Updating dependencies

Python packages are pinned to the versions recorded with their supplied source.
Changing these versions or a native recipe requires reviewing its new source
and notices. For Maven updates, export and review the resolved release graph:

```sh
./gradlew -I tools/licensing/export_release.gradle :app:exportReleaseComponents
python tools/licensing/refresh_notices.py
```

Review the resulting notices and any shaded dependencies before accepting the
new inventory. Ordinary builds reject changed artifacts, missing notices,
unknown versions and unidentified decoder binaries. Source archives are fetched
from the recorded upstream URLs only if missing and must match the recorded
SHA-256. Preserve the generated source ZIP with its APK for every distribution.

The packaged native/Python runtime is checked against
`licenses/packaged-runtime.json`, including native extensions and CA data. A
runtime change requires reviewing its source and notices before updating those
hashes. Two exact Chaquopy JNI packaging variants are accepted after verifying
identical loadable segments and section contents; only ELF section-name tables
differ after stripping. Unknown hashes still fail. An extracted source ZIP builds without Git metadata and uses its
supplied `third-party-sources` archives before downloading missing sources.
