package me.eternal.purrfect.common.logger

enum class LogChannel(
    val channel: String,
    val shortName: String
) {
    CORE("PurrfectCore", "core"),
    COMMON("PurrfectCommon", "common"),
    SCRIPTING("Scripting", "scripting"),
    NATIVE("PurrfectNative", "native"),
    MANAGER("PurrfectManager", "manager"),
    XPOSED("LSPosed-Bridge", "xposed");

    companion object {
        fun fromChannel(channel: String): LogChannel? {
            return entries.find { it.channel == channel }
        }
    }
}