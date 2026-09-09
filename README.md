# injector

Deploys (and reverts) the privileged **`agent`** on-car. It is the always-installed-first component
of the deployment and **owns** the vendor-signal broadcast permission.

## How it works

- **`Patcher`** applies the agent by **bind-mounting** an agent-patched copy of the privileged host
  app's APK over the live one, via the box's **own root adbd on loopback** (`127.0.0.1:5555`) using
  `dadb` (a pure-JVM ADB client). The mount is **ephemeral (RAM)** — reverted on reboot and
  re-applied by `BootReceiver`. **No system partition is written**: dm-verity stays enforcing and
  vendor OTA integrity is preserved. Same technique as `PavelDemyanov/screenmate-dash` (MIT).
  - Host = `co.screenmate.miscutils` (a small, non-critical *privileged* app), **not** the main
    display app. The patched APK has a one-line smali hook in `TimeSyncService.onCreate` starting
    the pure-Java agent, plus the agent as `classes2.dex`.
  - `isApplied()` vs `isCurrentApplied()`: the latter re-stages the bundled asset so a version bump
    reliably re-applies instead of leaving a stale older agent mounted.
- **`BootReceiver`** re-applies after a car power cycle. **`LivenessJobService`** periodically
  self-heals (~15-min floor) if the host stopped. **`ReinjectReceiver`** gives fast (~1s),
  consumer-driven recovery: a consumer that sees the feed go silent broadcasts `REINJECT`
  (guarded by the `SIGNALS` permission, debounced to avoid force-stop thrashing).
- **`MainActivity`** is the manual apply/revert/status UI.

### The SIGNALS permission

The injector **defines** `co.screenmate.can.permission.SIGNALS` (`protectionLevel="normal"`, so it
is granted at install without a signature match). Consumer apps only `<uses-permission>` it — which
is why the injector must be installed **first**.

## Build

Android application module. Requirements: **JDK 17**, Android SDK **platform 34**, Gradle **8.9**
(AGP 8.5.2, Kotlin 1.9.24). `minSdk`/`targetSdk` 34. Only dependency: `dev.mobile:dadb:1.2.9`
(pure loader — intentionally no CAN-TX; that lives in `tx-client`).

```bash
./gradlew :injector:assembleRelease
```

> **Device-specific asset not included.** `assets/patched-host.apk` is produced by
> `tools/build-host-apk.sh` from the device's *own* host APK and is **never redistributed** (it is
> gitignored). Without it the app installs but has nothing to mount. See the parent repo's
> `docs/INJECTION.md` / `docs/DEPLOY.md`.

## Note

Extracted from a multi-module monorepo; needs a root build/wrapper with the plugin versions above.
