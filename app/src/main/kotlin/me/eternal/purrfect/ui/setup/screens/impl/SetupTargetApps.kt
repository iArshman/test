package me.eternal.purrfect.ui.setup.screens.impl

import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.TargetApp

internal data class SetupInstallTarget(
    val targetApp: TargetApp,
    val displayName: String,
    val packageName: String,
    val alternatePackageNames: Set<String> = emptySet()
) {
    val packageNames: Set<String>
        get() = linkedSetOf(packageName).apply { addAll(alternatePackageNames) }
}

internal val setupTargetOrder = listOf(TargetApp.SNAPCHAT, TargetApp.INSTAGRAM, TargetApp.REDDIT, TargetApp.WHATSAPP)

internal fun TargetApp.toSetupInstallTarget(): SetupInstallTarget {
    return when (this) {
        TargetApp.SNAPCHAT -> SetupInstallTarget(
            targetApp = this,
            displayName = "Snapchat",
            packageName = Constants.SNAPCHAT_PACKAGE_NAME
        )

        TargetApp.REDDIT -> SetupInstallTarget(
            targetApp = this,
            displayName = "Reddit",
            packageName = Constants.REDDIT_PACKAGE_NAME
        )

        TargetApp.WHATSAPP -> SetupInstallTarget(
            targetApp = this,
            displayName = "WhatsApp",
            packageName = Constants.WHATSAPP_PACKAGE_NAME
        )

        TargetApp.INSTAGRAM -> SetupInstallTarget(
            targetApp = this,
            displayName = "Instagram",
            packageName = Constants.INSTAGRAM_PACKAGE_NAME,
            alternatePackageNames = Constants.INSTAGRAM_PACKAGE_NAMES - Constants.INSTAGRAM_PACKAGE_NAME
        )
    }
}

internal fun setupInstallTargets(selectedApps: Set<TargetApp>): List<SetupInstallTarget> {
    val normalized = setupTargetOrder.filter { it in selectedApps }
    return normalized.ifEmpty { listOf(TargetApp.SNAPCHAT) }
        .map { it.toSetupInstallTarget() }
}

internal fun parseSetupTargetApps(value: String?, fallback: Set<TargetApp> = emptySet()): Set<TargetApp> {
    val parsed = value.orEmpty()
        .split(',')
        .mapNotNull { key ->
            TargetApp.entries.firstOrNull { it.key == key.trim() }
        }
        .toSet()
    return parsed.ifEmpty { fallback }
}

internal fun Set<TargetApp>.toSetupTargetPrefsValue(): String {
    return setupTargetOrder
        .filter { it in this }
        .joinToString(",") { it.key }
}
