package com.neura.app

import java.net.URI

data class RouterPreset(val id: String, val name: String, val endpoint: String, val note: String)

/** Curated compatibility profiles, not a popularity ranking or an inference-access guarantee. */
object RouterPresets {
    val all = listOf(
        RouterPreset("onekey", "OneKey AI", "https://api.onekey-ai.lol/v1", "GPT: Responses; Claude: Messages + Bearer. Поиск зависит от того, какие инструменты пропускает шлюз; публичной спецификации нет."),
        RouterPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "Поиск через web plugin. Рассуждения через reasoning; поддержка зависит от модели."),
        RouterPreset("vercel", "Vercel AI Gateway", "https://ai-gateway.vercel.sh/v1", "Серверный поиск Perplexity; рассуждения через reasoning. Поиск может оплачиваться отдельно."),
        RouterPreset("together", "Together AI", "https://api.together.ai/v1", "reasoning.enabled; GPT-OSS использует reasoning_effort. Встроенный поиск в этом адаптере не подключён."),
        RouterPreset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "reasoning_effort для reasoning-моделей. Встроенный поиск в этом адаптере не подключён."),
        RouterPreset("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", "reasoning_effort для поддерживающих моделей. Встроенный поиск в этом адаптере не подключён."),
        RouterPreset("groq", "Groq", "https://api.groq.com/openai/v1", "Browser Search для openai/gpt-oss-20b и 120b; reasoning_effort для GPT-OSS."),
        RouterPreset("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "Чатовый маршрут. reasoning_effort зависит от backend модели; медиа и встроенный поиск здесь не подключены."),
        RouterPreset("novita", "Novita AI", "https://api.novita.ai/openai/v1", "enable_thinking и separate_reasoning для поддерживающих моделей. Встроенный поиск не подключён."),
        RouterPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.com/v1", "enable_thinking для гибридных моделей. Встроенный поиск в этом адаптере не подключён."),
        RouterPreset("litellm", "LiteLLM Gateway", "", "Укажи HTTPS-адрес своего шлюза. web_search_options и reasoning_effort переводятся сервером, если это настроено администратором.")
    )
    fun get(id: String) = all.firstOrNull { it.id == id }
    fun detect(endpoint: String): RouterPreset? {
        val host = runCatching { URI(endpoint).host?.lowercase() }.getOrNull() ?: return null
        return all.firstOrNull { it.endpoint.isNotBlank() && URI(it.endpoint).host == host }
            ?: when (host) {
                "api.together.xyz" -> get("together")
                "api.siliconflow.cn" -> get("siliconflow")
                else -> null
            }
    }
    fun resolve(profile: String, endpoint: String) = get(profile) ?: if (profile == "auto") detect(endpoint) else null

    fun protocol(protocol: String, profile: String, endpoint: String, model: String): String {
        if (resolve(profile, endpoint)?.id != "onekey" || protocol !in setOf(ApiProtocols.OPENAI_COMPAT, ApiProtocols.RESPONSES)) return protocol
        return if (model.substringAfterLast('/').startsWith("claude-", true)) ApiProtocols.ANTHROPIC else ApiProtocols.RESPONSES
    }
}
