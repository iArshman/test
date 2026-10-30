package me.eternal.purrfect.core.instagram

import android.content.Context
import android.content.Intent
import android.os.Build
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.BuildConfig
import java.lang.reflect.Method

internal object InstagramDexKitCache {
    private const val PREF_NAME = "purrfect_insta_dexkit_cache"
    private const val KEY_VERSION = "_v"
    private const val KEY_SCHEMA = "_schema"
    private const val SCHEMA_VERSION = "4"
    private const val SEP = '\u0000'

    private var prefs: android.content.SharedPreferences? = null
    private var localPrefs: android.content.SharedPreferences? = null
    @Volatile private var cacheValid = false
    @Volatile private var instagramVersion = ""

    fun init(context: Context) {
        val version = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }.toString()
        }.getOrDefault("")
        instagramVersion = version
        localPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val modulePrefs = tryLoadModulePrefs()
        if (modulePrefs != null) {
            val moduleStored = modulePrefs.getString(KEY_VERSION, "").orEmpty()
            val moduleSchema = modulePrefs.getString(KEY_SCHEMA, "").orEmpty()
            if (moduleStored == version && moduleSchema == SCHEMA_VERSION && version.isNotEmpty()) {
                prefs = modulePrefs
                cacheValid = true
                log("DexKit cache valid via module prefs for Instagram $version")
                return
            }
            log("Module prefs version mismatch: $moduleStored/$moduleSchema vs $version/$SCHEMA_VERSION")
        } else {
            log("DexKit cache using local prefs (cross-process unavailable)")
        }

        prefs = localPrefs
        val stored = prefs?.getString(KEY_VERSION, "").orEmpty()
        val storedSchema = prefs?.getString(KEY_SCHEMA, "").orEmpty()
        if (stored == version && storedSchema == SCHEMA_VERSION && version.isNotEmpty()) {
            cacheValid = true
            log("Cache valid for Instagram $version")
        } else {
            cacheValid = false
            prefs?.edit()?.clear()?.putString(KEY_VERSION, version)?.putString(KEY_SCHEMA, SCHEMA_VERSION)?.apply()
            log("Version $stored/$storedSchema -> $version/$SCHEMA_VERSION, cache cleared")
            requestBackgroundScan(context, "cache invalid")
        }
    }

    private fun tryLoadModulePrefs(): android.content.SharedPreferences? {
        return runCatching {
            val prefsClass = Class.forName("de.robv.android.xposed.XSharedPreferences")
            val prefs = prefsClass.getConstructor(String::class.java, String::class.java)
                .newInstance(BuildConfig.APPLICATION_ID, PREF_NAME)
            runCatching { prefsClass.getMethod("makeWorldReadable").invoke(prefs) }
            runCatching { prefsClass.getMethod("reload").invoke(prefs) }
            prefs as android.content.SharedPreferences
        }.getOrNull()
    }

    fun requestBackgroundScan(context: Context, reason: String = "manual") {
        if (instagramVersion.isEmpty()) return
        runCatching {
            val intent = Intent("${BuildConfig.APPLICATION_ID}.action.INSTAGRAM_DEXKIT_SCAN").apply {
                setPackage(BuildConfig.APPLICATION_ID)
                putExtra("apk_path", context.packageCodePath)
                val apkPaths = buildList {
                    context.packageCodePath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { add(it) }
                    context.applicationInfo.splitSourceDirs
                        ?.filter { it.isNotBlank() }
                        ?.let { addAll(it) }
                }.distinct()
                putExtra("apk_paths", apkPaths.toTypedArray())
                putExtra("app_version", instagramVersion)
            }
            context.sendBroadcast(intent)
            log("Requested background DexKit scan for Instagram $instagramVersion ($reason)")
        }.onFailure {
            log("Failed to request background DexKit scan: ${it.message}")
        }
    }

    fun isCacheValid(): Boolean = cacheValid

    fun clearCache() {
        prefs?.edit()?.clear()?.apply()
        cacheValid = false
        log("Cache manually cleared")
    }

    fun saveMethod(key: String, method: Method?) {
        if (method == null) return
        prefs?.edit()?.putString("m_$key", encode(method))?.apply()
    }

    fun loadMethod(key: String, classLoader: ClassLoader): Method? {
        val encoded = prefs?.getString("m_$key", null) ?: return null
        return decode(encoded, classLoader)
    }

    fun saveMethods(key: String, methods: List<Method>?) {
        if (methods == null) return
        val editor = prefs?.edit() ?: return
        editor.putInt("mc_$key", methods.size)
        methods.forEachIndexed { index, method ->
            editor.putString("m_${key}_$index", encode(method))
        }
        editor.apply()
    }

    fun loadMethods(key: String, classLoader: ClassLoader): List<Method>? {
        val sharedPrefs = prefs ?: return null
        val count = sharedPrefs.getInt("mc_$key", -1)
        if (count < 0) return null
        return buildList {
            for (index in 0 until count) {
                val encoded = sharedPrefs.getString("m_${key}_$index", null) ?: return null
                add(decode(encoded, classLoader) ?: return null)
            }
        }
    }

    fun saveString(key: String, value: String?) {
        prefs?.edit()?.putString("s_$key", value)?.apply()
    }

    fun loadString(key: String): String? = prefs?.getString("s_$key", null)

    private fun encode(method: Method): String {
        return method.declaringClass.name + SEP + method.name + SEP + descriptor(method)
    }

    private fun decode(encoded: String, classLoader: ClassLoader): Method? {
        return runCatching {
            val first = encoded.indexOf(SEP)
            val second = encoded.indexOf(SEP, first + 1)
            if (first < 0 || second < 0) return null
            val className = encoded.substring(0, first)
            val methodName = encoded.substring(first + 1, second)
            val methodDescriptor = encoded.substring(second + 1)
            val clazz = Class.forName(className, false, classLoader)
            clazz.declaredMethods.firstOrNull { method ->
                method.name == methodName && descriptor(method) == methodDescriptor
            }?.apply { isAccessible = true }
        }.getOrNull()
    }

    private fun descriptor(method: Method): String {
        return buildString {
            append('(')
            method.parameterTypes.forEach { appendType(it) }
            append(')')
            appendType(method.returnType)
        }
    }

    private fun StringBuilder.appendType(type: Class<*>) {
        var current = type
        while (current.isArray) {
            append('[')
            current = current.componentType
        }
        when {
            current == Void.TYPE -> append('V')
            current == java.lang.Boolean.TYPE -> append('Z')
            current == java.lang.Byte.TYPE -> append('B')
            current == java.lang.Character.TYPE -> append('C')
            current == java.lang.Short.TYPE -> append('S')
            current == java.lang.Integer.TYPE -> append('I')
            current == java.lang.Long.TYPE -> append('J')
            current == java.lang.Float.TYPE -> append('F')
            current == java.lang.Double.TYPE -> append('D')
            else -> append('L').append(current.name.replace('.', '/')).append(';')
        }
    }

    private fun log(message: String) {
        XposedBridge.log("[${InstagramFeatureState.TAG}] DexKitCache: $message")
    }
}
