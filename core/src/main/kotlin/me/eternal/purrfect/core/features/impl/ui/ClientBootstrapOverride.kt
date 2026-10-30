package me.eternal.purrfect.core.features.impl.ui

import me.eternal.purrfect.common.config.impl.UserInterfaceTweaks
import me.eternal.purrfect.common.util.protobuf.ProtoEditor
import me.eternal.purrfect.common.util.protobuf.ProtoWriter
import me.eternal.purrfect.core.features.Feature
import java.io.File


class ClientBootstrapOverride: Feature("ClientBootstrapOverride") {

    private val clientBootstrapFolder by lazy { File(context.androidContext.filesDir, "client-bootstrap") }

    private val appearanceStartupConfigFile by lazy { File(clientBootstrapFolder, "appearancestartupconfig") }
    private val plusFile by lazy { File(clientBootstrapFolder, "plus") }

    override fun init() {
        val bootstrapOverrideConfig = context.config.userInterface.bootstrapOverride
        val forceAmoledTheme = context.config.userInterface.forceAmoledTheme.get()
        val effectiveAppAppearance = if (forceAmoledTheme) {
            "always_dark"
        } else {
            bootstrapOverrideConfig.appAppearance.getNullable()
        }

        if (!clientBootstrapFolder.exists() && (effectiveAppAppearance != null || bootstrapOverrideConfig.homeTab.getNullable() != null)) {
            clientBootstrapFolder.mkdirs()
        }

        effectiveAppAppearance?.also { appearance ->
            val state = when (appearance) {
                "always_light" -> 0
                "always_dark" -> 1
                else -> return@also
            }.toByte()
            appearanceStartupConfigFile.writeBytes(byteArrayOf(0, 0, 0, state))
        }

        val homeTab = bootstrapOverrideConfig.homeTab.getNullable()

        if (homeTab != null) {
            val plusFileBytes = plusFile.exists().let { if (it) plusFile.readBytes() else ProtoWriter().toByteArray() }

            plusFile.writeBytes(
                ProtoEditor(plusFileBytes).apply {
                    edit {
                        homeTab.let { currentTab ->
                            remove(1)
                            addVarInt(1, UserInterfaceTweaks.BootstrapOverride.tabs.indexOf(currentTab) + 1)
                        }
                    }
                }.toByteArray()
            )
        }
    }
}
