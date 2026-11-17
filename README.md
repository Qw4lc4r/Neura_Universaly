# Neura Universal — open-source Android AI chat

A native Android chat client (Kotlin + Jetpack Compose + Material 3) built around **bring your own API key**. No backend or developer-owned proxy. MIT licensed.

## Supported API types

| Provider in app | API type | Default endpoint |
|---|---|---|
| OpenAI | OpenAI-compatible Chat Completions | `https://api.openai.com/v1` |
| Anthropic Claude | Anthropic Messages (streaming) | `https://api.anthropic.com/v1` |
| Google Gemini | Gemini `streamGenerateContent` | `https://generativelanguage.googleapis.com/v1beta` |
| OpenRouter | OpenAI-compatible | `https://openrouter.ai/api/v1` |
| DeepSeek | OpenAI-compatible | `https://api.deepseek.com/v1` |
| Groq | OpenAI-compatible | `https://api.groq.com/openai/v1` |
| Mistral | OpenAI-compatible | `https://api.mistral.ai/v1` |
| Together AI | OpenAI-compatible | `https://api.together.xyz/v1` |
| Custom API | OpenAI-compatible | Your HTTPS base URL |

Set any model ID that your selected provider actually exposes to your key. **Not every model is compatible with Chat Completions**; for example, some models require the provider's Responses API and may reject this client's requests. Models can impose their own token/temperature/tool restrictions. The default IDs and model suggestions are examples, not a dynamic model listing or guarantees.

## Included

- Multiple local chats, rename/delete, regenerate, edit and share/export
- Streaming text replies with stop/cancel and preservation of partial text
- Markdown headings, lists, links, code blocks with copy and basic syntax highlighting
- Provider selection, independent encrypted API keys per provider, editable model ID and HTTPS base URL
- System instructions, output token/temperature settings, light/dark modes
- Web search toggle: Anthropic native `web_search_20250305`; Gemini `google_search`; OpenAI Responses `web_search` (model/key support and billing required)
- Reasoning toggle: Anthropic thinking config, Gemini thinking config, OpenAI Responses reasoning effort on compatible models
- Provider/model suggestions and editable custom model ID; quick search/reasoning controls above the composer
- Keyboard-aware composer using edge-to-edge insets, `adjustResize` and Compose `imePadding()`
- Minimalist graphite and sage UI with quieter message presentation
- Local history storage with atomic file writes; Android Keystore AES-GCM for key encryption
- Github Actions debug-APK build without the obsolete SDK `tools` package

## Limitations / important security notes

- Chats are stored in the app's private storage **without encryption**. Device backup is disabled.
- API keys are encrypted at rest using the Android Keystore. They are passed directly from the device to the **selected HTTPS provider URL**. A malicious custom endpoint can steal your key and conversation. Only enter trusted URLs.
- The app does not provide free inference or bypass payment. Each provider sets prices, quotas, eligibility and availability.
- No OAuth login with ChatGPT/Claude subscriptions; this uses developer API keys, not chat subscriptions.
- Images/audio/file attachments, local HTTP models, function calling, citations, multimodal/tool rendering, live model discovery and full LaTeX are not implemented.
- Search/reasoning controls are intentionally disabled for generic OpenAI-compatible providers: APIs are not consistent. Providers may also reject enabled tools even where shown as available.
- Neither web search nor reasoning offers a universal "force" guarantee. When reasoning is off, some models can still reason by default.
- OpenAI uses Responses API only when search or reasoning is requested; other requests use Chat Completions.
- Web search source citations are currently not rendered as separate source cards; do not rely on this client for citation-rich research.
- For some OpenAI-compatible providers, default `max_tokens` or `temperature` fields may be unsupported. Adjust or extend adapter as needed.
- This source tree has not been compiled in this environment (Android SDK/Gradle not installed here). Check the GitHub Actions log for any compilation issues, then test keyboard in portrait and landscape on your device.

## Build with GitHub Actions (from Android/Termux)

1. Download and unzip the archive. Open the **Neura-Universal** folder in Termux.
2. Create a new empty GitHub repository yourself (public for open-source distribution).
3. `pkg install git gh unzip -y` and `gh auth login`.
4. `git init && git branch -M main && git add . && git commit -m "Initial Neura Universal"`.
5. `git remote add origin https://github.com/USERNAME/REPO.git` (replace URL).
6. `git push -u origin main`. GitHub Actions starts on push, or run manually with `gh workflow run android.yml`.
7. See `gh run list`; to see failures: `gh run view RUN_ID --log-failed | tail -n 100`.
8. On success download the artifact **Neura-Universal-debug-apk** from Actions → completed run → Artifacts.

The workflow uses `ubuntu-24.04`, Java 17, Gradle 8.9 and SDK 35; it does **not** run the `android-actions/setup-android@v3` action that previously attempted to install the removed `tools` SDK package.

## Application architecture

- `app/src/main/java/com/neura/app/MainActivity.kt` — Compose interface / chat view state
- `app/src/main/java/com/neura/app/UniversalApi.kt` — provider adapters and SSE parsing
- `app/src/main/java/com/neura/app/Store.kt` — local chats and per-provider secure key storage
- `app/src/main/java/com/neura/app/Markdown.kt` — markdown and code display
- `.github/workflows/android.yml` — CI build to APK

Contributions are welcome under the MIT License. This project is independent of OpenCode, OpenAI, Anthropic, and Google.


## Проверка исправленной версии

Исправлена компиляция `SelectionContainer` (импорт в MainActivity.kt) и валидация URL (java.net.URI вместо устаревшего HttpUrl.parse). Исходники проверены статически; APK и Gradle-сборка в подготовительной среде не выполнялись из-за отсутствия Android SDK и доступа к репозиториям Gradle. Сборку проверяет GitHub Actions.
