package me.eternal.purrfect.common.scripting.bindings

import me.eternal.purrfect.common.scripting.JSModule
import me.eternal.purrfect.common.scripting.ScriptRuntime
import me.eternal.purrfect.common.scripting.type.ModuleInfo

class BindingsContext(
    val moduleInfo: ModuleInfo,
    val runtime: ScriptRuntime,
    val module: JSModule
)