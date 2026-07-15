# The stock-app patch

ScreenMate Dash draws its own dashboard, but the live vehicle data (speed, gear, battery,
turn signals, …) is held by the **stock Screenmate app** (`co.teslogic.screenmate`, closed
source). To get that data — and to let our overlay replace the stock dashboard — the stock
APK is lightly patched. This folder documents exactly what the patch does.

> The stock APK itself is **not** included in this repository (it is proprietary). The patch is
> applied to *your own copy* of the stock app, on your own device. Nothing here redistributes
> Teslogic's code.

## What the patch changes

All of the hooks are small, additive, and read-only with respect to your data — none of them
send anything off the device.

| Hook | What it does |
|------|--------------|
| **КМ/Ч label** | Two string constants `"KPH"` → `"КМ/Ч"` in the speed-unit label. Purely cosmetic. |
| **State broadcast** | The stock app already computes a `DashboardState` every frame (the numbers it draws). One added line broadcasts that same object as a local Android broadcast (`app.smdash.STATE`) so our overlay can render the identical data. No new data is read or collected — it is exactly what the stock dashboard already shows, handed to our own app on the same device. |
| **Instant hide** | A `ContentObserver` on a single `Settings.Global` flag (`smdash_hide`) so the stock dashboard hides/shows the instant our overlay takes over (otherwise it lags when parked). |
| **Settings panel** | Injects our settings block (master toggle + 5 style thumbnails + a transparency slider) into the stock app's *Display* settings screen. This is [`SmdashPanel.java`](SmdashPanel.java) — our own code, compiled to its own `classes3.dex`. |
| **5-tap switch** | A gesture hook so five quick taps on the stock dashboard switch to ours (mirrors our overlay's own 5-tap). |
| **Transparency** | Forwards the stock "Dashboard Transparency" slider value to our overlay so one control drives both. |

## How the patch is applied (and why it's reversible)

The Screenmate box has a **locked bootloader (dm-verity enforcing)**, so the system partition
can't be written. The only root path is that the device's own `adbd` runs as root. So the app
(see [`../app/src/main/java/app/smdash/Patcher.kt`](../app/src/main/java/app/smdash/Patcher.kt)):

1. connects to the device's **own local root adbd at `127.0.0.1:5555`** (loopback only — this is
   why the app declares the `INTERNET` permission; it makes **no outbound network connections**,
   which you can verify by reading `Patcher.kt`),
2. **bind-mounts** the patched APK over the live stock app (ephemeral — gone on reboot, re-applied
   automatically on the next boot),
3. never modifies the system image itself.

"Remove patch" (or simply a reboot without the app re-applying) restores the untouched stock app.
Fully reversible.

## Rebuilding the patched APK

[`build-and-inject.sh`](build-and-inject.sh) rebuilds the patched stock APK from a decompiled
tree: it compiles `SmdashPanel.java` to a `.dex`, rebuilds with `apktool`, and appends the dex.
You supply the decompiled stock tree yourself:

```bash
# 1. decompile YOUR stock Screenmate APK on a case-sensitive volume (smali breaks on APFS default):
apktool d -r your-stock-screenmate.apk -o /Volumes/cs/dec
# 2. add the smali hooks (see the table above) into /Volumes/cs/dec
# 3. rebuild:
./build-and-inject.sh /Volumes/cs/dec /Volumes/cs/built.apk
```

> The exact resource/dex counts asserted in the script are specific to one stock version — adjust
> them for the stock build you're patching.
