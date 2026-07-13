<div align="center">

<img src="assets/icon.png" width="96" alt="SM Dash icon">

# ScreenMate Dash

A custom native dashboard for the **Tesla Screenmate** box (Android 14) — a sleek speedometer
overlay that replaces the stock dashboard, fed with live vehicle data from the stock app.

</div>

![Screenshot](assets/screenshot.png)

## Requirements

- A **Tesla Screenmate** box running the **stock Screenmate app v1.8**. The patch is built for
  v1.8 — on other stock versions it won't apply correctly. (Check on the box: stock Settings →
  Software.)
- Install over any previous version of this app — your data and the patch are preserved
  (same signing key).

## Features

- **Five dashboard styles** — Stock, Arc, Stack, Strip, Mini.
- **5-tap ring switch** — five quick taps on the dashboard cycle
  `Stock → Arc → Stack → Strip → Mini → Stock`, round and round. No menus.
- **Live data** — speed, gear, battery, temperatures, speed limit, autopilot, turn signals,
  high beam, seatbelt — straight from the car.
- **КМ/Ч** — speed localized to km/h.
- **In-Settings panel** — a *SM DASH* block inside the stock *Display* settings: a master
  toggle, the five style thumbnails, and a **transparency** slider that controls the dashboard
  live. The highlighted style follows the active dashboard in real time.
- **Adjustable** — drag to reposition, pinch to scale (50–100 %), drag up to collapse into a strip.
- **Reversible** — *Remove patch* restores the stock dashboard; a reboot does the same, and the
  app re-applies the patch automatically on boot.

## Switching dashboards

![Dashboard switching](assets/dashboard-switch.gif)

Five quick taps on whichever dashboard is showing advance to the next one.

## Install

1. Download **SMDashPatcher.apk** from [Releases](../../releases).
2. On the Screenmate, allow installation from unknown sources and install the APK.
3. Open the app → **Install patch**.
4. A system **"Allow debugging?"** dialog appears on the Screenmate — tap
   **"Always allow from this device"** (one time; required to apply the patch).
5. Done — the custom dashboard appears.

## Controls

- **5 taps** on the dashboard — cycle through the styles (see above).
- **Drag up** — collapse into a strip; pull the strip down — expand.
- **Two fingers** — scale; **drag** — reposition.
- In the stock *Display* settings — the *SM DASH* block: master toggle, style picker, transparency.

## How it works

The Screenmate's bootloader is locked (dm-verity enforcing), so the system can't be written
directly. The patched stock dashboard APK is **bind-mounted** over the live app (ephemeral) and
re-applied on every boot through the device's own local root adbd. Fully reversible.

> ⚠️ Experimental — use at your own risk. The patch is reversible, and the app re-applies it
> automatically after a reboot.
