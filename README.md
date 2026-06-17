# ScreenMate Dash

A custom native dashboard for the **Tesla Screenmate** box (Android 14, board `bengal_515`).
It draws as a system overlay on top of the screen and pulls real vehicle data from the stock
app. Switch between this dashboard and the stock one with **5 quick taps**.

> ⚠️ **Experimental — use at your own risk.** Tested on a Screenmate running stock v1.7.
> The patch is reversible (the original is backed up; the "Remove patch" button restores stock).
> A full reboot also drops the patch back to stock, and the app re-applies it automatically on boot.

## Install

1. Download **SMDashPatcher.apk** from [Releases](../../releases).
2. On the Screenmate, allow installation from unknown sources and install the APK.
3. Open the app → **"Install patch (backup + autostart)"**.
4. A system **"Allow debugging?"** dialog appears on the Screenmate screen —
   tap **"Always allow from this device"** (one time; required to apply the patch).
5. Done — the custom dashboard appears: km/h, live data, telltales.

## Controls

- **5 taps** on the dashboard (custom or stock) — switch between them.
- Drag up — collapse into a strip; pull the strip down — expand it.
- Two fingers — scale 50–100%. Drag — reposition.
- In the app: "Remove patch" — restore stock; "Tune positions" — fine-tune the layout.
- UI language: **English by default**, toggle to Russian via the EN/RU switch at the top.

## How it works

The Screenmate's bootloader is locked (dm-verity enforcing), so the system partition can't be
written. The patched stock dashboard APK is therefore **bind-mounted** over the system_ext copy
(ephemeral), and the app re-applies it on every boot through the device's own **local root adbd**.
See the source for details.

---
SMDashPatcher.apk (v0.6) · md5 `531090cc684393f955b66d927be2f42c` · 29456982 bytes
