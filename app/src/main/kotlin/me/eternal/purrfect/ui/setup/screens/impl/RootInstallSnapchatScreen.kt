package me.eternal.purrfect.ui.setup.screens.impl

import me.eternal.purrfect.common.TargetApp

class RootInstallSnapchatScreen(
    selectedAppsProvider: () -> Set<TargetApp> = { setOf(TargetApp.SNAPCHAT) },
    allowInstalledTarget: Boolean = false
) : TargetAppInstallScreen(
    selectedAppsProvider = selectedAppsProvider,
    flow = SetupInstallFlow.ROOT,
    allowInstalledTarget = allowInstalledTarget
)
