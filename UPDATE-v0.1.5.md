# Середовище Android v0.1.5 — оновлення

## Що змінилося
- Єдиний спосіб експорту: у APK системне збереження Android, у браузері стандартне завантаження.
- Прибрано непрацюючі варіанти з випадаючого списку.
- Виправлено захоплення назви файлу у Java-лямбді.
- Версія вебінтерфейсу й Android versionName оновлені до 0.1.5.
- Дані проєктів і кошика не змінюються.

## Як застосувати до наявного репозиторію
1. Розпакуйте архів у тимчасову папку.
2. Скопіюйте з нього **три файли**, замінюючи файли в робочій папці `/storage/emulated/0/Documents/Seredovyshche-Android`:
   - `app/src/main/assets/web/index.html`
   - `app/src/main/java/ua/seredovyshche/app/MainActivity.java`
   - `app/build.gradle`
3. У Termux виконайте:

```sh
cd /storage/emulated/0/Documents/Seredovyshche-Android
git status --short
git add app/src/main/assets/web/index.html app/src/main/java/ua/seredovyshche/app/MainActivity.java app/build.gradle
git commit -m "Simplify Android export v0.1.5"
git push
```

4. Відкрийте GitHub Actions, завантажте APK із зеленого запуску та встановіть **поверх** попередньої версії. Якщо Android відмовить через підпис — **не видаляйте** попередню версію, доки не зробите резервні копії.
5. Перевірте: «Експорт ZIP» → «Експортувати» → системний вибір папки → фактична наявність ZIP у вибраній папці; імпорт ZIP; перейменування; кошик.

## Обмеження
- Це вихідний проєкт, не готовий APK. Фактичне збирання й роботу на телефоні треба перевірити.
- Android використовує `ACTION_CREATE_DOCUMENT`; підтвердження запису показує Android після успішного завершення.
- Імпорт наразі приймає ZIP лише з `index.html`, `style.css`, `app.js`. Додаткові ресурси та дані браузерного сховища «Гаража» не імпортуються автоматично.
