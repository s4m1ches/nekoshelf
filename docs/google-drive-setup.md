# Настройка Google Drive для бэкапа

**Если вы поставили APK из [релизов](https://github.com/s4m1ches/nekoshelf/releases) — эта страница вам не нужна:** просто войдите в Google на экране «☁ Бэкап».

Инструкция ниже — только для тех, кто **собирает лаунчер сам** и подписывает своим ключом: Google узнаёт приложение по паре «имя пакета + отпечаток ключа», поэтому нужен свой OAuth-клиент. Делается один раз, с компьютера, нужен обычный аккаунт Google.

1. Откройте https://console.cloud.google.com и создайте проект, например «Nekoshelf».
2. **APIs & Services → Library** → найдите «Google Drive API» → **Enable**.
3. Экран согласия OAuth (в новой консоли: **Google Auth Platform**):
   - Тип аудитории: **External**.
   - Название приложения: «Nekoshelf»; email поддержки и email разработчика — ваш собственный.
   - **Data access / Scopes**: добавьте `https://www.googleapis.com/auth/drive.file` (не чувствительный доступ: только файлы, созданные самим приложением).
   - **Audience**: нажмите «Опубликовать приложение» (**Publish to production**). Это важно: в режиме «Testing» доступ сгорает через 7 дней. Проверка Google для `drive.file` не требуется.
4. **Clients → Create client**:
   - Application type: **Android**.
   - Package name: `app.nekoshelf.launcher`.
   - SHA-1: отпечаток **вашего** ключа подписи. Соберите APK (своим ключом или отладочным) и выполните
     `apksigner verify --print-certs app-debug.apk` (`apksigner` лежит в `build-tools` Android SDK) —
     нужна строка «certificate SHA-1 digest». Для отладочного ключа можно также
     `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` (на Windows `%USERPROFILE%\.android\debug.keystore`).
     Клиент работает только с APK, подписанным тем же ключом.
5. На ридере: свайп вверх → «☁ Бэкап» → «Войти в Google» → выберите аккаунт → разрешите доступ.
6. Дальше всё само: первый бэкап пройдёт при ближайшем пробуждении, если заряд больше 70 % или ридер на зарядке; можно нажать «Сделать сейчас». Файлы появятся в Google Drive в папке «Nekoshelf».

## Если не получилось

- Ошибка сразу после выбора аккаунта: чаще всего в клиенте Android неверно указан package name или SHA-1 (проверьте шаг 4), либо экран согласия остался в режиме Testing, а аккаунт не добавлен в тестовые пользователи (проще опубликовать, шаг 3).
- Изменения в консоли применяются не сразу — подождите несколько минут и повторите вход.
