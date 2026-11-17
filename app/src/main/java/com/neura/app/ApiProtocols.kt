package com.neura.app

/**
 * Protocol is independent of the company or host that provides it.
 * An OpenAI-compatible server need not expose images, speech or video endpoints;
 * those features are attempted only when the selected model declares them or
 * the user explicitly chooses the route for a manually entered model.
 */
object ApiProtocols {
    const val OPENAI = "openai_native"
    const val OPENAI_COMPAT = "openai_compatible"
    const val OPENROUTER = "openrouter_compatible"
    const val GEMINI = "gemini_native"
    const val ANTHROPIC = "anthropic_native"

    val customizable = listOf(OPENAI_COMPAT, OPENROUTER, GEMINI, ANTHROPIC, OPENAI)

    fun defaultFor(provider: String): String = when (provider) {
        "openai" -> OPENAI
        "openrouter" -> OPENROUTER
        "gemini" -> GEMINI
        "anthropic" -> ANTHROPIC
        else -> OPENAI_COMPAT
    }

    fun resolve(provider: String, customProtocol: String): String =
        if (provider == "custom" && customProtocol in customizable) customProtocol else defaultFor(provider)

    fun label(protocol: String): String = when (protocol) {
        OPENAI -> "OpenAI (официальный API)"
        OPENAI_COMPAT -> "OpenAI-совместимый"
        OPENROUTER -> "OpenRouter-совместимый"
        GEMINI -> "Gemini Native"
        ANTHROPIC -> "Anthropic Native"
        else -> "Неизвестный протокол"
    }

    fun modelCatalogFormat(protocol: String): String = when (protocol) {
        GEMINI -> "gemini"
        ANTHROPIC -> "anthropic"
        OPENROUTER -> "openrouter"
        else -> "openai"
    }

    /** Endpoint route selection; no guessing image support from a provider name. */
    fun imageRoute(protocol: String, hasDedicatedImageEndpoint: Boolean): String = when (protocol) {
        GEMINI -> "gemini_generate_content"
        ANTHROPIC -> "unsupported"
        OPENROUTER -> if (hasDedicatedImageEndpoint) "images" else "chat_images"
        OPENAI -> if (hasDedicatedImageEndpoint) "images_generations" else "chat_images"
        OPENAI_COMPAT -> if (hasDedicatedImageEndpoint) "images_generations" else "chat_images"
        else -> "unsupported"
    }

    /** Whether the client has a compatible transport for the selected media route. */
    fun canRun(protocol: String, mediaRoute: String): Boolean = when (mediaRoute) {
        MediaRoute.CHAT -> true
        MediaRoute.IMAGE -> protocol != ANTHROPIC
        MediaRoute.SPEECH, MediaRoute.TRANSCRIPTION -> protocol in setOf(OPENAI, OPENAI_COMPAT, OPENROUTER, GEMINI)
        MediaRoute.VIDEO -> protocol in setOf(OPENAI, OPENAI_COMPAT, OPENROUTER, GEMINI)
        else -> false
    }

    fun supportsWeb(protocol: String): Boolean = protocol in setOf(OPENAI, OPENAI_COMPAT, OPENROUTER, GEMINI, ANTHROPIC)
    fun supportsReasoning(protocol: String): Boolean = protocol in setOf(OPENAI, GEMINI, ANTHROPIC)
}


/** Search transport is selected independently from the model/provider.
 *  There is no standard web-search extension for arbitrary OpenAI-compatible gateways.
 */
object WebSearchModes {
    const val AUTO = "auto"
    const val CHAT_OPTIONS = "chat_web_search_options"
    const val OPENROUTER_PLUGIN = "openrouter_web_plugin"
    const val RESPONSES = "responses_web_search"

    fun label(mode: String): String = when (mode) {
        AUTO -> "Автоматически"
        CHAT_OPTIONS -> "Chat Completions · web_search_options"
        OPENROUTER_PLUGIN -> "OpenRouter · web plugin"
        RESPONSES -> "Responses API · web_search"
        else -> "Автоматически"
    }

    fun options(protocol: String): List<String> = when (protocol) {
        ApiProtocols.OPENAI_COMPAT, ApiProtocols.OPENROUTER -> listOf(AUTO, CHAT_OPTIONS, OPENROUTER_PLUGIN, RESPONSES)
        ApiProtocols.OPENAI -> listOf(AUTO, RESPONSES)
        else -> listOf(AUTO)
    }

    fun resolve(protocol: String, selected: String): String {
        if (selected != AUTO && selected in options(protocol)) return selected
        return when (protocol) {
            ApiProtocols.OPENROUTER -> OPENROUTER_PLUGIN
            ApiProtocols.OPENAI -> RESPONSES
            ApiProtocols.OPENAI_COMPAT -> CHAT_OPTIONS
            else -> AUTO // Gemini and Anthropic use their own native search tools.
        }
    }
}
