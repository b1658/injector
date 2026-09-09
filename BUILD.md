# Building the injector yourself

The injector is **not distributed as a prebuilt APK**, and it never will be. A working injector APK
must embed `assets/patched-host.apk` — an **agent-patched copy of *your own device's* privileged
host app** (`co.screenmate.miscutils`). That file is device-specific and proprietary; it is pulled
from the device you own and is **not redistributable**. Therefore every user builds their own.

> **Your responsibility.** By building and installing the injector you are modifying software on a
> device you own and taking on full responsibility for the result. You are the one who extracts your
> device's host APK, patches it, and runs it. The maintainers distribute only source and
> documentation — no patched binaries, no keys, no device images. Do not build or run this against a
> device you do not own or are not authorized to modify. This is experimental software for a vehicle
> head unit; understand what it does before you use it, and never treat it as a safety system.

## Prerequisites

- **JDK 17**, Android SDK **platform 34** + build-tools, Gradle **8.9** (AGP 8.5.2, Kotlin 1.9.24).
- `adb`, and the sibling **[agent](https://github.com/b1658/agent)** module sources (the pure-Java
  `AgentBroadcaster` + `AgentProbe` are compiled into the patched host).
- `apktool` (the host-patch step uses 3.0.3) and your device reachable over adb.
- A multi-module Gradle setup: a root `build.gradle.kts` declaring the plugin versions above and a
  `settings.gradle.kts` that `include(":injector")` (add `:agent`, `:common` if building the whole
  system). This split repo ships the module only.

## Step 1 — produce your `patched-host.apk`

The patched host is the device's own `co.screenmate.miscutils` APK with (a) a **one-line smali hook**
into an already-registered startup method (`TimeSyncService.onCreate`) that calls the pure-Java
agent entry point, and (b) the agent appended as `classes2.dex`. No re-sign is needed — the injector
bind-mounts over an already-registered package, so the runtime dex load skips signature checks.

In the full monorepo this is one command (`tools/build-host-apk.sh`), which:

1. Pulls `co.screenmate.miscutils` **from your connected device** (`adb`).
2. Compiles **only** `AgentBroadcaster.java` + `AgentProbe.java` to a dex (pure Java — the host ships
   an older Kotlin stdlib that would otherwise shadow ours).
3. Decodes the host APK with apktool, injects the one smali line, appends the agent dex, rebuilds.
4. Writes the result to `injector/src/main/assets/patched-host.apk`.

If you are building from this split repo, replicate those steps against your own device's APK and
place the result at `src/main/assets/patched-host.apk`.

## Step 2 — build the injector APK

```bash
./gradlew :injector:assembleRelease
```

Sign the output with **your own** release keystore (see below). The injector's only dependency is
`dev.mobile:dadb:1.2.9` (a pure-JVM ADB client used to bind-mount over the box's own loopback root
adbd at `127.0.0.1:5555`).

## Signing

No signing key is included. Generate your own release keystore and sign with `apksigner`
(v1+v2+v3), keeping the keystore private:

```bash
keytool -genkeypair -v -keystore my-release.jks -alias injector \
  -keyalg RSA -keysize 4096 -validity 10000
$ANDROID_HOME/build-tools/34.0.0/zipalign -v 4 \
  injector/build/outputs/apk/release/injector-release-unsigned.apk injector-aligned.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner sign --ks my-release.jks \
  --out injector-release.apk injector-aligned.apk
```

## What it does at install/run time

Installs the `co.screenmate.can.permission.SIGNALS` permission (install the injector **first**;
consumers only `uses-permission` it). On launch / boot / liveness it bind-mounts the patched host
over the live one — **ephemeral (RAM), reverted on reboot, no system partition written**, so
dm-verity stays enforcing and vendor OTA integrity is preserved.
