package me.eternal.purrfect.common

enum class TargetApp(val key: String) {
    SNAPCHAT("snapchat"),
    REDDIT("reddit"),
    WHATSAPP("whatsapp"),
    INSTAGRAM("instagram");

    companion object {
        const val PREF_KEY = "active_target_app"

        fun fromKeyOrNull(key: String?): TargetApp? {
            return entries.firstOrNull { it.key == key }
        }

        fun fromKey(key: String?): TargetApp {
            return fromKeyOrNull(key) ?: SNAPCHAT
        }
    }
}
