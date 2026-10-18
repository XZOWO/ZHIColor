package com.naivety.zhicolor.ui.screen

/** Keeps the edited values within the same hierarchy as the renderer configuration. */
internal object LyricFieldValuePolicy {
    fun maximum(key: String, values: Map<String, Any>, defaultMaximum: Int): Int = when (key) {
        "current_unrevealed_opacity_percent", "active_translation_progress_opacity_percent" ->
            number(values, "active_opacity_percent", 100)
        "active_translation_opacity_percent" ->
            number(values, "active_translation_progress_opacity_percent", 100)
        else -> defaultMaximum
    }.coerceAtMost(defaultMaximum)

    fun update(values: MutableMap<String, Any>, key: String, value: Any) {
        values[key] = value
        if (key == "active_opacity_percent" || key == "active_translation_progress_opacity_percent") {
            val active = number(values, "active_opacity_percent", 100)
            values["current_unrevealed_opacity_percent"] =
                number(values, "current_unrevealed_opacity_percent", active).coerceAtMost(active)
            val progress = number(values, "active_translation_progress_opacity_percent", active)
                .coerceAtMost(active)
            values["active_translation_progress_opacity_percent"] = progress
            values["active_translation_opacity_percent"] =
                number(values, "active_translation_opacity_percent", progress).coerceAtMost(progress)
        }
    }

    private fun number(values: Map<String, Any>, key: String, fallback: Int): Int =
        (values[key] as? Number)?.toInt() ?: fallback
}
