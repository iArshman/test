package me.eternal.purrfect.ui.setup.screens.impl

import me.eternal.purrfect.common.TargetApp

class PatchSnapchatScreen(
    selectedAppsProvider: () -> Set<TargetApp> = { setOf(TargetApp.SNAPCHAT) },
    flow: SetupInstallFlow = SetupInstallFlow.PATCH,
    allowInstalledTarget: Boolean = false
) : TargetAppInstallScreen(
    selectedAppsProvider = selectedAppsProvider,
    flow = flow,
    allowInstalledTarget = allowInstalledTarget
)
