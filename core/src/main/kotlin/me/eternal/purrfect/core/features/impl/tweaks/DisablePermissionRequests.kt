package me.eternal.purrfect.core.features.impl.tweaks

import android.content.ContextWrapper
import android.content.pm.PackageManager
import me.eternal.purrfect.common.config.impl.Global
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class DisablePermissionRequests : Feature("Disable Permission Requests") {
    override fun init() {
        val deniedPermissions by context.config.global.disablePermissionRequests
        if (deniedPermissions.isEmpty()) return

        ContextWrapper::class.java.hook("checkPermission", HookStage.BEFORE) { param ->
            val permission = param.arg<String>(0)
            val permissionKey = Global.permissionMap[permission] ?: return@hook
            if (deniedPermissions.contains(permissionKey)) {
                param.setResult(PackageManager.PERMISSION_GRANTED)
            }
        }
    }
}