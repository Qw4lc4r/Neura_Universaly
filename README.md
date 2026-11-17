# Neura Universal v5 — Android BYOK AI Client

Самостоятельный Android-клиент с интерфейсом в редакционном стиле — графит, тёплый металлический акцент, много воздуха. Исходники открыты (MIT), собственного сервера нет. Пользователь добавляет ключ API выбранного провайдера.

## Что реализовано

- Нативный чат на Kotlin / Jetpack Compose / Material 3, светлая и тёмная темы.
- Чаты, локальная история, редактирование, удаление, экспорт текста, потоковая выдача и остановка запроса.
- Markdown, копирование кода, базовая подсветка синтаксиса.
- Вложения через системный Android-проводник, просмотр изображений и текстовых файлов, передача картинок через поддерживаемые API.
- Генерация изображений через **OpenRouter Image API** (`POST /api/v1/images`), **OpenRouter Chat Completions** для моделей с `image`-выходом, **OpenAI Images** и **Gemini generateContent** для совместимых моделей. Модель должна поддерживать выбранный API, формат и доступ по ключу.
- Сохранение сгенерированных изображений в `Pictures/Neura` через MediaStore, открытие через FileProvider.
- Живой каталог моделей: OpenRouter `/models` + `/images/models`, OpenAI `/models`, Anthropic `/models`, Gemini `/models`, OpenAI-совместимые `/models` (если сервис предоставляет метод). Поиск, фильтры Free/Image/Vision/Reasoning/Favorites, избранное, recent, сортировка, ручной ID.
- Настройки организованы в четыре раздела: подключение, модель, диалог, внешний вид.
- Отдельные API-ключи на провайдера, шифрование ключей Android Keystore, история хранится локально.
- Понятные сообщения об ошибках API, включая `429`, с кнопками выбора другой модели и повторной попытки.
- GitHub Actions сборка `debug APK`.

## Важно про HTTP 429

Сообщение `OpenRouter HTTP 429: Provider returned error` **не является ошибкой компиляции или поломкой Kotlin**. Это ограничение частоты запросов/квоты/перегрузка на стороне OpenRouter либо модели-провайдера. Приложение не может убрать внешний лимит. В v5 вместо сырой ошибки показано объяснение, кнопка повторной попытки и выбор альтернативной модели. У бесплатных моделей могут быть дополнительные ограничения.

## Ограничения

- Не любая модель, попавшая в каталог, является *чатовой* или *генератором картинок*. Список показывает информацию API, а не гарантирует доступ. На провайдере может потребоваться оплата или дополнительное разрешение.
- Для OpenRouter при выборе модели из `/images/models` используется Image API. Для чатовых image-output моделей применяется Streaming Chat Completions; для вручную введённых ID есть ручной выбор режима. Это не универсальный протокол для всех мировых AI API.
- Поддержка PDF, DOCX и произвольных бинарных вложений как входа в модели не реализована. Такие файлы могут отображаться как вложения, но в текстовый запрос передаётся только содержимое читаемых текстовых форматов. Для изображений применяется мультимодальный протокол, если модель умеет читать картинки.
- OpenAI image editing из входного файла требует специального API и сейчас не реализован. Gemini и совместимые OpenRouter Image API модели с `input_references` могут принимать референсные изображения.
- Подписки на сайты ChatGPT / Claude не заменяют API-ключ. За запросы может взиматься отдельная плата.
- Локальные переписки не зашифрованы; Android backup отключён. API-ключ хранится в Keystore. Вводи только доверенные HTTPS endpoint.
- **Реальная Gradle/Android сборка в среде подготовки не выполнялась** — Android SDK и Gradle здесь отсутствуют. Проверь GitHub Actions и функциональность на устройстве.

## Обновление существующего репозитория из Termux

Имя папки **внутри архива совпадает с существующей папкой `Neura-Universal`**.

1. Скачай `Neura-Universal-v5-source.zip` в папку `Download`.
2. Открой Termux, проверь что установлен `unzip`, выполнен `termux-setup-storage`.
3. Выполни:

```bash
cd ~/neura-universal
unzip -o ~/storage/downloads/Neura-Universal-v5-source.zip
cd Neura-Universal

git diff --check
git status --short
git add -A
git commit -m "Neura v5: redesigned client and model catalog"
git push origin main
```

Если `nothing to commit`, проверь, что распаковка действительно перезаписала исходники *в* `~/neura-universal/Neura-Universal`.

4. Следи за сборкой:

```bash
gh run list --limit 3
RUN_ID=$(gh run list --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID"
```

5. После `success` скачать APK:

```bash
mkdir -p ~/storage/downloads/NeuraAPK
gh run download "$RUN_ID" --dir ~/storage/downloads/NeuraAPK
```

В GitHub Actions доступен артефакт `Neura-Universal-debug-apk`.

Если `failure`, точные ошибки Kotlin:

```bash
gh run view "$RUN_ID" --log-failed 2>&1 | grep -E 'e: file:|\.kt:[0-9]+|FAILURE:|What went wrong|error:' | head -n 70
```

Предупреждение: debug APK может иметь несовместимую подпись с предыдущей установкой (новый GitHub Runner). Чтобы не потерять чаты, сначала проверь подпись. Для долгосрочных обновлений нужна постоянная подпись release APK.

## Структура

- `MainActivity.kt` — маршрутизация экранов и состояние чатов
- `ChatScreen.kt` — чат, история, редакционный UI и клавиатура
- `ModelLibraryScreen.kt`, `ModelCatalog.kt` — каталог и фильтры по реальному API
- `SettingsScreen.kt` — тематические вкладки настроек
- `UniversalApi.kt` — потоковый чат и image generation API
- `AttachmentHelpers.kt` — файлы, превью и экспорт картинок
- `Store.kt` — сохранение истории и шифрование ключей
- `ApiErrors.kt` — перевод сетевых ошибок, включая HTTP 429
- `Markdown.kt` — форматированный текст и блоки кода
- `.github/workflows/android.yml` — GitHub Actions APK

Проект независим от OpenCode, OpenRouter, OpenAI, Google и Anthropic.
