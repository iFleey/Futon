# Futon

[English](README.md) | [中文](README_zh.md)

<p align="center">
  <img src="branding/official/logo.svg" alt="Futon" width="240" height="240">
</p>

<h1 align="center">Futon</h1>
<h3 align="center">Run on Futon. Sleep on your Futon</h3>

<p align="center">
  An Android AI Agent (toy project) combining a native C++ daemon with AI capabilities.
</p>

<p align="center">
  <a href="https://github.com/iFleey/Futon/releases"><img src="https://img.shields.io/github/v/release/iFleey/Futon?style=flat-square" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue?style=flat-square" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-green?style=flat-square" alt="Android">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-orange?style=flat-square" alt="Architecture">
</p>

## Introduction

Futon is an AI Agent for Android that works entirely on-device, supporting both cloud providers and local models.

Futon uses a dual-layer architecture: a low-level daemon handles screen capture, inference engine, and input injection;
the client app provides the user interface and AI service integration.

## Screenshots

| Home                            | Settings                            | AI Provider                            |
|---------------------------------|-------------------------------------|----------------------------------------|
| ![Home](docs/screenshots/1.jpg) | ![Settings](docs/screenshots/2.jpg) | ![AI Provider](docs/screenshots/3.jpg) |

| Local Model                            | SoM Perception                            | Integration                            |
|----------------------------------------|-------------------------------------------|----------------------------------------|
| ![Local Model](docs/screenshots/4.jpg) | ![SoM Perception](docs/screenshots/5.jpg) | ![Integration](docs/screenshots/6.jpg) |

## Getting Started

> [!IMPORTANT]
>
> Please note:
>
> Futon is a proof of concept and currently **lacks** the stability, security audits, and long-term support required for
> production use. DO NOT use the generated binaries in any production environment or mission-critical systems.
>
> Futon currently and will **only support** rooted devices.

### 1. Install the App

Download the latest APK from [Releases](https://github.com/iFleey/Futon/releases) and install it.

### 2. Grant ROOT Permission

On first launch, the app will request ROOT permission to deploy the daemon.

### 3. Deploy the Daemon

The app will automatically deploy a ROOT module to `/data/adb/modules/futon_daemon`, which includes:

- SELinux policy patches
- Daemon binary

### 4. Configure Security Keys

The app will automatically configure security keys. As a ROOT-level application, Futon implements extensive security
mechanisms. See [Security Model](./SECURITY.md) for details.

### 5. Configure AI Provider or Local Model

Go to the settings page to configure your AI provider's API key, or install a local model based on your device
capabilities.

## Build Guide

### Requirements

| Tool        | Version |
|-------------|---------|
| JDK         | 21      |
| Android SDK | API 36  |
| NDK         | r29     |
| CMake       | 3.22+   |
| Gradle      | 9.2+    |

### Building the App

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties)
./gradlew assembleRelease

# Run unit tests
./gradlew test
```

> [!TIP]
>
> In Release mode, the daemon performs signature verification on the client app. You can bypass this by adding
`--skip-sig-check` when starting the daemon (Debug builds automatically include this flag).

### Building the Daemon

```bash
cd daemon
mkdir build && cd build

cmake -B build \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30

make -j$(sysctl -n hw.ncpu)
```

> [!NOTE]
>
> The daemon binary is automatically embedded in the APK through the deployment contract system.
>
> To ensure compatibility between the client app and daemon versions, both are validated
> against [deployment_rules.json](deployment-rules.json).
>
> For details on the mechanism and workflow, see [docs/deployment-contract.md](docs/deployment-contract.md).

### Quick Debugging

Daemon debugging can be done without relying on a ROOT manager. The following steps use adb shell:

> [!TIP]
>
> Grant shell ROOT permission in your ROOT manager for debugging.

```bash
cd daemon/build

# Kill the running daemon
adb shell su -c "pkill -9 futon_daemon"

# Push to cache directory
adb push futon_daemon /data/local/tmp/

# Replace with the new daemon
adb shell su -c "cp /data/local/tmp/futon_daemon /data/adb/futon/futon_daemon"

# Grant daemon permissions
adb shell su -c "chmod 755 /data/adb/futon/futon_daemon"
```

### Signing Configuration

Create a `keystore.properties` file:

```properties
storeFile=path/to/your.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

## Contributing

Please refer to [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and [CONTRIBUTING.md](CONTRIBUTING.md) for:

- Code of conduct
- Code style guidelines
- Submission process
- Attribution rules
- Copyright ownership

## Acknowledgments

Special thanks to:

- @Caniv

  Participated in Futon's icon design and created the current icon.

- 胡斯凯

  Participated in Futon's early icon design.

- [PPOCRv5-Android](https://github.com/iFleey/PPOCRv5-Android)

  Futon is based on the PPOCRv5 model with extensive custom quantization and packaging. See PPOCRv5-Android published by
  Fleey for the model and specific applications.

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)

  PP-OCRv5 model

- [LiteRT](https://ai.google.dev/edge/litert)

  On-device ML runtime

- [Abseil](https://abseil.io/)

  C++ common libraries

## License

Source code is licensed under [GNU General Public License v3.0](LICENSE).

```
Futon - Android Automation Daemon, Futon Daemon Client, ...
Copyright (C) 2025 Fleey

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

> [!IMPORTANT]
>
> Trademark and Logo Policy
>
> The Futon name and logo are protected assets and are NOT covered by the GPLv3 license.
>
> - Forked projects MUST remove official branding assets, regardless of commercial use
> - Delete the `branding/official/` directory
> - Change the app name and package name
> - Update the identifiers in `daemon/core/branding.h`
>
> See [COPYRIGHT](COPYRIGHT) and [branding/README.md](branding/README.md) for details.
