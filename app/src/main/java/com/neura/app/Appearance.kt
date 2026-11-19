package com.neura.app

data class Appearance(
    val systemTheme: Boolean = true, val accent: String = "moss", val textScale: Float = 1f,
    val compact: Boolean = false, val showModel: Boolean = true, val autoScroll: Boolean = true
) {
    fun save(store: LocalStore) {
        store.set("ui_system_theme", systemTheme); store.set("ui_accent", accent)
        store.set("ui_text_scale", textScale.toString()); store.set("ui_compact", compact)
        store.set("ui_show_model", showModel); store.set("ui_auto_scroll", autoScroll)
    }
    companion object {
        fun load(store: LocalStore) = Appearance(
            store.bool("ui_system_theme", true), store.string("ui_accent", "moss"),
            store.string("ui_text_scale", "1").toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(.85f, 1.3f) ?: 1f,
            store.bool("ui_compact"), store.bool("ui_show_model", true), store.bool("ui_auto_scroll", true))
    }
}
