---
layout: default
title: Политика конфиденциальности
permalink: /privacy
---

# Политика конфиденциальности Nekoshelf Launcher

*Дата обновления: 24 октября 2026. English version below.*

Эта политика относится к приложению **Nekoshelf Launcher** (открытый исходный код, работает на вашем устройстве).

## Какие данные и зачем

- **Google Drive.** Если вы входите в Google на экране «Бэкап», лаунчер получает доступ только по разрешению `drive.file`: он видит и изменяет **только файлы, которые создал сам**. Остальной ваш Диск ему недоступен.
- **Необязательный доступ только для чтения.** Только если вы сами включите «Забирать новые книги с Диска» на экране «Бэкап», лаунчер дополнительно запрашивает разрешение `drive.readonly`. Оно используется только для того, чтобы найти и скачать на устройство файлы из папки `Nekoshelf/books` в **вашем собственном** Google Drive. Ничего другого лаунчер не читает и никуда не отправляет. Для этого необязательного доступа Google один раз показывает экран «приложение не проверено». Если выключить галочку, лаунчер перестаёт пользоваться этим доступом; отозвать разрешение можно на <https://myaccount.google.com/permissions>.
- **Вход в Google** выполняет Google Play Services; к нему применяется [политика конфиденциальности Google](https://policies.google.com/privacy).
- **Что сохраняется в Drive.** Резервные копии ваших собственных данных KOReader (статистика, закладки, настройки), резервные копии раздела «Манга» и файлы книг, которые вы сами выбрали для бэкапа. Всё лежит в папке в **вашем** Google Drive и принадлежит вам.
- **Серверов у проекта нет.** Лаунчер обращается только к API Google Drive. Мы ничего не получаем и не храним.
- **Нет аналитики и рекламы.** Данные никому не передаются и не продаются.

## Nekoshelf Manga

Nekoshelf Manga — форк [Komikku](https://github.com/komikku-app/komikku); его сетевое поведение такое же, как у Komikku: источники манги и расширения, которые вы добавляете, репозитории расширений, необязательные трекеры, проверка обновлений. Nekoshelf Manga **не обращается к Google Drive**, и эта политика на него не распространяется.

## Отзыв доступа и удаление

Доступ можно отозвать в любой момент: <https://myaccount.google.com/permissions>. Файлы бэкапа можно удалить в вашем Google Drive. Удаление приложения удаляет его локальные данные.

## Связь

Вопросы и обращения: <https://github.com/s4m1ches/nekoshelf/issues>

---

# Nekoshelf Launcher Privacy Policy (English)

*Updated: 24 October 2026.*

This policy covers **Nekoshelf Launcher**, an open-source app that runs on your device.

- **Google Drive.** If you sign in to Google on the Backup screen, the launcher uses only the `drive.file` scope: it can see and modify **only the files it created itself**, never the rest of your Drive.
- **Optional read-only access.** Only if you turn on «Забирать новые книги с Диска» ("fetch new books from Drive") on the Backup screen, the launcher also asks for the `drive.readonly` scope. It is used only to list and download files under the `Nekoshelf/books` folder of **your own** Drive onto the device. Nothing else is read, and nothing is sent anywhere. For this optional access Google shows an "unverified app" screen once. Turning the checkbox off stops the launcher from using it; revoke the permission at <https://myaccount.google.com/permissions>.
- **Sign-in** is handled by Google Play Services; [Google's privacy policy](https://policies.google.com/privacy) applies to it.
- **What is stored.** Backups of your own KOReader data (statistics, bookmarks, settings), "Манга" (manga) backups, and book files you choose to back up. They live in a folder in **your own** Google Drive.
- **No servers.** The project operates no servers. The launcher talks only to the Google Drive API. We receive and store nothing.
- **No analytics, no ads, no data sharing.**
- **Revoke access** any time at <https://myaccount.google.com/permissions>, and delete the backup files in your Drive. Uninstalling the app removes its local data.
- **Contact:** <https://github.com/s4m1ches/nekoshelf/issues>

## Nekoshelf Manga

Nekoshelf Manga is a fork of [Komikku](https://github.com/komikku-app/komikku); its network behaviour is Komikku's: manga sources and extensions you add, extension repositories, optional trackers and update checks. It does **not** access Google Drive and is not covered by this policy.
