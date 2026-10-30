package me.eternal.purrfect.core.instagram

import android.os.Build
import de.robv.android.xposed.XposedBridge
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

internal class InstagramDexKitBridge(
    private val apkPath: String?,
    private val classLoader: ClassLoader,
    private val moduleSourcePath: String? = null
) : Closeable {
    companion object {
        @Volatile
        private var dexKitNativeLoaded = false
        private val dexKitNativeLoadLock = Any()
    }

    data class MethodRef(
        val className: String,
        val name: String,
        val returnType: String,
        val paramTypes: List<String>,
        val method: Method?,
        val invokes: List<MethodRef> = emptyList()
    )

    private val bridge: Any? by lazy {
        runCatching {
            if (apkPath.isNullOrBlank()) return@runCatching null
            ensureDexKitNativeLoaded()
            val bridgeClass = Class.forName("org.luckypray.dexkit.DexKitBridge")
            createDexKitBridge(bridgeClass)
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit unavailable: ${throwable.message}")
        }.getOrNull()
    }

    private fun createDexKitBridge(bridgeClass: Class<*>): Any {
        runCatching {
            bridgeClass.declaredMethods
                .firstOrNull { method ->
                    method.name == "create" &&
                        method.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
                }
                ?.let { method ->
                    method.isAccessible = true
                    return method.invoke(null, apkPath)
                }
        }.onFailure { XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit static create(String) failed: ${it.message}") }

        runCatching {
            val companion = bridgeClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            companion.javaClass.declaredMethods
                .firstOrNull { method ->
                    method.name == "create" &&
                        method.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
                }
                ?.let { method ->
                    method.isAccessible = true
                    return method.invoke(companion, apkPath)
                }
        }.onFailure { XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit companion create(String) failed: ${it.message}") }

        runCatching {
            bridgeClass.declaredMethods
                .firstOrNull { method ->
                    method.name == "create" &&
                        method.parameterTypes.size == 2 &&
                        ClassLoader::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.parameterTypes[1] == java.lang.Boolean.TYPE
                }
                ?.let { method ->
                    method.isAccessible = true
                    return method.invoke(null, classLoader, false)
                }
        }.onFailure { XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit static create(ClassLoader, boolean) failed: ${it.message}") }

        runCatching {
            val companion = bridgeClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            companion.javaClass.declaredMethods
                .firstOrNull { method ->
                    method.name == "create" &&
                        method.parameterTypes.size == 2 &&
                        ClassLoader::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.parameterTypes[1] == java.lang.Boolean.TYPE
                }
                ?.let { method ->
                    method.isAccessible = true
                    return method.invoke(companion, classLoader, false)
                }
        }.onFailure { XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit companion create(ClassLoader, boolean) failed: ${it.message}") }

        val methods = bridgeClass.declaredMethods.joinToString { method ->
            "${method.name}(${method.parameterTypes.joinToString { it.name }})"
        }
        error("No compatible DexKitBridge.create entry point found. Methods=$methods")
    }

    private fun ensureDexKitNativeLoaded() {
        if (dexKitNativeLoaded) return
        synchronized(dexKitNativeLoadLock) {
            if (dexKitNativeLoaded) return
            var firstError: Throwable? = null

            dexKitNativeLibraryCandidates().forEach { candidate ->
                if (!candidate.exists()) return@forEach
                val result = runCatching { System.load(candidate.absolutePath) }
                if (result.isSuccess) {
                    dexKitNativeLoaded = true
                    XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit native loaded from ${candidate.absolutePath}")
                    return
                }
                firstError = firstError ?: result.exceptionOrNull()
            }

            val fallback = runCatching { System.loadLibrary("dexkit") }
            if (fallback.isSuccess) {
                dexKitNativeLoaded = true
                XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit native loaded through loadLibrary")
                return
            }

            throw firstError ?: fallback.exceptionOrNull() ?: UnsatisfiedLinkError("libdexkit.so not found")
        }
    }

    private fun dexKitNativeLibraryCandidates(): List<File> {
        val modulePath = moduleSourcePath?.takeIf { it.isNotBlank() } ?: return emptyList()
        val moduleDir = File(modulePath).parentFile ?: return emptyList()
        return Build.SUPPORTED_ABIS
            .flatMap { abi -> dexKitAbiFolders(abi) }
            .distinct()
            .map { abiFolder -> File(moduleDir, "lib/$abiFolder/libdexkit.so") }
    }

    private fun dexKitAbiFolders(abi: String): List<String> {
        val normalized = abi.lowercase(Locale.US)
        return when (normalized) {
            "arm64-v8a" -> listOf("arm64", abi)
            "armeabi-v7a", "armeabi", "armv8i" -> listOf("arm", abi)
            "x86", "x86_64" -> listOf(abi)
            else -> listOf(abi)
        }
    }

    fun findMethodsUsingStrings(vararg strings: String): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (strings.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            applyStringMatcher(methodMatcherClass, matcher, strings)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit string search failed for ${strings.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsUsingStringsConstrained(
        strings: List<String>,
        paramCount: Int? = null,
        returnType: String? = null
    ): List<Method> {
        if (strings.isEmpty()) return emptyList()
        return findMethodsWithMatcher("constrained string search for ${strings.joinToString()}") { methodMatcherClass, matcher ->
            applyStringMatcher(methodMatcherClass, matcher, strings.toTypedArray())
            if (paramCount != null) {
                methodMatcherClass.getMethod("paramCount", java.lang.Integer.TYPE).invoke(matcher, paramCount)
            }
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
        }
    }

    fun findMethodsUsingNumbersConstrained(
        numbers: List<Int>,
        paramCount: Int? = null,
        returnType: String? = null
    ): List<Method> {
        if (numbers.isEmpty()) return emptyList()
        return findMethodsWithMatcher("constrained number search for ${numbers.joinToString()}") { methodMatcherClass, matcher ->
            applyNumberMatcher(methodMatcherClass, matcher, numbers.map { it.toLong() }.toLongArray())
            if (paramCount != null) {
                methodMatcherClass.getMethod("paramCount", java.lang.Integer.TYPE).invoke(matcher, paramCount)
            }
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
        }
    }

    private fun findMethodsWithMatcher(
        logName: String,
        configureMatcher: (Class<*>, Any) -> Unit
    ): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            configureMatcher(methodMatcherClass, matcher)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit $logName failed: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodRefsUsingStrings(vararg strings: String): List<MethodRef> {
        if (strings.isEmpty()) return emptyList()
        return findMethodRefs(
            logName = "method-ref string search for ${strings.joinToString()}",
            configureMatcher = { methodMatcherClass, matcher ->
                applyStringMatcher(methodMatcherClass, matcher, strings)
            }
        )
    }

    fun findMethodRefsInClassUsingStrings(declaredClass: String, vararg strings: String): List<MethodRef> {
        if (declaredClass.isBlank() || strings.isEmpty()) return emptyList()
        return findMethodRefs(
            logName = "method-ref class/string search for $declaredClass",
            configureMatcher = { methodMatcherClass, matcher ->
                methodMatcherClass.getMethod("declaredClass", String::class.java).invoke(matcher, declaredClass)
                applyStringMatcher(methodMatcherClass, matcher, strings)
            }
        )
    }

    fun findMethodRefsUsingNumbers(vararg numbers: Long): List<MethodRef> {
        if (numbers.isEmpty()) return emptyList()
        return findMethodRefs(
            logName = "method-ref number search for ${numbers.joinToString()}",
            configureMatcher = { methodMatcherClass, matcher ->
                applyNumberMatcher(methodMatcherClass, matcher, numbers)
            }
        )
    }

    private fun findMethodRefs(
        logName: String,
        configureMatcher: (Class<*>, Any) -> Unit
    ): List<MethodRef> {
        val activeBridge = bridge ?: return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            configureMatcher(methodMatcherClass, matcher)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { methodDataToRef(it, includeInvokes = true) }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit $logName failed: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    private fun methodDataToRef(data: Any?, includeInvokes: Boolean): MethodRef? {
        data ?: return null
        return runCatching {
            val dataClass = data.javaClass
            val className = dataClass.getMethod("getClassName").invoke(data) as? String ?: return@runCatching null
            val name = dataClass.getMethod("getName").invoke(data) as? String ?: ""
            val returnType = dataClass.getMethod("getReturnType").invoke(data)?.toString().orEmpty()
            val paramTypes = toStringList(dataClass.getMethod("getParamTypes").invoke(data))
            val method = runCatching {
                dataClass.getMethod("getMethodInstance", ClassLoader::class.java).invoke(data, classLoader) as? Method
            }.getOrNull()
            val invokes = if (includeInvokes) {
                runCatching {
                    toAnyList(dataClass.getMethod("getInvokes").invoke(data))
                        .mapNotNull { methodDataToRef(it, includeInvokes = false) }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            MethodRef(className, name, returnType, paramTypes, method, invokes)
        }.getOrNull()
    }

    private fun toStringList(value: Any?): List<String> {
        return toAnyList(value).map { it.toString() }
    }

    private fun toAnyList(value: Any?): List<Any?> {
        return when (value) {
            null -> emptyList()
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            is BooleanArray -> value.toList()
            is ByteArray -> value.toList()
            is CharArray -> value.toList()
            is ShortArray -> value.toList()
            is IntArray -> value.toList()
            is LongArray -> value.toList()
            is FloatArray -> value.toList()
            is DoubleArray -> value.toList()
            else -> emptyList()
        }
    }

    fun findMethodsUsingNumbers(vararg numbers: Int): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (numbers.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            applyNumberMatcher(methodMatcherClass, matcher, numbers.map { it.toLong() }.toLongArray())
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit number search failed for ${numbers.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsUsingNumbers(vararg numbers: Long): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (numbers.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            applyNumberMatcher(methodMatcherClass, matcher, numbers)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit number search failed for ${numbers.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    private fun applyNumberMatcher(methodMatcherClass: Class<*>, matcher: Any, numbers: LongArray) {
        val usingNumbers = methodMatcherClass.methods.firstOrNull { method ->
            method.name == "usingNumbers" && method.parameterTypes.size == 1
        } ?: return
        val parameterType = usingNumbers.parameterTypes[0]
        when {
            parameterType == LongArray::class.java -> usingNumbers.invoke(matcher, numbers)
            parameterType == IntArray::class.java -> usingNumbers.invoke(matcher, numbers.map { it.toInt() }.toIntArray())
            parameterType.isArray && parameterType.componentType == java.lang.Long::class.java -> {
                usingNumbers.invoke(matcher, numbers.toTypedArray() as Any)
            }
            parameterType.isArray && parameterType.componentType == java.lang.Integer::class.java -> {
                usingNumbers.invoke(matcher, numbers.map { it.toInt() }.toTypedArray() as Any)
            }
            parameterType.isArray && parameterType.componentType == java.lang.Number::class.java -> {
                usingNumbers.invoke(matcher, numbers.map { it as Number }.toTypedArray() as Any)
            }
            java.util.Collection::class.java.isAssignableFrom(parameterType) -> {
                usingNumbers.invoke(matcher, numbers.map { it as Number })
            }
            else -> usingNumbers.invoke(matcher, numbers.toTypedArray() as Any)
        }
    }

    private fun applyStringMatcher(matcherClass: Class<*>, matcher: Any, strings: Array<out String>) {
        val usingStrings = matcherClass.methods.firstOrNull { method ->
            method.name == "usingStrings" &&
                method.parameterTypes.size == 1 &&
                ((method.parameterTypes[0].isArray && method.parameterTypes[0].componentType == String::class.java) ||
                    java.util.Collection::class.java.isAssignableFrom(method.parameterTypes[0]))
        } ?: return
        val parameterType = usingStrings.parameterTypes[0]
        if (java.util.Collection::class.java.isAssignableFrom(parameterType)) {
            usingStrings.invoke(matcher, strings.toList())
        } else {
            usingStrings.invoke(matcher, strings as Any)
        }
    }

    fun findClassNamesUsingNumbers(vararg numbers: Int): List<String> {
        val activeBridge = bridge ?: return emptyList()
        if (numbers.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            applyNumberMatcher(methodMatcherClass, matcher, numbers.map { it.toLong() }.toLongArray())
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching { data?.javaClass?.getMethod("getClassName")?.invoke(data) as? String }.getOrNull()
            }.distinct()
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit number class search failed for ${numbers.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findClassNamesUsingStrings(vararg strings: String): List<String> {
        val activeBridge = bridge ?: return emptyList()
        if (strings.isEmpty()) return emptyList()
        return runCatching {
            val findClassClass = Class.forName("org.luckypray.dexkit.query.FindClass")
            val classMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.ClassMatcher")
            val findClass = findClassClass.getMethod("create").invoke(null)
            val matcher = classMatcherClass.getMethod("create").invoke(null)
            applyStringMatcher(classMatcherClass, matcher, strings)
            findClassClass.getMethod("matcher", classMatcherClass).invoke(findClass, matcher)
            val results = activeBridge.javaClass.getMethod("findClass", findClassClass).invoke(activeBridge, findClass) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching { data?.javaClass?.getMethod("getName")?.invoke(data) as? String }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit class search failed for ${strings.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findClassNamesWithFieldNames(vararg fieldNames: String): List<String> {
        val activeBridge = bridge ?: return emptyList()
        if (fieldNames.isEmpty()) return emptyList()
        return runCatching {
            val findClassClass = Class.forName("org.luckypray.dexkit.query.FindClass")
            val classMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.ClassMatcher")
            val findClass = findClassClass.getMethod("create").invoke(null)
            val matcher = classMatcherClass.getMethod("create").invoke(null)
            val addFieldForName = classMatcherClass.methods.firstOrNull { method ->
                method.name == "addFieldForName" && method.parameterTypes.size == 1 && method.parameterTypes[0] == String::class.java
            } ?: return@runCatching emptyList()
            fieldNames.forEach { name -> addFieldForName.invoke(matcher, name) }
            findClassClass.getMethod("matcher", classMatcherClass).invoke(findClass, matcher)
            val results = activeBridge.javaClass.getMethod("findClass", findClassClass).invoke(activeBridge, findClass) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching { data?.javaClass?.getMethod("getName")?.invoke(data) as? String }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit field-name class search failed for ${fieldNames.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findClassNamesWithFieldTypes(vararg fieldTypes: String): List<String> {
        val activeBridge = bridge ?: return emptyList()
        if (fieldTypes.isEmpty()) return emptyList()
        return runCatching {
            val findClassClass = Class.forName("org.luckypray.dexkit.query.FindClass")
            val classMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.ClassMatcher")
            val findClass = findClassClass.getMethod("create").invoke(null)
            val matcher = classMatcherClass.getMethod("create").invoke(null)
            val addFieldForType = classMatcherClass.methods.firstOrNull { method ->
                method.name == "addFieldForType" && method.parameterTypes.size == 1 && method.parameterTypes[0] == String::class.java
            } ?: return@runCatching emptyList()
            fieldTypes.forEach { type -> addFieldForType.invoke(matcher, type) }
            findClassClass.getMethod("matcher", classMatcherClass).invoke(findClass, matcher)
            val results = activeBridge.javaClass.getMethod("findClass", findClassClass).invoke(activeBridge, findClass) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching { data?.javaClass?.getMethod("getName")?.invoke(data) as? String }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit field-type class search failed for ${fieldTypes.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsByParamTypes(returnType: String? = null, vararg paramTypeNames: String): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (paramTypeNames.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
            methodMatcherClass.getMethod("paramTypes", Array<String>::class.java)
                .invoke(matcher, arrayOf(*paramTypeNames))
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit param search failed for ${paramTypeNames.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsByParamTypesWithWildcards(returnType: String? = null, paramTypeNames: List<String?>): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (paramTypeNames.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
            @Suppress("UNCHECKED_CAST")
            val params = paramTypeNames.toTypedArray() as Array<String>
            methodMatcherClass.getMethod("paramTypes", Array<String>::class.java).invoke(matcher, params)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit wildcard param search failed for ${paramTypeNames.joinToString()}: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsByName(name: String, returnType: String? = null, paramCount: Int? = null): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (name.isBlank()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            methodMatcherClass.getMethod("name", String::class.java).invoke(matcher, name)
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
            if (paramCount != null) {
                methodMatcherClass.getMethod("paramCount", Int::class.javaPrimitiveType).invoke(matcher, paramCount)
            }
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit method-name search failed for $name: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findMethodsInClassUsingStrings(
        declaredClass: String,
        returnType: String? = null,
        paramTypes: List<String> = emptyList(),
        vararg strings: String
    ): List<Method> {
        val activeBridge = bridge ?: return emptyList()
        if (declaredClass.isBlank() || strings.isEmpty()) return emptyList()
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            methodMatcherClass.getMethod("declaredClass", String::class.java).invoke(matcher, declaredClass)
            if (!returnType.isNullOrBlank()) {
                methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, returnType)
            }
            if (paramTypes.isNotEmpty()) {
                methodMatcherClass.getMethod("paramTypes", Array<String>::class.java)
                    .invoke(matcher, arrayOf(*paramTypes.toTypedArray()))
            }
            applyStringMatcher(methodMatcherClass, matcher, strings)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
                ?: return@runCatching emptyList()
            results.mapNotNull { data ->
                runCatching {
                    data?.javaClass?.getMethod("getMethodInstance", ClassLoader::class.java)?.invoke(data, classLoader) as? Method
                }.getOrNull()
            }
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit class/string method search failed for $declaredClass: ${throwable.message}")
        }.getOrDefault(emptyList())
    }

    fun findDevOptionsStructuralGate(): Method? {
        val activeBridge = bridge ?: return null
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            val methodsMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodsMatcher")
            val methodDataClass = Class.forName("org.luckypray.dexkit.result.MethodData")

            // 1. Find getters
            val getterFindMethod = findMethodClass.getMethod("create").invoke(null)
            val getterMatcher = methodMatcherClass.getMethod("create").invoke(null)
            methodMatcherClass.getMethod("declaredClass", String::class.java).invoke(getterMatcher, "com.facebook.mobileconfig.factory.MobileConfigUnsafeContext")
            methodMatcherClass.getMethod("returnType", String::class.java).invoke(getterMatcher, "boolean")
            methodMatcherClass.getMethod("paramTypes", Array<String>::class.java).invoke(getterMatcher, arrayOf("long"))
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(getterFindMethod, getterMatcher)

            val getters = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, getterFindMethod) as? Iterable<*>
            if (getters == null || !getters.iterator().hasNext()) return@runCatching null

            // 2. Create MethodsMatcher for invokes
            val getterInvoke = methodsMatcherClass.getMethod("create").invoke(null)
            val getDescriptorMethod = methodDataClass.getMethod("getDescriptor")
            val methodsMatcherAdd = methodsMatcherClass.getMethod("add", methodMatcherClass)

            for (getter in getters) {
                val descriptor = getDescriptorMethod.invoke(getter) as String
                val invokeMatcher = methodMatcherClass.getMethod("create", String::class.java).invoke(null, descriptor)
                methodsMatcherAdd.invoke(getterInvoke, invokeMatcher)
            }

            // 3. Find candidates
            val candidateFindMethod = findMethodClass.getMethod("create").invoke(null)
            val candidateMatcher = methodMatcherClass.getMethod("create").invoke(null)
            methodMatcherClass.getMethod("returnType", String::class.java).invoke(candidateMatcher, "boolean")
            methodMatcherClass.getMethod("paramTypes", Array<String>::class.java).invoke(candidateMatcher, arrayOf("com.instagram.common.session.UserSession"))
            methodMatcherClass.getMethod("invokeMethods", methodsMatcherClass).invoke(candidateMatcher, getterInvoke)
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(candidateFindMethod, candidateMatcher)

            val candidates = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, candidateFindMethod) as? Iterable<*>
            if (candidates == null) return@runCatching null

            var bestMethodData: Any? = null
            var bestFanIn = 0
            val getOpCodesMethod = methodDataClass.getMethod("getOpCodes")
            val getCallersMethod = methodDataClass.getMethod("getCallers")
            val getClassNameMethod = methodDataClass.getMethod("getClassName")

            for (candidate in candidates) {
                val opCodes = getOpCodesMethod.invoke(candidate) as? Collection<*>
                if (opCodes != null && opCodes.size > 16) continue

                val callersObj = runCatching { getCallersMethod.invoke(candidate) }.getOrNull()
                val callers = if (callersObj is Array<*>) callersObj.toList() else callersObj as? Iterable<*>
                if (callers != null) {
                    val callerClasses = mutableSetOf<String>()
                    for (caller in callers) {
                        val callerClass = getClassNameMethod.invoke(caller) as String
                        callerClasses.add(callerClass)
                    }
                    if (callerClasses.size > bestFanIn) {
                        bestFanIn = callerClasses.size
                        bestMethodData = candidate
                    }
                }
            }

            if (bestFanIn >= 3 && bestMethodData != null) {
                return@runCatching methodDataClass.getMethod("getMethodInstance", ClassLoader::class.java).invoke(bestMethodData, classLoader) as? Method
            }
            null
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit DevOptions structural search failed: ${throwable.message}")
        }.getOrNull()
    }

    fun findDevOptionsLegacyConfigId(configId: Long): String? {
        val activeBridge = bridge ?: return null
        return runCatching {
            val findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod")
            val methodMatcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher")
            
            val findMethod = findMethodClass.getMethod("create").invoke(null)
            val matcher = methodMatcherClass.getMethod("create").invoke(null)
            
            methodMatcherClass.getMethod("usingNumbers", LongArray::class.java).invoke(matcher, longArrayOf(configId))
            methodMatcherClass.getMethod("returnType", String::class.java).invoke(matcher, "boolean")
            methodMatcherClass.getMethod("paramCount", Integer.TYPE).invoke(matcher, 1)
            
            findMethodClass.getMethod("matcher", methodMatcherClass).invoke(findMethod, matcher)
            
            val results = activeBridge.javaClass.getMethod("findMethod", findMethodClass).invoke(activeBridge, findMethod) as? Iterable<*>
            if (results != null) {
                val methodDataClass = Class.forName("org.luckypray.dexkit.result.MethodData")
                val getClassNameMethod = methodDataClass.getMethod("getClassName")
                for (data in results) {
                    return@runCatching getClassNameMethod.invoke(data) as String
                }
            }
            null
        }.onFailure { throwable ->
            XposedBridge.log("[${InstagramFeatureState.TAG}] DexKit DevOptions legacy search failed: ${throwable.message}")
        }.getOrNull()
    }

    override fun close() {
        runCatching { (bridge as? Closeable)?.close() }
    }
}
