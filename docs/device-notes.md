# Meebook E6 — device notes

Facts about the target device (Meebook E6, Haoqing firmware, RK3566, Android 11,
1072×1448 @ 320 dpi, greyscale e-ink) that the launcher code depends on. Checked on
the owner's device on 2026-10-23.

## Frontlight

- `Settings.System.SCREEN_BRIGHTNESS` drives the frontlight. Owner confirmed by eye:
  `adb shell settings put system screen_brightness 60` turns it on, `0` turns it off.
  `screen_brightness_mode` is `0` (manual).
- The launcher's light toggle (`device/Toggles.kt`) switches between 0 and the last
  non-zero level (kept in prefs `toggles/light_level`, default 60). "On" = brightness > 0.
- Writing it needs `WRITE_SETTINGS`, a special app access: the owner grants it once in
  «Изменение системных настроек» → «Разрешить изменение системных настроек». Until then
  a tap on the light icon opens that screen instead of failing.
- The firmware also has its own keys — `isLightOn`, `key_cold_value`,
  `haoqing_warm_light` — which are not public `Settings.System` keys and are not writable
  by an app at targetSdk 28. The hardware has two backlight channels: `backlight`
  (cold) and `backlight_warm`. The warm channel and the warm/cold mix are only reachable
  from the firmware's pull-down light panel; that panel is not startable by apps
  (`com.android.systemui/.settings.BrightnessDialog` is not exported).
- Long-press on the light icon therefore opens `Settings.ACTION_DISPLAY_SETTINGS`
  («Экран», with «Яркость»).
- Known side effect: the firmware's own quick-toggle may show a stale on/off state
  after the launcher changes `SCREEN_BRIGHTNESS`.

## Wi‑Fi

- The app targets API 28 on purpose so `WifiManager.setWifiEnabled` still works on
  Android 11.
- **Airplane mode blocks it.** In airplane mode Android 11 refuses with
  `WifiService: setWifiEnabled in Airplane mode: only Settings can toggle wifi` and
  returns false, even at target 28. The owner keeps airplane mode on with Wi‑Fi on, so
  in practice the Wi‑Fi tap falls back to `Settings.Panel.ACTION_WIFI` (the system
  bottom sheet with the Wi‑Fi switch). Long-press opens `Settings.ACTION_WIFI_SETTINGS`.
- To follow the state, the header listens to `WIFI_STATE_CHANGED`. Register it
  **exported**: below API 33 `ContextCompat.RECEIVER_NOT_EXPORTED` guards the receiver
  with an app-private permission, and the system broadcast is then denied
  (`Permission Denial … requires app.meebook.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

## E-ink full refresh

- `context.getSystemService("eink")` returns `android.os.EinkManager`; reflective
  `sendOneFullFrame()` performs one full refresh. Logcat shows
  `D EinkManager: sendOneFullFrame` when it runs.

## Screenshots

- `adb shell screencap` output is after the firmware's contrast boost: light greys come
  out darker than specified (#DDDDDD shows as about #AEAEAE), so UI greys are kept light.
