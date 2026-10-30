package me.eternal.purrfect.core.whatsapp

import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.Reader
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object WhatsAppDetectionHooks {
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val PACKAGE_SOURCE_STORE = 2
    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val X25519_KEY_SIZE = 32
    private val X25519_P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val X25519_A24 = BigInteger.valueOf(121665)
    private val X25519_BASE_POINT = ByteArray(X25519_KEY_SIZE).apply { this[0] = 9 }

    private val earlyInstalled = AtomicBoolean(false)
    private val runtimeInstalled = AtomicBoolean(false)
    private val buildSpoofInstalled = AtomicBoolean(false)
    private val systemPropertyHooksInstalled = AtomicBoolean(false)
    private val installerHooksInstalled = AtomicBoolean(false)
    private val firebaseMessagingGuardInstalled = AtomicBoolean(false)
    private val hookInjectionShieldInstalled = AtomicBoolean(false)
    private val passiveHookEvidenceShieldInstalled = AtomicBoolean(false)
    private val rootDebugEnvironmentHooksInstalled = AtomicBoolean(false)
    private val hookInjectionBootstrapGuardInstalled = AtomicBoolean(false)
    private val registrationSignalHooksInstalled = AtomicBoolean(false)
    private val switchboardLogged = AtomicBoolean(false)
    private val callerCheck = ThreadLocal.withInitial { false }
    private val procPathStreams = Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val procPathReaders = Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val zipFilePaths = Collections.synchronizedMap(IdentityHashMap<Any, String>())
    private val originalClassesDexByZipPath = Collections.synchronizedMap(mutableMapOf<String, OriginalClassesDexSnapshot?>())
    private val whatsappInstallSourceObjects = Collections.synchronizedMap(IdentityHashMap<Any, Boolean>())
    private val bootstrapSafeObjects = Collections.synchronizedMap(IdentityHashMap<Class<*>, Any>())
    private val bootstrapRandom = SecureRandom()

    private data class OriginalClassesDexSnapshot(
        val bytes: ByteArray,
        val entry: java.util.zip.ZipEntry
    )

    private val customRomSystemProperties = mapOf(
        "ro.build.tags" to "release-keys",
        "ro.build.type" to "user",
        "ro.build.display.id" to safeBuildDisplay(),
        "ro.build.fingerprint" to safeBuildFingerprint(),
        "ro.build.version.incremental" to safeBuildIncremental(),
        "ro.hardware" to safeBuildPart(Build.HARDWARE, "tensor"),
        "ro.product.brand" to safeBuildPart(Build.BRAND, "google"),
        "ro.product.device" to safeBuildPart(Build.DEVICE, "oriole"),
        "ro.product.manufacturer" to safeBuildPart(Build.MANUFACTURER, "Google"),
        "ro.product.model" to safeBuildPart(Build.MODEL, "Pixel 6"),
        "ro.product.name" to safeBuildPart(Build.PRODUCT, "oriole"),
        "ro.kernel.qemu" to "0",
        "ro.boot.qemu" to "0",
        "ro.modversion" to "",
        "ro.cm.version" to "",
        "ro.lineage.version" to "",
        "ro.crdroid.version" to "",
        "ro.evolution.version" to "",
        "ro.pixelos.version" to "",
        "ro.custom.version" to "",
        "ro.rom.version" to ""
    )

    private val hookClassPrefixes = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed",
        "com.saurik.substrate"
    )

    private val hookClassFragments = listOf(
        "xposed",
        "lsposed",
        "lspatch",
        "edxposed",
        "substrate",
        "frida",
        "hookbridge",
        "lsp_hook",
        "lsphooker",
        "purrfect"
    )

    private val hookPackageNames = setOf(
        BuildConfig.APPLICATION_ID,
        "de.robv.android.xposed.installer",
        "org.meowcat.edxposed.manager",
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "org.lsposed.lspatch",
        "org.lsposed.lspatch.manager",
        "com.saurik.substrate"
    )

    private val hookProbeClassNames = setOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
        "de.robv.android.xposed.IXposedHookLoadPackage",
        "org.lsposed.lspd.impl.LSPosedBridge",
        "org.lsposed.lspd.impl.LSPosedContext",
        "org.lsposed.lspd.nativebridge.HookBridge",
        "io.github.lsposed.lspd.impl.LSPosedBridge",
        "com.saurik.substrate.MS",
        "com.saurik.substrate.SubstrateClassLoader"
    )

    private val procInspectionPaths = listOf(
        "/proc/self/maps",
        "/proc/self/smaps",
        "/proc/self/mountinfo",
        "/proc/self/status"
    )

    private val rootProbePaths = setOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su"
    )

    private val hookBootstrapDetectorClassNames = listOf(
        "X.0gC",
        "X.C11430gC",
        "X.1SC"
    )

    private val installerStringKeys = setOf(
        "installer",
        "installer_package",
        "installer_package_name",
        "installing_package",
        "installing_package_name",
        "originating_package",
        "originating_package_name",
        "initiating_package",
        "initiating_package_name",
        "update_owner_package",
        "update_owner_package_name",
        "install_source",
        "install_source_package",
        "app_campaign_download_source",
        "download_source"
    )

    private val installerBooleanKeys = setOf(
        "install_source_official",
        "is_installed_from_play_store",
        "is_from_play_store",
        "is_official_install_source"
    )

    private val installerIntegerKeys = setOf(
        "package_source",
        "install_package_source"
    )

    private val detectorLogKeyFragments = listOf(
        "rom",
        "installer",
        "install_source",
        "package_source",
        "official",
        "integrity",
        "attestation",
        "play_integrity",
        "hook",
        "injection",
        "xposed",
        "lsposed",
        "lspatch",
        "frida"
    )

    fun install(appClassLoader: ClassLoader) {
        installEarly(appClassLoader)
        installRuntime(appClassLoader)
    }

    @Suppress("UNUSED_PARAMETER")
    fun installEarly(appClassLoader: ClassLoader) {
        if (!earlyInstalled.compareAndSet(false, true)) return
        logSwitchboard()
        installCoreHooks(appClassLoader)
        WhatsAppDetectionEventLogger.lifecycle("Installed early WhatsApp custom ROM and proc-map shields")
    }

    fun installRuntime(appClassLoader: ClassLoader) {
        installEarly(appClassLoader)
        if (!runtimeInstalled.compareAndSet(false, true)) return
        installCoreHooks(appClassLoader)
        installOnce(registrationSignalHooksInstalled) { installRegistrationSignalHooks() }
        WhatsAppDetectionEventLogger.lifecycle("Installed runtime WhatsApp detection logger")
    }

    private fun logSwitchboard() {
        if (!switchboardLogged.compareAndSet(false, true)) return
        WhatsAppDetectionEventLogger.lifecycle(
            "WhatsApp shields active: custom_rom_build_identity,custom_rom_system_properties," +
                "play_store_installer_spoof,hook_injection_proc_map_hiding," +
                "passive_hook_evidence_hiding,root_debug_emulator_hiding,push_registration_guard,detection_logger"
        )
        WhatsAppDetectionEventLogger.lifecycle(
            "WhatsApp shields avoid broad classloader/bootstrap interception; Play Integrity tokens are server-verified and not locally forged"
        )
    }

    private fun installCoreHooks(appClassLoader: ClassLoader) {
        installOnce(buildSpoofInstalled) { spoofCustomRomBuildIdentity() }
        installOnce(systemPropertyHooksInstalled) { installCustomRomSystemPropertyHooks() }
        installOnce(installerHooksInstalled) { installPlayStoreInstallerHooks() }
        installOnce(firebaseMessagingGuardInstalled) { installFirebaseMessagingGuard(appClassLoader) }
        installOnce(hookInjectionShieldInstalled) { installProcMapShield() }
        installOnce(passiveHookEvidenceShieldInstalled) { installPassiveHookEvidenceShield() }
        installOnce(rootDebugEnvironmentHooksInstalled) { installRootDebugEnvironmentHooks() }
    }

    private inline fun installOnce(gate: AtomicBoolean, block: () -> Unit) {
        if (gate.compareAndSet(false, true)) block()
    }

    private fun spoofCustomRomBuildIdentity() {
        setStaticStringField(Build::class.java, "TAGS", "release-keys")
        setStaticStringField(Build::class.java, "TYPE", "user")
        setStaticStringField(Build::class.java, "BRAND", safeBuildPart(Build.BRAND, "google"))
        setStaticStringField(Build::class.java, "DEVICE", safeBuildPart(Build.DEVICE, "oriole"))
        setStaticStringField(Build::class.java, "PRODUCT", safeBuildPart(Build.PRODUCT, "oriole"))
        setStaticStringField(Build::class.java, "MODEL", safeBuildPart(Build.MODEL, "Pixel 6"))
        setStaticStringField(Build::class.java, "MANUFACTURER", safeBuildPart(Build.MANUFACTURER, "Google"))
        setStaticStringField(Build::class.java, "HARDWARE", safeBuildPart(Build.HARDWARE, "tensor"))
        setStaticStringField(Build::class.java, "FINGERPRINT", safeBuildFingerprint())
        setStaticStringField(Build::class.java, "DISPLAY", safeBuildDisplay())
        runCatching {
            setStaticStringField(Build.VERSION::class.java, "INCREMENTAL", safeBuildIncremental())
        }
        WhatsAppDetectionEventLogger.lifecycle("Spoofed WhatsApp custom ROM Build identity")
    }

    private fun installCustomRomSystemPropertyHooks() {
        runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            XposedBridge.hookAllMethods(
                systemPropertiesClass,
                "get",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val clean = customRomSystemProperties[key] ?: return
                        param.result = clean
                        logProbe("custom_rom_system_property", key, "return_${clean.ifBlank { "empty" }}")
                    }
                }
            )
            XposedBridge.hookAllMethods(
                systemPropertiesClass,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val value = customRomSystemProperties[key]?.toBooleanStrictOrNull() ?: return
                        param.result = value
                        logProbe("custom_rom_system_property_boolean", key, "return_$value")
                    }
                }
            )
            XposedBridge.hookAllMethods(
                systemPropertiesClass,
                "getInt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val value = customRomSystemProperties[key]?.toIntOrNull() ?: return
                        param.result = value
                        logProbe("custom_rom_system_property_int", key, "return_$value")
                    }
                }
            )
            XposedBridge.hookAllMethods(
                systemPropertiesClass,
                "getLong",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val value = customRomSystemProperties[key]?.toLongOrNull() ?: return
                        param.result = value
                        logProbe("custom_rom_system_property_long", key, "return_$value")
                    }
                }
            )
            WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp custom ROM system property shield")
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Custom ROM system property shield unavailable: ${it.message}")
        }
        installGetpropProcessHook()
    }

    private fun installGetpropProcessHook() {
        runCatching {
            XposedBridge.hookAllMethods(
                ProcessBuilder::class.java,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val builder = param.thisObject as? ProcessBuilder ?: return
                        val command = runCatching { builder.command() }.getOrNull() ?: return
                        if (command.size < 2) return
                        val executable = normalizePath(command[0])
                        if (isWhichSuCommand(command)) {
                            param.result = fakeTextProcess("")
                            logProbe("root_which_su_process", command.joinToString(" "), "return_empty")
                            return
                        }
                        if (executable != "/system/bin/getprop" && executable != "getprop") return
                        val key = command[1]
                        val clean = customRomSystemProperties[key] ?: return
                        param.result = fakeTextProcess("$clean\n")
                        logProbe("custom_rom_getprop_process", key, "return_${clean.ifBlank { "empty" }}")
                    }
                }
            )
            WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp custom ROM getprop process shield")
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Custom ROM getprop process shield unavailable: ${it.message}")
        }
    }

    private fun installRootDebugEnvironmentHooks() {
        hookRootFileProbes()
        hookRuntimeWhichSuProbe()
        hookAdbSettingProbe()
        hookDebuggerProbe()
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp root, debug, ADB, and emulator environment shields")
    }

    private fun hookRootFileProbes() {
        listOf("exists", "canExecute", "canRead").forEach { methodName ->
            runCatching {
                XposedBridge.hookAllMethods(
                    File::class.java,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            val file = param.thisObject as? File ?: return
                            if (!isRootProbePath(file.path)) return
                            param.result = false
                            logProbe("root_file_probe", file.path, "${methodName}_false")
                        }
                    }
                )
            }
        }
    }

    private fun hookRuntimeWhichSuProbe() {
        runCatching {
            XposedBridge.hookAllMethods(
                Runtime::class.java,
                "exec",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val command = runtimeCommand(param.args.firstOrNull()) ?: return
                        if (!isWhichSuCommand(command)) return
                        param.result = fakeTextProcess("")
                        logProbe("root_which_su_runtime", command.joinToString(" "), "return_empty")
                    }
                }
            )
        }
    }

    private fun hookAdbSettingProbe() {
        runCatching {
            XposedBridge.hookAllMethods(
                Settings.Global::class.java,
                "getInt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (key != "adb_enabled") return
                        param.result = 0
                        logProbe("adb_setting_probe", key, "return_0")
                    }
                }
            )
        }
    }

    private fun hookDebuggerProbe() {
        runCatching {
            XposedBridge.hookAllMethods(
                android.os.Debug::class.java,
                "isDebuggerConnected",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        param.result = false
                        logProbe("debugger_probe", "Debug.isDebuggerConnected", "return_false")
                    }
                }
            )
        }
    }

    private fun fakeTextProcess(stdout: String): Process {
        return object : Process() {
            private val output = ByteArrayOutputStream()
            private val input = ByteArrayInputStream(stdout.toByteArray(Charsets.UTF_8))
            private val error = ByteArrayInputStream(ByteArray(0))

            override fun getOutputStream(): OutputStream = output
            override fun getInputStream(): InputStream = input
            override fun getErrorStream(): InputStream = error
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
        }
    }

    private fun installPlayStoreInstallerHooks() {
        runCatching {
            val appPackageManagerClass = Class.forName("android.app.ApplicationPackageManager")
            hookInstallerPackageName(appPackageManagerClass)
            hookInstallSourceInfo(appPackageManagerClass)
            hookHookPackageLookups(appPackageManagerClass)
            WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp Play Store installer spoof and hook package hiding")
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("PackageManager installer shield unavailable: ${it.message}")
        }
        hookInstallSourceInfoGetters()
    }

    private fun hookInstallerPackageName(packageManagerClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                packageManagerClass,
                "getInstallerPackageName",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val packageName = param.args.firstOrNull() as? String ?: return
                        if (packageName == WHATSAPP_PACKAGE) {
                            param.result = PLAY_STORE_PACKAGE
                            logProbe("installer_source", packageName, "getInstallerPackageName_vending")
                        }
                    }
                }
            )
        }
    }

    private fun hookInstallSourceInfo(packageManagerClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                packageManagerClass,
                "getInstallSourceInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val packageName = param.args.firstOrNull() as? String ?: return
                        val result = param.result ?: return
                        if (packageName == WHATSAPP_PACKAGE) {
                            whatsappInstallSourceObjects[result] = true
                            logProbe("installer_source", packageName, "track_install_source_info")
                        }
                    }
                }
            )
        }
    }

    private fun hookInstallSourceInfoGetters() {
        runCatching {
            val installSourceInfoClass = Class.forName("android.content.pm.InstallSourceInfo")
            listOf(
                "getInstallingPackageName",
                "getInitiatingPackageName",
                "getOriginatingPackageName",
                "getUpdateOwnerPackageName"
            ).forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(
                        installSourceInfoClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                if (whatsappInstallSourceObjects[param.thisObject] == true) {
                                    param.result = PLAY_STORE_PACKAGE
                                    logProbe("installer_source", methodName, "return_vending")
                                }
                            }
                        }
                    )
                }
            }
            runCatching {
                XposedBridge.hookAllMethods(
                    installSourceInfoClass,
                    "getPackageSource",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (whatsappInstallSourceObjects[param.thisObject] == true) {
                                param.result = PACKAGE_SOURCE_STORE
                                logProbe("installer_source", "getPackageSource", "return_store")
                            }
                        }
                    }
                )
            }
        }
    }

    private fun hookHookPackageLookups(packageManagerClass: Class<*>) {
        listOf("getPackageInfo", "getApplicationInfo", "getLaunchIntentForPackage").forEach { methodName ->
            runCatching {
                XposedBridge.hookAllMethods(
                    packageManagerClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            val packageName = param.args.firstOrNull() as? String ?: return
                            if (!isHookPackage(packageName)) return
                            if (methodName == "getLaunchIntentForPackage") {
                                param.result = null
                            } else {
                                param.throwable = PackageManager.NameNotFoundException(packageName)
                            }
                            logProbe("hook_package_lookup", packageName, "hidden")
                        }

                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (methodName == "getLaunchIntentForPackage") return
                            val packageName = param.args.firstOrNull() as? String ?: return
                            if (packageName != WHATSAPP_PACKAGE) return
                            if (sanitizeSelfPackageRecord(param.result)) {
                                logProbe("self_package_evidence", methodName, "sanitized")
                            }
                        }
                    }
                )
            }
        }
    }

    private fun hookHookPackageEnumerations(packageManagerClass: Class<*>) {
        listOf(
            "getInstalledPackages",
            "getInstalledApplications",
            "getPackagesForUid",
            "queryIntentActivities",
            "queryIntentServices",
            "queryBroadcastReceivers",
            "queryContentProviders"
        ).forEach { methodName ->
            runCatching {
                XposedBridge.hookAllMethods(
                    packageManagerClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            when (val result = param.result) {
                                is List<*> -> {
                                    val filtered = result.filterNot { isHookPackageRecord(it) }
                                    if (filtered.size != result.size) {
                                        param.result = filtered
                                        logProbe("hook_package_enumeration", methodName, "filtered_${result.size - filtered.size}")
                                    }
                                }
                                is Array<*> -> {
                                    if (result.all { it is String }) {
                                        val packages = result.filterIsInstance<String>()
                                        val filtered = packages.filterNot { isHookPackage(it) }
                                        if (filtered.size != packages.size) {
                                            param.result = filtered.toTypedArray()
                                            logProbe(
                                                "hook_package_enumeration",
                                                methodName,
                                                "filtered_${packages.size - filtered.size}"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun installHookInjectionShield(appClassLoader: ClassLoader) {
        installClassProbeHooks()
        installBootstrapStackTraceHooks()
        installProcReaderHooks()
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp hook/injection class, bootstrap-stack, and proc-map shield")
    }

    private fun installProcMapShield() {
        installProcReaderHooks()
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp hook/injection proc-map shield")
    }

    private fun installPassiveHookEvidenceShield() {
        installPassiveStackTraceHooks()
        installPatchPayloadEvidenceHooks()
        runCatching {
            val appPackageManagerClass = Class.forName("android.app.ApplicationPackageManager")
            hookHookPackageLookups(appPackageManagerClass)
            hookHookPackageEnumerations(appPackageManagerClass)
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Hook package lookup shield unavailable: ${it.message}")
        }
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp passive hook evidence shield")
    }

    private fun installPatchPayloadEvidenceHooks() {
        hookZipPayloadLookups()
        hookAssetPayloadLookups()
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp patch payload zip and asset evidence shield")
    }

    private fun hookZipPayloadLookups() {
        runCatching {
            XposedBridge.hookAllConstructors(
                java.util.zip.ZipFile::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = when (val source = param.args.firstOrNull()) {
                            is String -> source
                            is File -> source.absolutePath
                            else -> null
                        } ?: return
                        zipFilePaths[param.thisObject] = path
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                java.util.zip.ZipFile::class.java,
                "getEntry",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val zipFile = param.thisObject as? java.util.zip.ZipFile ?: return
                        if (!isSelfApkZip(zipFile)) return
                        val name = param.args.firstOrNull() as? String ?: return
                        if (normalizePath(name) == "classes.dex") {
                            val originalEntry = originalClassesDexEntry(zipFile) ?: return
                            param.result = originalEntry
                            logProbe("patch_payload_zip_entry", name, "serve_origin_metadata")
                            return
                        }
                        if (!isPatchPayloadEntry(name)) return
                        param.result = null
                        logProbe("patch_payload_zip_entry", name, "hidden")
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                java.util.zip.ZipFile::class.java,
                "entries",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val zipFile = param.thisObject as? java.util.zip.ZipFile ?: return
                        if (!isSelfApkZip(zipFile)) return
                        val entries = param.result as? java.util.Enumeration<*> ?: return
                        param.result = filteringZipEntries(entries, zipFile)
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                java.util.zip.ZipFile::class.java,
                "size",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        if (!isSelfApkZip(param.thisObject)) return
                        val originalSize = param.result as? Int ?: return
                        val hidden = countPatchPayloadEntries(param.thisObject as? java.util.zip.ZipFile ?: return)
                        if (hidden <= 0) return
                        param.result = (originalSize - hidden).coerceAtLeast(0)
                        logProbe("patch_payload_zip_size", "ZipFile.size", "subtract_$hidden")
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                java.util.zip.ZipFile::class.java,
                "getInputStream",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        if (!isSelfApkZip(param.thisObject)) return
                        val entry = param.args.firstOrNull() as? java.util.zip.ZipEntry ?: return
                        if (normalizePath(entry.name) != "classes.dex") return
                        val originalClasses = originalClassesDex(param.thisObject as? java.util.zip.ZipFile ?: return) ?: return
                        param.result = ByteArrayInputStream(originalClasses)
                        logProbe("patch_payload_zip_input", "classes.dex", "serve_origin_classes")
                    }
                }
            )
        }
    }

    private fun hookAssetPayloadLookups() {
        runCatching {
            XposedBridge.hookAllMethods(
                android.content.res.AssetManager::class.java,
                "list",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = param.args.firstOrNull() as? String ?: return
                        val result = param.result as? Array<*> ?: return
                        if (!isPatchPayloadAssetPath(path)) return
                        param.result = result.filterIsInstance<String>()
                            .filterNot { isPatchPayloadAssetPath("$path/$it") }
                            .toTypedArray()
                        logProbe("patch_payload_asset_list", path, "filtered")
                    }
                }
            )
        }
        listOf("open", "openFd").forEach { methodName ->
            runCatching {
                XposedBridge.hookAllMethods(
                    android.content.res.AssetManager::class.java,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            val path = param.args.firstOrNull() as? String ?: return
                            if (!isPatchPayloadAssetPath(path)) return
                            param.throwable = java.io.FileNotFoundException(path)
                            logProbe("patch_payload_asset_open", path, "hidden")
                        }
                    }
                )
            }
        }
    }

    private fun installHookClassPackageShield() {
        installClassProbeHooks()
        runCatching {
            val appPackageManagerClass = Class.forName("android.app.ApplicationPackageManager")
            hookHookPackageLookups(appPackageManagerClass)
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Hook package lookup shield unavailable: ${it.message}")
        }
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp hook/injection class and package probe shield")
    }

    private fun installFirebaseMessagingGuard(appClassLoader: ClassLoader) {
        runCatching {
            val registrationServiceClass = Class.forName(
                "com.whatsapp.infra.push.RegistrationIntentService",
                false,
                appClassLoader
            )
            XposedBridge.hookAllMethods(
                registrationServiceClass,
                "A0B",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val throwable = param.throwable ?: return
                        if (!isFirebaseMessagingMissing(throwable)) return
                        param.throwable = null
                        param.result = defaultReturnValue((param.method as java.lang.reflect.Method).returnType)
                        logProbe(
                            "firebase_messaging_guard",
                            "RegistrationIntentService.A0B",
                            "suppressed_missing_component"
                        )
                    }
                }
            )
            WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp push registration missing Firebase component guard")
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Firebase Messaging missing-component guard unavailable: ${it.message}")
        }
    }

    private fun installHookInjectionBootstrapGuard(appClassLoader: ClassLoader) {
        hookBootstrapDetectorClassNames.forEach { className ->
            val detectorClass = runCatching {
                Class.forName(className, false, appClassLoader)
            }.getOrNull() ?: return@forEach

            detectorClass.declaredMethods
                .map { it.name }
                .distinct()
                .forEach { methodName ->
                    runCatching {
                        XposedBridge.hookAllMethods(
                            detectorClass,
                            methodName,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam<*>) {
                                    val throwable = param.throwable ?: return
                                    if (!isHookBootstrapThrowable(throwable)) return
                                    if (!isHookBootstrapStack(Thread.currentThread().stackTrace)) return
                                    val returnType = (param.method as java.lang.reflect.Method).returnType
                                    param.throwable = null
                                    param.result = hookBootstrapSafeResult(returnType)
                                    logProbe(
                                        "hook_bootstrap_detector",
                                        "$className.$methodName:${returnType.name}",
                                        "suppressed_gx9"
                                    )
                                }
                            }
                        )
                    }
                }
        }
        WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp hook/injection bootstrap GX9 guard")
    }

    private fun installClassProbeHooks() {
        runCatching {
            XposedBridge.hookAllMethods(
                Class::class.java,
                "forName",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val className = param.args.firstOrNull() as? String ?: return
                        if (shouldHideClassProbe(className)) {
                            param.throwable = ClassNotFoundException(className)
                            logProbe("hook_class_probe", className, "class_not_found")
                        }
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                ClassLoader::class.java,
                "loadClass",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val className = param.args.firstOrNull() as? String ?: return
                        if (shouldHideClassProbe(className)) {
                            param.throwable = ClassNotFoundException(className)
                            logProbe("hook_classloader_probe", className, "class_not_found")
                        }
                    }
                }
            )
        }
    }

    private fun installStackTraceHooks() {
        runCatching {
            XposedBridge.hookAllMethods(
                Throwable::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Thread::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
    }

    private fun installPassiveStackTraceHooks() {
        runCatching {
            XposedBridge.hookAllMethods(
                Throwable::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        if (!shouldFilterPassiveHookStack(stack, Thread.currentThread().stackTrace)) return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Thread::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        if (!shouldFilterPassiveHookStack(stack, stack)) return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Thread::class.java,
                "getAllStackTraces",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val result = param.result as? Map<*, *> ?: return
                        val callerStack = runWithCallerGuard { Thread.currentThread().stackTrace }
                        var filteredCount = 0
                        val filteredStacks = LinkedHashMap<Thread, Array<StackTraceElement>>(result.size)
                        result.forEach { (thread, stack) ->
                            if (thread is Thread && stack is Array<*> && stack.all { it is StackTraceElement }) {
                                @Suppress("UNCHECKED_CAST")
                                val stackTrace = stack as Array<StackTraceElement>
                                val cleanStack = if (shouldFilterPassiveHookStack(stackTrace, callerStack)) {
                                    filteredCount++
                                    filterHookStackTrace(stackTrace)
                                } else {
                                    stackTrace
                                }
                                filteredStacks[thread] = cleanStack
                            }
                        }
                        if (filteredCount > 0 && filteredStacks.isNotEmpty()) {
                            param.result = filteredStacks
                            logProbe("hook_all_stack_traces", "Thread.getAllStackTraces", "filtered_$filteredCount")
                        }
                    }
                }
            )
        }
    }

    private fun installProcReaderHooks() {
        hookPathConstructors(FileInputStream::class.java)
        hookPathConstructors(FileReader::class.java)
        hookPathConstructors(RandomAccessFile::class.java)
        hookInputStreamReaderPathPropagation()
        hookBufferedReaderPathPropagation()
        hookBufferedReaderLineSanitizer()
        hookRandomAccessFileLineSanitizer()
    }

    private fun hookPathConstructors(clazz: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                clazz,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = pathFromArg(param.args.firstOrNull()) ?: return
                        if (!isProcInspectionPath(path)) return
                        when (val instance = param.thisObject) {
                            is Reader -> procPathReaders[instance] = path
                            else -> procPathStreams[instance] = path
                        }
                        logProbe("hook_proc_probe", path, "track_for_line_filter")
                    }
                }
            )
        }
    }

    private fun hookInputStreamReaderPathPropagation() {
        runCatching {
            XposedBridge.hookAllConstructors(
                InputStreamReader::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = procPathStreams[param.args.firstOrNull()] ?: return
                        procPathReaders[param.thisObject] = path
                    }
                }
            )
        }
    }

    private fun hookBufferedReaderPathPropagation() {
        runCatching {
            XposedBridge.hookAllConstructors(
                BufferedReader::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val reader = param.args.firstOrNull() ?: return
                        val path = procPathReaders[reader] ?: return
                        procPathReaders[param.thisObject] = path
                    }
                }
            )
        }
    }

    private fun hookBufferedReaderLineSanitizer() {
        runCatching {
            XposedBridge.hookAllMethods(
                BufferedReader::class.java,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = procPathReaders[param.thisObject] ?: return
                        val line = param.result as? String ?: return
                        if (isHookProcLine(line)) {
                            param.result = ""
                            logProbe("hook_proc_line", path, "blank_hook_line")
                        }
                    }
                }
            )
        }
    }

    private fun hookRandomAccessFileLineSanitizer() {
        runCatching {
            XposedBridge.hookAllMethods(
                RandomAccessFile::class.java,
                "readLine",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val path = procPathStreams[param.thisObject] ?: return
                        val line = param.result as? String ?: return
                        if (isHookProcLine(line)) {
                            param.result = ""
                            logProbe("hook_proc_line", path, "blank_hook_line")
                        }
                    }
                }
            )
        }
    }

    private fun installRegistrationSignalHooks() {
        runCatching {
            XposedBridge.hookAllMethods(
                JSONObject::class.java,
                "put",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val normalized = key.lowercase(Locale.US)
                        if (normalized in installerIntegerKeys || installerIntegerKeys.any { normalized.endsWith("_$it") }) {
                            param.args[1] = PACKAGE_SOURCE_STORE
                            logProbe("installer_json_signal", key, "force_store_source")
                            return
                        }
                        if (normalized in installerStringKeys || installerStringKeys.any { normalized.endsWith("_$it") }) {
                            param.args[1] = PLAY_STORE_PACKAGE
                            logProbe("installer_json_signal", key, "force_vending")
                            return
                        }
                        if (normalized in installerBooleanKeys || installerBooleanKeys.any { normalized.endsWith("_$it") }) {
                            param.args[1] = true
                            logProbe("installer_json_signal", key, "force_true")
                            return
                        }
                        if (detectorLogKeyFragments.any { it in normalized }) {
                            val value = param.args.getOrNull(1)
                            logProbe("detection_json_signal", key, value?.toString()?.take(220).orEmpty())
                        }
                    }
                }
            )
            WhatsAppDetectionEventLogger.lifecycle("Hooked WhatsApp detection and installer JSON signal logger")
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("WhatsApp detection JSON logger unavailable: ${it.message}")
        }
    }

    private fun shouldHideClassProbe(className: String): Boolean {
        if (callerCheck.get() == true || isInternalPurrfectCall()) return false
        return runWithCallerGuard {
            isHookClassName(className)
        }
    }

    private fun filterHookStackTrace(stack: Array<StackTraceElement>): Array<StackTraceElement> {
        val filtered = stack.filterNot { frame ->
            val className = frame.className
            isHookClassName(className) || isHookMethodFrame(className, frame.methodName)
        }
        if (filtered.size != stack.size) {
            logProbe("hook_stack_trace", "StackTraceElement[]", "filtered_${stack.size - filtered.size}")
        }
        return filtered.toTypedArray()
    }

    private fun shouldFilterPassiveHookStack(
        stack: Array<StackTraceElement>,
        callerStack: Array<StackTraceElement>
    ): Boolean {
        if (!stack.any { isHookClassName(it.className) || isHookMethodFrame(it.className, it.methodName) }) return false
        if (isWhatsAppCriticalBootstrapStack(stack) || isWhatsAppCriticalBootstrapStack(callerStack)) return false
        return true
    }

    private fun isHookPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase(Locale.US)
        return normalized in hookPackageNames
    }

    private fun isHookPackageRecord(record: Any?): Boolean {
        record ?: return false
        return listOf(
            "packageName",
            "sourceDir",
            "publicSourceDir",
            "nativeLibraryDir"
        ).any { fieldName ->
            runCatching {
                val field = record.javaClass.getField(fieldName)
                val value = field.get(record) as? String ?: return@runCatching false
                isHookPackage(value) || isHookPath(value)
            }.getOrDefault(false)
        } || listOf("applicationInfo", "activityInfo", "serviceInfo", "providerInfo").any { fieldName ->
            runCatching {
                val field = record.javaClass.getField(fieldName)
                isHookPackageRecord(field.get(record))
            }.getOrDefault(false)
        }
    }

    private fun sanitizeSelfPackageRecord(record: Any?): Boolean {
        record ?: return false
        var changed = false
        changed = scrubApplicationInfoEvidence(record) || changed
        listOf("applicationInfo", "activityInfo", "serviceInfo", "providerInfo").forEach { fieldName ->
            changed = runCatching {
                val field = record.javaClass.getField(fieldName)
                sanitizeSelfPackageRecord(field.get(record))
            }.getOrDefault(false) || changed
        }
        return changed
    }

    private fun scrubApplicationInfoEvidence(record: Any): Boolean {
        var changed = false
        changed = runCatching {
            val packageField = record.javaClass.getField("packageName")
            val packageName = packageField.get(record) as? String
            if (packageName != WHATSAPP_PACKAGE) return@runCatching false
            var localChanged = false
            runCatching {
                val appComponentFactoryField = record.javaClass.getField("appComponentFactory")
                val value = appComponentFactoryField.get(record) as? String
                if (value != null && isHookPath(value)) {
                    appComponentFactoryField.set(record, "androidx.core.app.CoreComponentFactory")
                    localChanged = true
                }
            }
            runCatching {
                val metaDataField = record.javaClass.getField("metaData")
                val metaData = metaDataField.get(record) as? android.os.Bundle
                val suspiciousKeys = metaData?.keySet()
                    ?.filter { key ->
                        isHookPath(key) || isHookPath(metaData.get(key)?.toString().orEmpty())
                    }
                    .orEmpty()
                suspiciousKeys.forEach { metaData?.remove(it) }
                localChanged = localChanged || suspiciousKeys.isNotEmpty()
            }
            localChanged
        }.getOrDefault(false) || changed
        return changed
    }

    private fun filteringZipEntries(
        entries: java.util.Enumeration<*>,
        zipFile: java.util.zip.ZipFile
    ): java.util.Enumeration<java.util.zip.ZipEntry> {
        val originalClassesEntry = originalClassesDexEntry(zipFile)
        val visible = entries.asSequence()
            .filterIsInstance<java.util.zip.ZipEntry>()
            .filterNot { isPatchPayloadEntry(it.name) }
            .map { entry ->
                if (normalizePath(entry.name) == "classes.dex" && originalClassesEntry != null) {
                    originalClassesEntry
                } else {
                    entry
                }
            }
            .toList()
        return Collections.enumeration(visible)
    }

    private fun countPatchPayloadEntries(zipFile: java.util.zip.ZipFile): Int {
        return runWithCallerGuard {
            zipFile.entries().asSequence()
                .filterIsInstance<java.util.zip.ZipEntry>()
                .count { isPatchPayloadEntry(it.name) }
        }
    }

    private fun originalClassesDex(zipFile: java.util.zip.ZipFile): ByteArray? {
        return originalClassesDexSnapshot(zipFile)?.bytes
    }

    private fun originalClassesDexEntry(zipFile: java.util.zip.ZipFile): java.util.zip.ZipEntry? {
        return originalClassesDexSnapshot(zipFile)?.let { cloneZipEntry(it.entry) }
    }

    private fun originalClassesDexSnapshot(zipFile: java.util.zip.ZipFile): OriginalClassesDexSnapshot? {
        val path = selfApkZipPath(zipFile) ?: return null
        if (originalClassesDexByZipPath.containsKey(path)) {
            return originalClassesDexByZipPath[path]
        }
        val snapshot = runWithCallerGuard {
            runCatching {
                val originEntry = zipFile.getEntry("assets/lspatch/origin.apk") ?: return@runCatching null
                zipFile.getInputStream(originEntry).use { originInput ->
                    java.util.zip.ZipInputStream(originInput).use { originZip ->
                        var entry = originZip.nextEntry
                        while (entry != null) {
                            if (entry.name == "classes.dex") {
                                val bytes = originZip.readBytes()
                                return@runCatching OriginalClassesDexSnapshot(bytes, originClassesZipEntry(entry, bytes))
                            }
                            entry = originZip.nextEntry
                        }
                        null
                    }
                }
            }.getOrNull()
        }
        originalClassesDexByZipPath[path] = snapshot
        return snapshot
    }

    private fun originClassesZipEntry(
        source: java.util.zip.ZipEntry,
        bytes: ByteArray
    ): java.util.zip.ZipEntry {
        val entry = java.util.zip.ZipEntry("classes.dex")
        entry.size = bytes.size.toLong()
        entry.crc = crc32(bytes)
        if (source.time >= 0) entry.time = source.time
        source.comment?.let { entry.comment = it }
        source.extra?.let { entry.extra = it }
        return entry
    }

    private fun cloneZipEntry(source: java.util.zip.ZipEntry): java.util.zip.ZipEntry {
        return java.util.zip.ZipEntry(source)
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun isSelfApkZip(zipFile: Any?): Boolean {
        return selfApkZipPath(zipFile) != null
    }

    private fun selfApkZipPath(zipFile: Any?): String? {
        zipFile ?: return null
        val path = zipFilePaths[zipFile] ?: runCatching {
            (zipFile as? java.util.zip.ZipFile)?.name
        }.getOrNull() ?: return null
        val normalized = normalizePath(path)
        val isSelfApk = normalized.endsWith("/base.apk") ||
            normalized.endsWith(".apk") && "/com.whatsapp-" in normalized ||
            normalized.contains("/data/app/") && normalized.contains("/com.whatsapp")
        return if (isSelfApk) normalized else null
    }

    private fun isPatchPayloadEntry(name: String): Boolean {
        val normalized = normalizePath(name).trimStart('/')
        return normalized.startsWith("assets/lspatch/") ||
            normalized == "assets/lspatch" ||
            normalized.contains("/lspatch/") ||
            normalized.contains("liblspatch.so") ||
            normalized.endsWith("/${BuildConfig.APPLICATION_ID}.apk") ||
            isHookPath(normalized)
    }

    private fun isPatchPayloadAssetPath(path: String): Boolean {
        val normalized = normalizePath(path).trim('/')
        return normalized == "lspatch" ||
            normalized.startsWith("lspatch/") ||
            normalized.contains("/lspatch/") ||
            normalized.contains("liblspatch.so") ||
            normalized.contains(BuildConfig.APPLICATION_ID.lowercase(Locale.US))
    }

    private fun isHookPath(value: String): Boolean {
        val normalized = value.lowercase(Locale.US)
        return hookPackageNames.any { it in normalized } ||
            hookClassFragments.any { fragment -> fragment != "hookbridge" && fragment in normalized }
    }

    private fun isHookClassName(className: String): Boolean {
        if (className in hookProbeClassNames) return true
        return hookClassPrefixes.any { className.startsWith(it) } ||
            className == "LSPHooker_" ||
            className.startsWith("LSPHooker_")
    }

    private fun isHookMethodFrame(className: String, methodName: String): Boolean {
        val normalizedClass = className.lowercase(Locale.US)
        val normalizedMethod = methodName.lowercase(Locale.US)
        return "nativehooker" in normalizedClass ||
            "hookbridge" in normalizedClass ||
            "lsp_hook" in normalizedClass ||
            "lsp" in normalizedClass && "hook" in normalizedMethod
    }

    private fun isFirebaseMessagingMissing(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        repeat(8) {
            if (current?.message?.contains("Firebase Messaging component is not present") == true) return true
            current = current?.cause
        }
        return false
    }

    private fun isWhatsAppPushRegistrationStack(stack: Array<StackTraceElement>): Boolean {
        return stack.any { frame ->
            frame.className == "com.whatsapp.infra.push.RegistrationIntentService"
        }
    }

    private fun isProcInspectionPath(path: String): Boolean {
        val normalized = normalizePath(path)
        return procInspectionPaths.any { normalized == it || normalized.endsWith(it) } ||
            (normalized.startsWith("/proc/self/task/") && normalized.endsWith("/maps"))
    }

    private fun isHookProcLine(line: String): Boolean {
        val normalized = line.lowercase(Locale.US)
        return hookClassFragments.any { it in normalized } ||
            "de.robv.android.xposed" in normalized ||
            "org.lsposed" in normalized ||
            "io.github.lsposed" in normalized ||
            "liblspd" in normalized ||
            "libxposed" in normalized ||
            "libsubstrate" in normalized ||
            "rezygisk" in normalized ||
            "zygisk" in normalized && "lsposed" in normalized
    }

    private fun isHookBootstrapThrowable(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        repeat(8) {
            val className = current?.javaClass?.name.orEmpty()
            if (className == "X.GX9" || className.endsWith(".GX9")) return true
            if (className == "java.lang.ExceptionInInitializerError") return true
            if (current is NullPointerException && isBootstrapDetectorNullPointer(current)) return true
            current = current?.cause
        }
        return false
    }

    private fun isBootstrapDetectorNullPointer(throwable: NullPointerException): Boolean {
        val message = throwable.message.orEmpty()
        return "X.0gC.A00" in message ||
            "X.C11430gC.A00" in message ||
            "X.0gD.generatePrivateKey" in message ||
            "X.C11440gD.generatePrivateKey" in message ||
            ("generatePrivateKey" in message && "null object reference" in message) ||
            ("X.0gC" in message && "null object reference" in message)
    }

    private fun isHookBootstrapStack(stack: Array<StackTraceElement>): Boolean {
        return stack.any { frame ->
            val className = frame.className
            (className == "X.0gB" || className == "X.C11420gB") && frame.methodName == "<clinit>"
        } || stack.any { frame ->
            val className = frame.className
            className == "X.0dh" ||
                className == "X.0gT" ||
                className == "X.0eJ" ||
                className == "X.1SC" ||
                className == "X.1SK" ||
                className == "X.0aL" ||
                className == "X.0z9" ||
                className == "X.1Rw" ||
                className == "X.00C" ||
                className == "android.os.HandlerThread" ||
                className == "com.whatsapp.Main" ||
                className == "com.whatsapp.invite.ui.ReferralInviteManager"
        }
    }

    private fun isWhatsAppCriticalBootstrapStack(stack: Array<StackTraceElement>): Boolean {
        return stack.any { frame ->
            when (frame.className) {
                "X.0gB",
                "X.C11420gB",
                "X.0gC",
                "X.C11430gC",
                "X.0dh",
                "X.0gT",
                "X.0eJ",
                "X.1Rw",
                "X.00C",
                "X.07j",
                "X.0Af",
                "X.1nN",
                "X.00l",
                "X.06y",
                "X.06t",
                "X.0Wl",
                "X.0Eo",
                "X.0Dg",
                "X.0Er",
                "X.1SC",
                "X.1SK",
                "X.1ST",
                "X.1SU",
                "X.0aL",
                "X.0z9" -> true
                else -> false
            }
        }
    }

    private fun hookBootstrapSafeResult(returnType: Class<*>): Any? {
        return hookBootstrapSafeResult(returnType, mutableSetOf())
    }

    private fun hookBootstrapSafeResult(returnType: Class<*>, seen: MutableSet<Class<*>>): Any? {
        return when {
            returnType == Void.TYPE -> null
            returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java -> false
            returnType == Byte::class.javaPrimitiveType || returnType == java.lang.Byte::class.java -> 0.toByte()
            returnType == Short::class.javaPrimitiveType || returnType == java.lang.Short::class.java -> 0.toShort()
            returnType == Int::class.javaPrimitiveType || returnType == java.lang.Integer::class.java -> 0
            returnType == Long::class.javaPrimitiveType || returnType == java.lang.Long::class.java -> 0L
            returnType == Float::class.javaPrimitiveType || returnType == java.lang.Float::class.java -> 0f
            returnType == Double::class.javaPrimitiveType || returnType == java.lang.Double::class.java -> 0.0
            returnType == Char::class.javaPrimitiveType || returnType == Character::class.java -> 0.toChar()
            returnType == String::class.java -> ""
            returnType == ByteArray::class.java -> randomBytes(X25519_KEY_SIZE)
            returnType.isArray -> java.lang.reflect.Array.newInstance(returnType.componentType ?: Any::class.java, 0)
            returnType.isInterface -> bootstrapSafeInterface(returnType)
            isBootstrapCarrierType(returnType) -> bootstrapSafeObject(returnType, seen)
            else -> null
        }
    }

    private fun bootstrapSafeInterface(type: Class<*>): Any? {
        if (!isBootstrapInterfaceType(type)) return null
        bootstrapSafeObjects[type]?.let { return it }
        val delegate = instantiateCurve25519Delegate(type.classLoader)
        val instance = instantiateCurve25519Provider(type) ?: runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                bootstrapInterfaceResult(proxy, method, args, delegate)
            }
        }.getOrNull()
        if (instance != null) {
            bootstrapSafeObjects[type] = instance
            logProbe("hook_bootstrap_safe_interface", type.name, "created")
        }
        return instance
    }

    private fun bootstrapSafeObject(type: Class<*>, seen: MutableSet<Class<*>>): Any? {
        if (!isBootstrapObjectType(type) || !seen.add(type)) return null
        bootstrapSafeObjects[type]?.let { return it }

        val instance = runCatching {
            type.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }?.let { constructor ->
                constructor.isAccessible = true
                constructor.newInstance()
            }
        }.getOrNull() ?: unsafeAllocate(type)

        if (instance != null) {
            bootstrapSafeObjects[type] = instance
            hydrateBootstrapObject(instance, seen)
        }
        seen.remove(type)
        return instance
    }

    private fun hydrateBootstrapObject(instance: Any, seen: MutableSet<Class<*>>) {
        var current: Class<*>? = instance.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                val fieldType = field.type
                if (!shouldHydrateBootstrapField(current.name, field.name, fieldType)) return@forEach
                val value = hookBootstrapSafeResult(fieldType, seen).takeIf {
                    it != null || !fieldType.isPrimitive
                } ?: return@forEach
                runCatching {
                    field.isAccessible = true
                    if (field.get(instance) == null || fieldType.isPrimitive) {
                        field.set(instance, value)
                    }
                }
            }
            current = current.superclass
        }
    }

    private fun isBootstrapObjectType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.isInterface || type.isAnnotation || type.isEnum) return false
        if (Modifier.isAbstract(type.modifiers)) return false
        val name = type.name
        return name.startsWith("X.") || name.startsWith("com.whatsapp.")
    }

    private fun isBootstrapInterfaceType(type: Class<*>): Boolean {
        val name = type.name
        return name.startsWith("X.") || name.startsWith("com.whatsapp.") ||
            type.declaredMethods.any { method ->
                method.name == "generatePrivateKey" ||
                    method.name == "generatePublicKey" ||
                    method.name == "calculateAgreement"
        }
    }

    private fun installBootstrapStackTraceHooks() {
        runCatching {
            XposedBridge.hookAllMethods(
                Throwable::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        if (!isHookBootstrapStack(Thread.currentThread().stackTrace)) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                Thread::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (callerCheck.get() == true) return
                        val stack = param.result as? Array<StackTraceElement> ?: return
                        if (!isHookBootstrapStack(stack)) return
                        param.result = filterHookStackTrace(stack)
                    }
                }
            )
        }
    }

    private fun shouldHydrateBootstrapField(ownerName: String, fieldName: String, fieldType: Class<*>): Boolean {
        if (!isBootstrapCarrierName(ownerName)) return false
        if ((ownerName == "X.1SC" || ownerName == "X.C63491SC") && fieldName == "A02") {
            return isBootstrapCarrierType(fieldType)
        }
        return fieldType.isPrimitive ||
            fieldType == String::class.java ||
            fieldType == ByteArray::class.java ||
            fieldType.isArray ||
            isBootstrapInterfaceType(fieldType) ||
            isBootstrapCarrierType(fieldType)
    }

    private fun isBootstrapCarrierType(type: Class<*>): Boolean {
        return isBootstrapCarrierName(type.name)
    }

    private fun isBootstrapCarrierName(name: String): Boolean {
        return name == "X.1SC" ||
            name == "X.C63491SC" ||
            name == "X.1SA" ||
            name == "X.C63471SA"
    }

    private fun instantiateCurve25519Delegate(classLoader: ClassLoader?): Any? {
        return listOf(
            "org.whispersystems.curve25519.OpportunisticCurve25519Provider",
            "org.whispersystems.curve25519.JavaCurve25519Provider",
            "org.whispersystems.curve25519.NativeCurve25519Provider"
        ).firstNotNullOfOrNull { className ->
            runCatching {
                Class.forName(className, false, classLoader)
                    .getDeclaredConstructor()
                    .newInstance()
            }.getOrNull()
        }
    }

    private fun instantiateCurve25519Provider(type: Class<*>): Any? {
        return listOf(
            "org.whispersystems.curve25519.OpportunisticCurve25519Provider",
            "org.whispersystems.curve25519.JavaCurve25519Provider",
            "org.whispersystems.curve25519.NativeCurve25519Provider"
        ).firstNotNullOfOrNull { className ->
            runCatching {
                val providerClass = Class.forName(className, false, type.classLoader)
                val provider = providerClass.getDeclaredConstructor().newInstance()
                provider.takeIf { type.isInstance(it) }
            }.getOrNull()
        }
    }

    private fun bootstrapInterfaceResult(
        proxy: Any,
        method: java.lang.reflect.Method,
        args: Array<Any?>?,
        delegate: Any?
    ): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "toString" -> "WhatsAppBootstrapSafeProxy(${proxy.javaClass.interfaces.firstOrNull()?.name.orEmpty()})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }

        invokeCurve25519Delegate(delegate, method, args)?.let { return it }

        return when (method.name) {
            "generatePrivateKey" -> randomCurve25519PrivateKey()
            "generatePublicKey" -> x25519PublicKey(args?.firstByteArray()) ?: randomBytes(X25519_KEY_SIZE)
            "calculateAgreement" -> x25519Agreement(args) ?: randomBytes(X25519_KEY_SIZE)
            "getRandom" -> randomBytes((args?.firstOrNull() as? Int)?.coerceAtLeast(0) ?: 32)
            "isTorsionFree" -> true
            else -> hookBootstrapSafeResult(method.returnType)
        }
    }

    private fun invokeCurve25519Delegate(
        delegate: Any?,
        requestedMethod: java.lang.reflect.Method,
        args: Array<Any?>?
    ): Any? {
        delegate ?: return null
        val method = delegate.javaClass.methods.firstOrNull { candidate ->
            candidate.name == requestedMethod.name &&
                candidate.parameterTypes.size == (args?.size ?: 0) &&
                candidate.parameterTypes.zip(args.orEmpty()).all { (type, arg) ->
                    arg == null || boxedType(type).isInstance(arg)
                }
        } ?: return null

        return runCatching {
            method.invoke(delegate, *(args ?: emptyArray()))
        }.getOrNull()
    }

    private fun boxedType(type: Class<*>): Class<*> {
        return when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
    }

    private fun randomCurve25519PrivateKey(): ByteArray {
        val bytes = randomBytes(32)
        bytes[0] = (bytes[0].toInt() and 248).toByte()
        bytes[31] = ((bytes[31].toInt() and 127) or 64).toByte()
        return bytes
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        bootstrapRandom.nextBytes(bytes)
        return bytes
    }

    private fun x25519PublicKey(privateKey: ByteArray?): ByteArray? {
        return x25519(privateKey ?: return null, X25519_BASE_POINT)
    }

    private fun x25519Agreement(args: Array<Any?>?): ByteArray? {
        val publicKey = args?.firstByteArray() ?: return null
        val privateKey = args.drop(1).firstByteArray() ?: return null
        return x25519(privateKey, publicKey)
    }

    private fun Array<Any?>.firstByteArray(): ByteArray? {
        return firstOrNull { it is ByteArray } as? ByteArray
    }

    private fun Iterable<Any?>.firstByteArray(): ByteArray? {
        return firstOrNull { it is ByteArray } as? ByteArray
    }

    private fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
        if (privateKey.size < X25519_KEY_SIZE || publicKey.size < X25519_KEY_SIZE) return null

        val scalar = privateKey.copyOf(X25519_KEY_SIZE)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()

        val k = littleEndianToBigInteger(scalar)
        val u = littleEndianToBigInteger(publicKey.copyOf(X25519_KEY_SIZE)).mod(X25519_P)
        return bigIntegerToLittleEndian(montgomeryLadder(k, u))
    }

    private fun montgomeryLadder(scalar: BigInteger, u: BigInteger): BigInteger {
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = if (scalar.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                val oldX2 = x2
                x2 = x3
                x3 = oldX2
                val oldZ2 = z2
                z2 = z3
                z3 = oldZ2
            }
            swap = bit

            val a = (x2 + z2).mod(X25519_P)
            val aa = a.modPow(BigInteger.TWO, X25519_P)
            val b = (x2 - z2).mod(X25519_P)
            val bb = b.modPow(BigInteger.TWO, X25519_P)
            val e = (aa - bb).mod(X25519_P)
            val c = (x3 + z3).mod(X25519_P)
            val d = (x3 - z3).mod(X25519_P)
            val da = (d * a).mod(X25519_P)
            val cb = (c * b).mod(X25519_P)

            x3 = (da + cb).modPow(BigInteger.TWO, X25519_P)
            z3 = (u * (da - cb).modPow(BigInteger.TWO, X25519_P)).mod(X25519_P)
            x2 = (aa * bb).mod(X25519_P)
            z2 = (e * (aa + X25519_A24 * e).mod(X25519_P)).mod(X25519_P)
        }

        if (swap == 1) {
            val oldX2 = x2
            x2 = x3
            x3 = oldX2
            val oldZ2 = z2
            z2 = z3
            z3 = oldZ2
        }

        return (x2 * z2.modInverse(X25519_P)).mod(X25519_P)
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        return BigInteger(1, bytes.reversedArray())
    }

    private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.mod(X25519_P).toByteArray()
        val out = ByteArray(X25519_KEY_SIZE)
        bigEndian.indices.forEach { index ->
            val sourceIndex = bigEndian.size - 1 - index
            if (index < out.size && sourceIndex >= 0) {
                out[index] = bigEndian[sourceIndex]
            }
        }
        return out
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }
    }

    private fun unsafeAllocate(type: Class<*>): Any? {
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
        }.getOrNull()
    }

    private fun pathFromArg(arg: Any?): String? {
        return when (arg) {
            is File -> arg.path
            is String -> arg
            else -> null
        }
    }

    private fun runtimeCommand(arg: Any?): List<String>? {
        return when (arg) {
            is String -> arg.split(' ').filter { it.isNotBlank() }
            is Array<*> -> arg.filterIsInstance<String>()
            else -> null
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').lowercase(Locale.US)
    }

    private fun isRootProbePath(path: String): Boolean {
        val normalized = normalizePath(path)
        return rootProbePaths.any { normalizePath(it) == normalized }
    }

    private fun isWhichSuCommand(command: List<String>): Boolean {
        if (command.size < 2) return false
        val executable = normalizePath(command[0])
        return (executable == "/system/xbin/which" || executable == "which") && command[1] == "su"
    }

    private fun setStaticStringField(clazz: Class<*>, fieldName: String, value: String) {
        runCatching {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, value)
        }.onFailure {
            WhatsAppDetectionEventLogger.lifecycle("Unable to set ${clazz.name}.$fieldName: ${it.message}")
        }
    }

    private fun safeBuildFingerprint(): String {
        val current = Build.FINGERPRINT.orEmpty()
        if (looksStockBuildValue(current)) return current
        val brand = safeBuildPart(Build.BRAND, "google")
        val product = safeBuildPart(Build.PRODUCT, "oriole")
        val device = safeBuildPart(Build.DEVICE, product)
        val release = safeBuildPart(Build.VERSION.RELEASE, "16")
        val id = safeBuildPart(Build.ID, "BP1A.260505.005")
        return "$brand/$product/$device:$release/$id/${safeBuildIncremental()}:user/release-keys"
    }

    private fun safeBuildDisplay(): String {
        val display = Build.DISPLAY.orEmpty()
        return if (looksStockBuildValue(display)) display else safeBuildIncremental()
    }

    private fun safeBuildIncremental(): String {
        val incremental = Build.VERSION.INCREMENTAL.orEmpty()
        return if (looksStockBuildValue(incremental)) incremental else "14256728"
    }

    private fun safeBuildPart(value: String?, fallback: String): String {
        val clean = value?.takeIf { looksStockBuildValue(it) } ?: fallback
        return clean.replace(' ', '_')
    }

    private fun looksStockBuildValue(value: String): Boolean {
        val normalized = value.lowercase(Locale.US)
        if (normalized.isBlank()) return false
        return listOf(
            "lineage",
            "cyanogenmod",
            "crdroid",
            "evolution",
            "pixelos",
            "grapheneos",
            "calyxos",
            "test-keys",
            "dev-keys",
            "userdebug",
            "eng",
            "generic",
            "unknown",
            "goldfish",
            "ranchu",
            "google_sdk",
            "emulator",
            "android sdk built for x86",
            "genymotion",
            "sdk_google",
            "sdk_gphone",
            "vbox86",
            "simulator"
        ).none { it in normalized }
    }

    private fun isInternalPurrfectCall(): Boolean {
        return runWithCallerGuard {
            Thread.currentThread().stackTrace.any { it.className.startsWith("me.eternal.purrfect") }
        }
    }

    private inline fun <T> runWithCallerGuard(block: () -> T): T {
        val previous = callerCheck.get()
        callerCheck.set(true)
        return try {
            block()
        } finally {
            callerCheck.set(previous)
        }
    }

    private fun logProbe(type: String, detail: String, action: String) {
        WhatsAppDetectionEventLogger.probe(type, detail, action)
    }
}
