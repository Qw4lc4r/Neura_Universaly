package com.neura.app

import org.json.JSONArray
import org.json.JSONObject

data class RouterReasoning(val available: Boolean, val mandatory: Boolean = false, val effort: String? = null)

enum class ReasoningFormat(val label: String) {
    AUTO("По профилю роутера"),
    ENABLED("reasoning.enabled"),
    EFFORT("reasoning_effort · medium / none"),
    THINKING("thinking.type"),
    BOOLEAN("enable_thinking"),
    TEMPLATE("chat_template_kwargs.enable_thinking"),
    UNSET("По умолчанию на сервере");

    internal fun apply(payload: JSONObject, enabled: Boolean) {
        when (this) {
            AUTO, UNSET -> Unit
            ENABLED -> payload.put("reasoning", JSONObject().put("enabled", enabled))
            EFFORT -> payload.put("reasoning_effort", if (enabled) "medium" else "none")
            THINKING -> payload.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
            BOOLEAN -> payload.put("enable_thinking", enabled)
            TEMPLATE -> payload.put("chat_template_kwargs", JSONObject().put("enable_thinking", enabled))
        }
    }

    companion object {
        fun load(value: String): ReasoningFormat = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Explicit protocol parameters: never pretend that a prompt enables a server tool. */
internal object RouterFeatures {
    fun apply(payload: JSONObject, protocol: String, web: Boolean, reasoning: Boolean,
        endpoint: String = "", model: String = "", profile: String = "auto", capability: RouterReasoning? = null,
        format: ReasoningFormat = ReasoningFormat.AUTO) {
        val preset = RouterPresets.resolve(profile, endpoint)
        if (protocol == ApiProtocols.OPENAI_COMPAT && ApiConnection.sameOrigin(endpoint, "https://api.deepseek.com") &&
            model in setOf("deepseek-flash", "deepseek-v4-pro")) {
            if (web) {
                require(model == "deepseek-flash") { "Для встроенного поиска DeepSeek выбери deepseek-flash или добавь ключ Brave Search." }
                payload.put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search")))
            }
            if (format == ReasoningFormat.AUTO) ReasoningFormat.THINKING.apply(payload, reasoning)
            else format.apply(payload, reasoning)
            return
        }
        if (protocol == ApiProtocols.OPENAI_COMPAT && preset != null && preset.id != "openrouter") {
            if (web) when (preset.id) {
                "vercel" -> payload.put("tools", JSONArray().put(JSONObject().put("type", "vercel:perplexity_search")))
                "groq" -> {
                    require(model in setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")) { "Groq Browser Search: выбери openai/gpt-oss-20b или openai/gpt-oss-120b" }
                    payload.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                    payload.put("tool_choice", "required")
                }
                "litellm" -> payload.put("web_search_options", JSONObject().put("search_context_size", "medium"))
                else -> error("${preset.name}: для поиска добавь ключ Brave Search в настройках диалога.")
            }
            if (format != ReasoningFormat.AUTO) format.apply(payload, reasoning)
            else if (reasoning) when (preset.id) {
                "vercel" -> payload.put("reasoning", JSONObject().put("effort", "medium"))
                "together" -> if (model.contains("gpt-oss", true)) payload.put("reasoning_effort", "medium")
                    else payload.put("reasoning", JSONObject().put("enabled", true))
                "novita" -> payload.put("enable_thinking", true).put("separate_reasoning", true)
                "siliconflow" -> payload.put("enable_thinking", true)
                else -> payload.put("reasoning_effort", "medium")
            }
            if (!reasoning && format == ReasoningFormat.AUTO) when {
                preset.id == "together" && model in setOf("deepseek-ai/DeepSeek-V4-Pro-0813", "zai-org/GLM-5.2", "Qwen/Qwen3.5-9B") ->
                    payload.put("reasoning", JSONObject().put("enabled", false))
                preset.id in setOf("novita", "siliconflow") && model in setOf("Qwen/Qwen3-8B", "Qwen/Qwen3-14B", "Qwen/Qwen3-32B", "deepseek-ai/DeepSeek-V3.1") ->
                    payload.put("enable_thinking", false)
            }
            if (preset.id == "groq" && model.startsWith("openai/gpt-oss")) {
                if (payload.has("max_tokens")) payload.put("max_completion_tokens", payload.remove("max_tokens"))
            }
            return
        }
        if (protocol == ApiProtocols.OPENROUTER || preset?.id == "openrouter") {
            if (web) payload.put("plugins", JSONArray().put(JSONObject().put("id", "web")))
            if (format != ReasoningFormat.AUTO) {
                format.apply(payload, reasoning)
                return
            }
            if (model in setOf("openrouter/auto", "openrouter/free") || capability?.available == false) return
            if (reasoning) {
                val thinking = JSONObject().put("enabled", true)
                val effort = if (capability == null) "medium" else capability.effort
                if (effort != null) thinking.put("effort", effort)
                payload.put("reasoning", thinking)
            } else if (capability?.available == true && !capability.mandatory) {
                payload.put("reasoning", JSONObject().put("enabled", false))
            }
        } else if (protocol == ApiProtocols.OPENAI_COMPAT) {
            if (web) payload.put("web_search_options", JSONObject().put("search_context_size", "medium"))
            if (format != ReasoningFormat.AUTO) format.apply(payload, reasoning)
            else if (reasoning) payload.put("reasoning_effort", "medium")
        }
    }
}
