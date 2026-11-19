# Профили роутеров Neura 1.4

Проверено по публичной документации 18 ноября 2026. Это подборка десяти распространённых сервисов плюс OneKey пользователя, а не измеренный рейтинг популярности. В список включены шлюзы и платформы inference.

## OneKey AI

Публичная [страница подключения](https://app.onekey-ai.lol/web) указывает OpenAI base URL `https://api.onekey-ai.lol/v1`, для Codex — `wire_api = responses`, для Claude — нативный Gateway с Bearer-авторизацией. Это данные публичного HTML, не доступ к аккаунту пользователя. Публичные `/docs` и `/openapi.json` на API-хосте отвечали 404.

В Neura выбери **OneKey AI**, введи ключ локально и загрузи модели. Для GPT запрос идёт на `/v1/responses`; для модели с ID `claude-*` — `/v1/messages` с `Authorization: Bearer`. Список моделей запрашивается на `/v1/models` с Bearer. Если OneKey уже сохранён как свой API, профиль **Авто** распознает точный API-хост. Адрес `app.onekey-ai.lol/web` — кабинет, не Base URL API.

Поиск (`web_search`) и reasoning передаются в формате выбранного нативного протокола. Публичная страница не гарантирует пропуск этих инструментов, их поддержку каждой моделью и права конкретного ключа. Без реального ключа проверены структура, маршрут и заголовки на тестовом HTTPS-сервере; реальная генерация OneKey не проверялась. Для неизвестных семейств OneKey по умолчанию пробует Responses: если шлюз их там не обслуживает, выбери подходящий протокол и профиль «Общий формат протокола».

## Десять дополнительных сервисов

| Сервис | Base URL | Рассуждения | Поиск в реализации Neura |
| --- | --- | --- | --- |
| [OpenRouter](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens) | `https://openrouter.ai/api/v1` | `reasoning.effort` | [web plugin](https://openrouter.ai/docs/guides/features/plugins/web-search), доступность/оплата на стороне сервиса |
| [Vercel AI Gateway](https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions) | `https://ai-gateway.vercel.sh/v1` | `reasoning.effort` | [серверный инструмент](https://vercel.com/docs/ai-gateway/models-and-providers/web-search) `vercel:perplexity_search`, без отдельного поискового ключа клиента |
| [Together AI](https://docs.together.ai/docs/inference/chat/reasoning) | `https://api.together.ai/v1` | `reasoning.enabled`, для GPT-OSS `reasoning_effort` | Встроенный поиск в адаптере не подключён |
| [Fireworks](https://docs.fireworks.ai/guides/reasoning) | `https://api.fireworks.ai/inference/v1` | `reasoning_effort` | Встроенный поиск в адаптере не подключён |
| [DeepInfra](https://deepinfra.com/blog/deepseek-v4-1-flash-model-overview-integration) | `https://api.deepinfra.com/v1/openai` | `reasoning_effort`; принимаемые уровни зависят от модели | Встроенный поиск в адаптере не подключён |
| [Groq](https://console.groq.com/docs/reasoning) | `https://api.groq.com/openai/v1` | `reasoning_effort` для поддерживающих моделей | [browser_search](https://console.groq.com/docs/tool-use/built-in-tools/browser-search), для `openai/gpt-oss-20b` и `openai/gpt-oss-120b` |
| [Hugging Face](https://huggingface.co/docs/inference-providers/index) | `https://router.huggingface.co/v1` | Совместимый `reasoning_effort`: поддержка зависит от backend модели | Встроенный поиск не подключён; универсальные image/audio/video API этим чатовым маршрутом не предоставляются |
| [Novita](https://docs.novita.ai/api-reference/model-apis-llm-create-chat-completion) | `https://api.novita.ai/openai/v1` | `enable_thinking`, `separate_reasoning`; только совместимые модели | Встроенный поиск в адаптере не подключён |
| [SiliconFlow](https://docs.siliconflow.com/en/api-reference/chat-completions/chat-completions) | `https://api.siliconflow.com/v1` | `enable_thinking`, для моделей с такой возможностью | Встроенный поиск в адаптере не подключён |
| [LiteLLM](https://docs.litellm.ai/docs/completion/web_search) | Собственный HTTPS URL шлюза | [reasoning_effort](https://docs.litellm.ai/docs/reasoning_content), перевод на стороне шлюза | `web_search_options`, если администратор настроил подходящий upstream |

Если в таблице поиск не подключён, Neura останавливает запрос до сети с объяснением. Это не утверждение об отсутствии у сервиса других search-продуктов: клиентские tool loops, отдельный поисковый API и дополнительные ключи в этой версии не реализованы. Автоматических платных проб, смены модели или повторной генерации для подбора параметров нет.

## Выбор профиля

В **Настройки → Подключение → Профиль**:

- **Авто** — определение известного сервиса только по точному хосту HTTPS URL. Похожее имя/путь и поддомены другого сервиса не подходят.
- Именной профиль — для своего reverse proxy или LiteLLM с произвольным доменом. Меняет сериализацию параметров, не адрес назначения и не ключ.
- **Общий формат протокола** — отключает автоопределение сервиса, оставляет выбранный протокол (Chat Completions, Responses, Anthropic, Gemini, OpenRouter).

При выборе готового провайдера подставляется его адрес; ключи и модели хранятся раздельно. LiteLLM не имеет общего публичного адреса — его нужно ввести. Старый домен Together `api.together.xyz` и региональный SiliconFlow `api.siliconflow.cn` тоже распознаются.

Выключенный переключатель reasoning означает, что клиент не запрашивает дополнительное reasoning. Модели с обязательным или включённым по умолчанию thinking могут продолжить рассуждать. Полное отключение требует модельного параметра, который нельзя без проверки навязывать всем моделям сервиса.

## Что проверено

- Сериализация разных профилей, отсутствие чужих параметров, проверка модели Groq и честный отказ неподключённого поиска.
- Реальные локальные HTTPS-запросы через OkHttp и корутины: маршрут, Bearer, нативные заголовки и JSON. OneKey GPT и Claude проверяются отдельно.
- Существующие проверки SSE, reasoning, аудио PCM/WAV, пагинации, отмены соединений и безопасного origin.

Тестовый сервер не подтверждает доступность платного сервиса, права пользовательского ключа или фактическое выполнение поиска upstream. Нужна проверка на телефоне с введённым локально ключом. Сами ключи не включены ни в проект, ни в тесты.
