package com.naivety.zhicolor.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    CHINESE("zh-Hans"),
    ENGLISH("en");

    companion object {
        private const val PREFERENCES = "zhicolor_language"
        private const val LANGUAGE = "language_tag"
        private const val PLATFORM_MIGRATED = "platform_migrated"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales?.getOrNull(0)?.toLanguageTag().orEmpty()
            } else {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getString(LANGUAGE, "").orEmpty()
            }
            return fromLanguageTag(tag)
        }

        fun set(context: Context, language: AppLanguage) {
            if (current(context) == language) return
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    LocaleList.forLanguageTags(language.languageTag)
            } else {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(LANGUAGE, language.languageTag).apply()
                applyLegacyConfiguration(context.applicationContext)
                context.findActivity()?.let { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                }
            }
        }

        /** Called before the first Activity so an OS upgrade retains an existing choice once. */
        fun initialize(context: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getBoolean(PLATFORM_MIGRATED, false)) return
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val saved = fromLanguageTag(preferences.getString(LANGUAGE, "").orEmpty())
            if (manager.applicationLocales.isEmpty && saved != SYSTEM) {
                manager.applicationLocales = LocaleList.forLanguageTags(saved.languageTag)
            }
            preferences.edit().putBoolean(PLATFORM_MIGRATED, true).apply()
        }

        fun wrap(context: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return context
            val selected = current(context)
            return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                // A previously wrapped Application may still carry the old choice.
                // Resolve SYSTEM from system resources explicitly when clearing it.
                setLocales(if (selected == SYSTEM) Resources.getSystem().configuration.locales
                    else LocaleList.forLanguageTags(selected.languageTag))
            })
        }

        /** Application-context strings also follow changes made on Android 12. */
        @Suppress("DEPRECATION")
        fun applyLegacyConfiguration(context: Context) {
            if (Build.VERSION.SDK_INT >= 33) return
            val selected = current(context)
            val locales = if (selected == SYSTEM) Resources.getSystem().configuration.locales
                else LocaleList.forLanguageTags(selected.languageTag)
            val resources = context.resources
            val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }

        internal fun fromLanguageTag(tag: String): AppLanguage {
            if (tag.isBlank()) return SYSTEM
            return when (Locale.forLanguageTag(tag).language) {
                "zh" -> CHINESE
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }

        private fun LocaleList.getOrNull(index: Int): Locale? =
            if (index < size()) get(index) else null

        private fun Context.findActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
            else -> null
        }
    }
}
