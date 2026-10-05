"""Build the decoder from its checked-in source archive; requires Python 3.12+.

Windows uses Git Bash, Android NDK r26b and the SDK's CMake/Ninja. Linux uses
the same NDK release and system Bash/CMake/Ninja. No credentials are involved.
"""
# Copyright (c) 2026 abn3li
# SPDX-License-Identifier: GPL-3.0-only
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "6.1.4"
DECODERS = "aac,ac3,alac,amrnb,amrwb,ape,atrac1,atrac3,atrac3p,dca,eac3,flac,mp1,mp2,mp3,opus,pcm_alaw,pcm_mulaw,vorbis,truehd,mlp,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f64le,pcm_u8"
COMMON = ["--target-os=android", "--enable-cross-compile", "--ar=llvm-ar", "--nm=llvm-nm", "--ranlib=llvm-ranlib", "--strip=llvm-strip", "--enable-static", "--disable-shared", "--enable-pic", "--disable-autodetect", "--disable-doc", "--disable-programs", "--disable-everything", "--disable-avdevice", "--disable-avformat", "--disable-avfilter", "--disable-swscale", "--disable-postproc", "--disable-symver", "--disable-gpl", "--disable-nonfree", "--enable-version3", "--enable-swresample", "--disable-hardcoded-tables", "--enable-decoder=" + DECODERS, "--extra-cflags=-fPIC", "--extra-ldflags=-Wl,-z,max-page-size=16384"]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], action="append")
    parser.add_argument("--jobs", type=int, default=6)
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    ndk = sdk / "ndk/26.1.10909125"
    assert "Pkg.Revision = 26.1.10909125" in (ndk / "source.properties").read_text(), "Use the recorded NDK r26b."
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    bash = Path(os.environ.get("PROGRAMFILES", "C:/Program Files")) / "Git/bin/bash.exe" if os.name == "nt" else Path(shutil.which("bash"))
    cmake_bin = sdk / "cmake/3.26.4/bin"
    cmake = cmake_bin / ("cmake.exe" if os.name == "nt" else "cmake")
    ninja = cmake_bin / ("ninja.exe" if os.name == "nt" else "ninja")
    make = ndk / 'prebuilt' / host / 'bin' / ('make.exe' if os.name == 'nt' else 'make')
    generator = 'Ninja' if ninja.exists() else ('MinGW Makefiles' if os.name == 'nt' else 'Unix Makefiles')
    make_program = ninja if ninja.exists() else make
    archive = ROOT / "third_party/sources" / f"ffmpeg-{VERSION}.tar.xz"
    source_record = next(x for x in json.loads((ROOT / "third_party/sources.json").read_text())['sources'] if x['path'] == archive.relative_to(ROOT).as_posix())
    assert digest(archive) == source_record['sha256'], "FFmpeg source archive changed."

    def shell_path(path):
        if os.name != "nt":
            return str(path)
        return subprocess.check_output([str(bash), "--noprofile", "--norc", "-c", 'cygpath -u "$1"', "--", str(path)], text=True).strip()

    manifest_path = ROOT / "third_party/media3-ffmpeg/build-manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"ffmpeg_version": VERSION, "ffmpeg_source_sha256": digest(archive), "media3_commit": "b01c6ffcb3fca3d038476dab5d3bc9c9f2010781", "ndk": "26.1.10909125", "android_api": 26, "license": "LGPL-3.0-or-later", "gpl_components": False, "nonfree_components": False, "decoders": DECODERS.split(','), "abis": {}}
    for abi in args.abi or ["arm64-v8a", "x86_64"]:
        base = ROOT / "build/ffmpeg" / abi
        source = base / f"ffmpeg-{VERSION}"
        base.mkdir(parents=True, exist_ok=True)
        if not source.exists():
            with tarfile.open(archive) as tar:
                tar.extractall(base, filter="data")
        arch, cpu, target = ("aarch64", "armv8-a", "aarch64-linux-android26") if abi == "arm64-v8a" else ("x86_64", "x86-64", "x86_64-linux-android26")
        options = [f"--arch={arch}", f"--cpu={cpu}", f"--cc={target}-clang", f"--cxx={target}-clang++", *COMMON]
        if abi == "x86_64":
            options += ["--disable-asm"]
        stamp = base / "configure-arguments.json"
        paths = [shell_path(toolchain), shell_path(ndk / "prebuilt" / host / "bin")]
        shell = "C:/Progra~1/Git/bin/bash.exe" if os.name == "nt" else "/bin/bash"
        commands = ["#!/usr/bin/env bash", "set -euo pipefail", "export PATH=" + shlex.quote(':'.join(paths)) + ':"$PATH"', "cd " + shlex.quote(shell_path(source))]
        if not stamp.exists() or json.loads(stamp.read_text()) != options:
            commands.append("./configure " + " ".join(shlex.quote(x) for x in options))
        commands.append(f"make -j{max(1, min(args.jobs, 16))} " + shlex.quote("SHELL=" + shell))
        script = base / "build.sh"
        script.write_text('\n'.join(commands) + '\n', encoding="utf-8", newline="\n")
        print(f"Building FFmpeg {VERSION}: {abi}", flush=True)
        with (base / "build.log").open('w', encoding='utf-8') as log:
            subprocess.run([str(bash), "--noprofile", "--norc", str(script)], stdout=log, stderr=subprocess.STDOUT, check=True)
        stamp.write_text(json.dumps(options, indent=2), encoding='utf-8')
        config = (source / 'config.h').read_text()
        for expected in ['#define CONFIG_GPL 0', '#define CONFIG_NONFREE 0', '#define CONFIG_VERSION3 1', '#define FFMPEG_LICENSE "LGPL version 3 or later"']:
            assert expected in config, expected
        cmake_build = base / ('jni' if ninja.exists() else 'jni-make')
        subprocess.run([str(cmake), '-S', str(ROOT / 'third_party/media3-ffmpeg/src/main/jni'), '-B', str(cmake_build), '-G', generator, f'-DCMAKE_MAKE_PROGRAM={make_program.as_posix()}', f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake', f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release', f'-DFFMPEG_SOURCE_DIR={source.as_posix()}', f'-DFFMPEG_STATIC_DIR={source.as_posix()}'], check=True)
        subprocess.run([str(cmake), '--build', str(cmake_build), '--clean-first', '--parallel', str(max(1, min(args.jobs, 16)))], check=True)
        output = ROOT / 'app/src/main/jniLibs' / abi / 'libffmpegJNI.so'
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cmake_build / 'libffmpegJNI.so', output)
        strip = toolchain / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip')
        subprocess.run([str(strip), '--strip-unneeded', str(output)], check=True)
        manifest['abis'][abi] = {'file': output.relative_to(ROOT).as_posix(), 'sha256': digest(output), 'configure': options, 'config_header_sha256': digest(source / 'config.h')}
        manifest['recipe_sha256'] = digest(__file__)
        manifest['cmake_sha256'] = digest(ROOT / 'third_party/media3-ffmpeg/src/main/jni/CMakeLists.txt')
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        print(f"Built {abi}: {digest(output)}", flush=True)


if __name__ == '__main__':
    main()
