# Nekoshelf KOReader Kit

Набор настроек KOReader, с которым Nekoshelf Launcher проверялся на Meebook E6 (e-ink, Android 11).
*English: the configs and ornament pack I use with KOReader on the Meebook E6. Plugins themselves are not included.*

## Что внутри

| Путь | Назначение |
|---|---|
| `patches/1-nekoshelf-eink-fullrefresh.lua` | пользовательский патч KOReader: полное обновление экрана E6 через `EinkManager.sendOneFullFrame()` (без него «full refresh» на E6 — обычная перерисовка без вспышки). На других устройствах отключается сам. |
| `defaults.custom.lua` | `STARDICT_DATA_DIR` → `/storage/emulated/0/Dict` (общая папка словарей). Поменяйте путь под себя. |
| `settings/bookends.lua`, `settings/bookends_presets/` | настройки и пресеты «Новелла» / «Манга» плагина Bookends (строки статуса). |
| `bookshelf-ornaments/Кошкодевочки/` | 48 PNG-орнаментов (те же кошкодевочки, что в лаунчере) для плагина Bookshelf. |
| `settings.reader.partial.lua` | **частичные** настройки KOReader: ключи для ручного переноса (см. ниже). |
| `install-kit.ps1` | заливает всё это на ридер через adb. |

Сами плагины в набор **не входят**, установите их с официальных страниц:

- Bookends: <https://github.com/AndyHazz/bookends.koplugin>
- Bookshelf: <https://github.com/AndyHazz/bookshelf.koplugin> (Releases, файл `bookshelf.koplugin.zip`)

Проверено с Bookends 5.31.0 и Bookshelf 5.5.5. Плагины кладут в `koreader/plugins/`.

## Установка

1. Запустите KOReader на ридере хотя бы раз (появится папка `/sdcard/koreader`), установите плагины.
2. Включите отладку по USB и подключите ридер.
3. Запустите:

   ```powershell
   powershell -ExecutionPolicy Bypass -File koreader-kit\install-kit.ps1
   ```

   Параметры: `-Adb <путь к adb.exe>`, `-Serial <серийный номер>`, `-SkipOrnaments`.
   Одноимённые файлы на ридере перезаписываются, поэтому сделайте копию `/sdcard/koreader`, если правили их.
4. Перезапустите KOReader.

## settings.reader.lua: переносится вручную

Скрипт **не трогает** `settings.reader.lua`: KOReader перезаписывает его при выходе, и там лежит ваше состояние.
Чтобы применить рекомендуемые настройки:

1. Закройте KOReader.
2. `adb pull /sdcard/koreader/settings.reader.lua`
3. Скопируйте нужные ключи из `settings.reader.partial.lua` в свой файл (ключ — одна строка `["имя"] = значение,`; вложенные таблицы `footer`, `statistics`, `style_tweaks` заменяйте целиком).
4. `adb push settings.reader.lua /sdcard/koreader/` и запустите KOReader.

В частичном файле только ключи устройства, обновления экрана, шрифтов, интерфейса и чтения. Путей к книгам, истории, аккаунтов и серверов там нет.

## English

Run `koreader-kit\install-kit.ps1` (adb must see the reader). It pushes the e-ink full-refresh patch, `defaults.custom.lua`, Bookends settings/presets and the ornament pack to `/sdcard/koreader/`. It never overwrites `settings.reader.lua`; merge the keys you want from `settings.reader.partial.lua` by hand while KOReader is closed. Install Bookends and Bookshelf from their own repositories (linked above); they are not redistributed here.

Лицензия: Apache-2.0 (см. `../LICENSE`). Орнаменты сгенерированы владельцем репозитория.
