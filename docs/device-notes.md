# Meebook E6 — device notes

Facts about the target device (Meebook E6, Haoqing firmware, RK3566, Android 11,
1072×1448 @ 320 dpi, greyscale e-ink) that the launcher code depends on. Checked on
a test device on 2026-10-23.

## Frontlight

- `Settings.System.SCREEN_BRIGHTNESS` drives the frontlight. Confirmed by eye:
  `adb shell settings put system screen_brightness 60` turns it on, `0` turns it off.
  `screen_brightness_mode` is `0` (manual).
- The launcher's light toggle (`device/Toggles.kt`) switches between 0 and the last
  non-zero level (kept in prefs `toggles/light_level`, default 60). "On" = brightness > 0.
- Writing it needs `WRITE_SETTINGS`, a special app access: the user grants it once in
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
- Airplane mode was turned **off** (2026-10-23), so the Wi‑Fi icon is a true
  one-tap on/off toggle — confirmed on the device. Long-press opens
  `Settings.ACTION_WIFI_SETTINGS`.
- **Airplane mode blocks it.** If airplane mode is ever turned on again, Android 11 refuses
  with `WifiService: setWifiEnabled in Airplane mode: only Settings can toggle wifi` and
  returns false, even at target 28. Only then does the tap fall back to
  `Settings.Panel.ACTION_WIFI` (the system bottom sheet with the Wi‑Fi switch: one extra tap).
- To follow the state, the header listens to `WIFI_STATE_CHANGED`. Register it
  **exported**: below API 33 `ContextCompat.RECEIVER_NOT_EXPORTED` guards the receiver
  with an app-private permission, and the system broadcast is then denied
  (`Permission Denial … requires app.nekoshelf.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

## E-ink full refresh

- `context.getSystemService("eink")` returns `android.os.EinkManager`; reflective
  `sendOneFullFrame()` marks the NEXT posted frame as a full (GC16) refresh — it does not
  flash what is already on screen. `Eink.fullRefresh(activity)` therefore invalidates the
  decor view right after the call so a frame is posted (verified: `dumpsys gfxinfo` frame
  count +1 per refresh tap, +0 for a tap on an inert area). Logcat shows
  `D EinkManager: sendOneFullFrame` when it runs.

## Hardware page keys

- The page-turn buttons send scan codes 603/604, mapped in `/system/usr/keylayout/Generic.kl`
  to firmware labels `HAOQING_PAGE_UP` / `HAOQING_PAGE_DOWN` (key codes 291 / 292 on this
  build). The launcher resolves them with `KeyEvent.keyCodeFromString(...)` at runtime.
  Test with `adb shell input keyevent 292`.

## Opening books in KOReader

- KOReader accepts `ACTION_VIEW` with a `file://` URI (`UriHandler: Found local file`).
  At targetSdk ≥ 24 StrictMode would throw `FileUriExposedException`, so `Launch.openBook`
  lifts the VM policy for that one `startActivity`.
- With the Bookshelf plugin's "hot parking" (reader parked under the shelf), an external
  open closes the parked ReaderUI first (`ReaderUI instance mismatch!`), and the plugin's
  close hook re-shows the shelf over the newly opened book. Seen once on 2026-10-23; the
  next open showed the book at its saved position.

## Default home (launcher)

- HOME candidates on this firmware (`adb shell cmd package query-activities --brief -a
  android.intent.action.MAIN -c android.intent.category.HOME`, checked 2026-10-23):
  `com.haoqing.app.hqreader/.view.activity.LauncherActivity` (the stock home),
  `app.nekoshelf.launcher/.MainActivity` and `com.android.settings/.FallbackHome`. The stock
  home's HOME entry point is `LauncherActivity`; `LauncherMainActivity` is its main screen
  (not a HOME activity — it does not take part in the home choice).
- To make «Лаунчер» the home: press HOME and pick «Лаунчер» → «Всегда» (user step), or
  `adb shell cmd package set-home-activity app.nekoshelf.launcher/.MainActivity`.
- **To go back to the stock home:**
  - `adb shell cmd package set-home-activity com.haoqing.app.hqreader/.view.activity.LauncherActivity`
  - or on the device: Настройки → Приложения → Приложения по умолчанию → «Главный экран» →
    the stock launcher (the same screen opens with
    `adb shell am start -a android.settings.MANAGE_DEFAULT_APPS_SETTINGS`; on 2026-10-23 it
    showed «Главный экран: Нет», i.e. no default chosen yet).
  - Uninstalling «Лаунчер» also returns HOME to the stock launcher.
- Opening the stock home's main screen directly (without changing the default):
  `adb shell am start -n com.haoqing.app.hqreader/com.haoqing.app.hqreader.view.activity.LauncherMainActivity`.

## «Манга» provider cold start

- When the «Манга» process is not running, its bridge provider takes several seconds to
  answer the first query (one cold start measured 7.3 s on 2026-10-23: `HomeLoader … bridge=7351ms`).
  With «Манга» already running the bridge part takes 0.25–0.5 s. The home screen therefore
  shows the books first: the full refresh waits for «Манга» at most 1 s after the books, and
  a later manga read is merged in without another full refresh.

## Screenshots

- `adb shell screencap` output is after the firmware's contrast boost: light greys come
  out darker than specified (#DDDDDD shows as about #AEAEAE), so UI greys are kept light.

## Бэкап на Google Drive

- Раз в сутки при пробуждении, если заряд больше 70 % или ридер на зарядке; Wi-Fi сам никогда не включается. Сохраняются в папку «Nekoshelf» на Drive: `koreader` и `manga` (настройки и данные, 14 дневных снимков), `books/...` (сами книги, никогда не удаляются). Восстановление только докладывает то, чего не хватает, и ничего не перезаписывает.
