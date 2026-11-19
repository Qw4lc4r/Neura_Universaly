package com.neura.app

/**
 * Protocol is independent of the company or host that provides it.
 * An OpenAI-compatible server need not expose images, speech or video endpoints;
 * those features are attempted only when the selected model declares them or
 * the user explicitly chooses the route for a manually entered model.
 */
object ApiProtocols {
    const val RESPONSES = "openai_responses"
    const val OPENAI = "openai_native"
    const val OPENAI_COMPAT = "openai_compatible"
    const val OPENROUTER = "openrouter_compatible"
    const val GEMINI = "gemini_native"
    const val ANTHROPIC = "anthropic_native"

    val customizable = listOf(OPENAI_COMPAT, OPENROUTER, GEMINI, ANTHROPIC, OPENAI, RESPONSES)

    fun defaultFor(provider: String): String = when (provider) {
        "onekey" -> RESPONSES
        "openai" -> OPENAI
        "openrouter" -> OPENROUTER
        "gemini" -> GEMINI
        "anthropic" -> ANTHROPIC
        else -> OPENAI_COMPAT
    }

    fun resolve(provider: String, customProtocol: String): String =
        if (Providers.configurable(provider) && customProtocol in customizable) customProtocol else defaultFor(provider)

    fun label(protocol: String): String = when (protocol) {
        OPENAI -> "OpenAI (автоматический маршрут)"
        RESPONSES -> "OpenAI Responses"
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
        RESPONSES -> if (hasDedicatedImageEndpoint) "images_generations" else "chat_images"
        OPENAI -> if (hasDedicatedImageEndpoint) "images_generations" else "chat_images"
        OPENAI_COMPAT -> if (hasDedicatedImageEndpoint) "images_generations" else "chat_images"
        else -> "unsupported"
    }

    /** Whether the client has a compatible transport for the selected media route. */
    fun canRun(protocol: String, mediaRoute: String): Boolean = when (mediaRoute) {
        MediaRoute.CHAT -> true
        MediaRoute.IMAGE -> protocol != ANTHROPIC
        MediaRoute.SPEECH, MediaRoute.TRANSCRIPTION -> protocol in setOf(OPENAI, RESPONSES, OPENAI_COMPAT, OPENROUTER, GEMINI)
        MediaRoute.VIDEO -> protocol in setOf(OPENAI, RESPONSES, OPENAI_COMPAT, OPENROUTER, GEMINI)
        else -> false
    }

    fun supportsWeb(protocol: String): Boolean = protocol in customizable
    fun supportsReasoning(protocol: String): Boolean = protocol in customizable
}
