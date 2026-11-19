# Neura

[Русский](#русский) · [English](#english)

## Русский

Neura — Android-клиент для общения с моделями через собственный API-ключ. Можно подключить OpenRouter, OpenAI, Anthropic, Gemini, DeepSeek и другие сервисы или указать HTTPS-адрес своего роутера.

- Каталог моделей с поиском, фильтрами, избранным и ручным вводом ID.
- Потоковые ответы, Markdown, локальная история и экспорт диалогов.
- Фото, файлы, веб-поиск, чтение публичных ссылок и рассуждения. Доступность инструментов зависит от модели и API. Для поиска с любой чат-моделью можно добавить отдельный ключ Brave Search.
- Работа с изображениями, аудио и видео через совместимые модели. Светлая и тёмная темы с настройкой акцента и размера текста.

### Подключение

1. Откройте боковое меню → **Настройки → API**.
2. Выберите провайдера или **Свой API**. Укажите базовый HTTPS-адрес и ключ, если он требуется. Формат запросов можно задать в разделе **Совместимость API**.
3. Нажмите **Сохранить и выбрать модель**. Если роутер не отдаёт каталог, укажите ID модели через меню каталога.
4. Откройте чат. Вложения, поиск и рассуждения находятся в меню **+**.

История хранится на устройстве, API-ключи сохраняются в зашифрованном виде. Содержимое запроса и необходимые вложения передаются выбранному API. При включённом поиске через Brave поисковый запрос отправляется в Brave.

### Сборка

Нужны JDK 17 и Android SDK Platform 35. Приложение поддерживает Android 8.0 и новее (API 26+). Откройте проект в Android Studio или укажите расположение SDK через переменную `ANDROID_HOME`.

    ./gradlew testDebugUnitTest lintDebug assembleDebug

На Windows используйте `gradlew.bat` вместо `./gradlew`. APK появится в `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions выполняет те же проверки и сохраняет APK в артефактах успешной сборки.

В версии 1.6.1 Neura показывает причину HTTP 402 и ID запроса, когда их возвращает роутер. Если роутер явно указывает меньший допустимый максимум токенов, приложение повторяет запрос один раз с этим максимумом.

Лицензия [MIT](LICENSE).

## English

Neura is an Android chat client for AI models that uses your own API key. Connect to OpenRouter, OpenAI, Anthropic, Gemini, DeepSeek, or another service, or enter the HTTPS address of your own gateway.

- Searchable model catalog with filters, favorites, and manual model ID entry.
- Streaming responses, Markdown, local chat history, and conversation export.
- Photos, files, web search, public link reading, and reasoning. Tool availability depends on the model and API. A separate Brave Search API key enables web search with any chat model.
- Image, audio, and video features through compatible models. Light and dark themes with adjustable accent color and text size.

### Getting started

1. Open the side menu → **Настройки** (Settings) → **API**.
2. Pick a provider or choose **Свой API** (Custom API). Enter the base HTTPS URL and an API key if required. Set the request protocol under **Совместимость API** (API compatibility).
3. Tap **Сохранить и выбрать модель** (Save and choose a model). If the gateway does not expose a catalog, enter the model ID from the catalog menu.
4. Start a chat. Use **+** to attach files or enable web search, link reading, or reasoning.

Chat history stays on your device and API keys are stored encrypted. The selected API receives the request content and necessary attachments. When Brave Search is enabled, the search query is sent to Brave.

### Build

Use JDK 17 and Android SDK Platform 35. The app supports Android 8.0 and later (API 26+). Open the project in Android Studio or set `ANDROID_HOME` to your SDK location.

    ./gradlew testDebugUnitTest lintDebug assembleDebug

On Windows, use `gradlew.bat` in place of `./gradlew`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same checks and saves the APK as a build artifact on success.

Version 1.6.1 displays the gateway's HTTP 402 explanation and request ID when available. If the gateway specifies a lower affordable output limit, Neura retries once with that limit.

Licensed under [MIT](LICENSE).
