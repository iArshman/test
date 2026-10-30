package me.eternal.purrfect.mapper

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.mapper.impl.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ApkLoadStats(
    val path: String,
    val dexFiles: Int,
    val classes: Int,
    val fromLspatchOrigin: Boolean,
)

class ClassMapper(
    private vararg val mappers: AbstractClassMapper = DEFAULT_MAPPERS,
) {
    private val classes = mutableListOf<ClassDef>()
    private val warnings = mutableListOf<String>()
    private val loadedApkStats = mutableListOf<ApkLoadStats>()

    companion object {
        val DEFAULT_MAPPERS get() = arrayOf(
            BCryptClassMapper(),
            CallbackMapper(),
            DefaultMediaItemMapper(),
            MediaQualityLevelProviderMapper(),
            OperaPageViewControllerMapper(),
            PlusSubscriptionMapper(),
            StoryBoostStateMapper(),
            FriendsFeedEventDispatcherMapper(),
            ChatEventDispatcherMapper(),
            CompositeConfigurationProviderMapper(),
            ScoreUpdateMapper(),
            FriendRelationshipChangerMapper(),
            ViewBinderMapper(),
            OperaViewerParamsMapper(),
            MemoriesPresenterMapper(),
            StreaksExpirationMapper(),
            COFObservableMapper(),
            FoldingLayoutMapper(),
            PlatformClientAttestationMapper(),
            ChatMediaDrawerMapper(),
            PlatformPresenceActionWrapperMapper(),
        )
    }


    fun loadApk(path: String): ApkLoadStats {
        var loadedDexFiles = 0
        var loadedClasses = 0
        var fromLspatchOrigin = false

        fun readClass(stream: InputStream) = runCatching {
            val dexClasses = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(stream)).classes
            classes.addAll(dexClasses)
            loadedDexFiles += 1
            loadedClasses += dexClasses.size
        }.onFailure {
            throw Throwable("Failed to load dex file", it)
        }

        fun filterDexClasses(name: String) = name.startsWith("classes") && name.endsWith(".dex")

        ZipFile(path).use { apkFile ->
            val apkEntries = apkFile.entries().toList()
            apkEntries.firstOrNull { it.name.endsWith("lspatch/origin.apk") }?.let { origin ->
                fromLspatchOrigin = true
                ZipInputStream(apkFile.getInputStream(origin)).use { originApk ->
                    var nextEntry = originApk.nextEntry
                    while (nextEntry != null) {
                        if (filterDexClasses(nextEntry.name)) {
                            readClass(originApk)
                        }
                        originApk.closeEntry()
                        nextEntry = originApk.nextEntry
                    }
                }
                return@use
            }

            apkEntries.filter { filterDexClasses(it.name) }.forEach {
                apkFile.getInputStream(it).use { stream ->
                    readClass(stream)
                }
            }
        }

        return ApkLoadStats(
            path = path,
            dexFiles = loadedDexFiles,
            classes = loadedClasses,
            fromLspatchOrigin = fromLspatchOrigin,
        ).also { loadedApkStats.add(it) }
    }

    fun getWarns() = warnings

    fun getLoadedApkStats() = loadedApkStats.toList()

    fun getLoadedClassCount() = classes.size

    fun getLoadedTypeCount() = classes.asSequence().map { it.type }.distinct().count()

    suspend fun run(): JsonObject {
        val context = MapperContext(classes.associateBy { it.type })

        withContext(Dispatchers.IO) {
            mappers.forEach { mapper ->
                launch {
                    mapper.run(context)
                }
            }
        }

        val outputJson = JsonObject()
        mappers.forEach { mapper ->
            outputJson.add(mapper.mapperName, JsonObject().apply {
                warnings.addAll(mapper.writeFromJson(this))
            })
        }
        return outputJson
    }
}
