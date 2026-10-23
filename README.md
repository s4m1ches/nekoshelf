# Nekoshelf Launcher

Лаунчер для Android e-ink ридеров: полка книг KOReader, статистика, «Сейчас читаю», мост к манга-приложению и бэкап в Google Drive. Сделан под Meebook E6 (Android 11), но не привязан к нему.

*English summary: an Android launcher for e-ink readers, built around KOReader. It has a bookshelf, reading statistics, "now reading", a bridge to the Nekoshelf Manga app, an e-ink-friendly UI, Google Drive backup/restore and optional catgirl decor. Tested on the Meebook E6. Apache-2.0. Not affiliated with Meebook.*

Проекты: **Nekoshelf Launcher** (этот репозиторий), [**Nekoshelf Manga**](https://github.com/s4m1ches/nekoshelf-manga) (форк [Komikku](https://github.com/komikku-app/komikku)), **Nekoshelf KOReader Kit** (`koreader-kit/`).

## Возможности

- **Полка** книг из KOReader: обложки, прогресс.
- **Статистика** чтения из базы KOReader.
- **Сейчас читаю**: быстрый возврат к текущей книге.
- **Мост «Манга»**: раздел с вашей мангой из Nekoshelf Manga и продолжение чтения оттуда.
- **E-ink**: без анимаций, чёткие контрасты, управление обновлением экрана.
- **Бэкап и восстановление в Google Drive**: данные KOReader, раздел «Манга» и книги; доступ только `drive.file` (в Drive лежат только файлы самого приложения).
- **Кошкодевочки**: необязательный декор.

## Требования

- Android 11, e-ink ридер (проверено на Meebook E6).
- [KOReader](https://koreader.rocks/).
- Для раздела «Манга»: [Nekoshelf Manga](https://github.com/s4m1ches/nekoshelf-manga).

## Сборка

```
./gradlew :app:assembleDebug
```

Релизная подпись: путь к файлу `.properties` с данными ключа задаётся переменной окружения `NEKOSHELF_KEYSTORE_PROPERTIES`. Оба приложения (лаунчер и Nekoshelf Manga) должны быть подписаны **одним ключом**. Ключ и пароли в репозиторий не кладутся.

## Google Drive

Для бэкапа нужен собственный OAuth-клиент: [docs/google-drive-setup.md](docs/google-drive-setup.md).

## Набор для KOReader

Патч полного обновления экрана E6, настройки Bookends, орнаменты Bookshelf и скрипт установки: [koreader-kit/README.md](koreader-kit/README.md).

## Конфиденциальность

[Политика конфиденциальности](https://s4m1ches.github.io/nekoshelf/privacy). Серверов, аналитики и передачи данных нет.

## Лицензия

Apache-2.0, см. [LICENSE](LICENSE). Copyright 2026 Samich. Проект не связан с Meebook (Meebook упоминается только как устройство, на котором проверялась работа).

Вопросы и ошибки: [Issues](https://github.com/s4m1ches/nekoshelf/issues).
