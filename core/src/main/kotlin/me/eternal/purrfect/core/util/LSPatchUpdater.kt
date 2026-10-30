package me.eternal.purrfect.core.util

import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.ModContext
import java.io.File
import java.util.zip.ZipFile

object LSPatchUpdater {
    private const val TAG = "LSPatchUpdater"
    private const val XPOSED_INIT_ENTRY = "assets/xposed_init"
    private const val XPOSED_LOADER_CLASS = "me.eternal.purrfect.core.XposedLoader"

    var HAS_LSPATCH = false
        private set

    private fun translatedOrFallback(context: ModContext, key: String, fallback: String): String {
        val value = context.translation.getOrNull(key) ?: return fallback
        return if (value.equals(key, ignoreCase = false)) fallback else value
    }

    private fun ensureTranslationsLoaded(context: ModContext) {
        if (context.translation.getOrNull("toast_purrfect_updated") != null) return
        runCatching {
            context.translation.userLocale = context.getConfigLocale()
            context.translation.load()
        }.onFailure {
            context.log.warn("Failed to load translations in updater: ${it.message}", TAG)
        }
    }

    private fun getModuleUniqueHash(module: ZipFile): String {
        return module.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.crc }
            .reduce { acc, crc -> acc xor crc }
            .toString(16)
    }

    fun onBridgeConnected(context: ModContext) {
        ensureTranslationsLoaded(context)

        val embeddedModule = findEmbeddedModule(context) ?: return

        HAS_LSPATCH = true
        context.log.verbose("Found embedded Purrfect at ${embeddedModule.absolutePath}", TAG)

        val seAppApk = File(context.bridgeClient.getApplicationApkPath()).also {
            if (!it.canRead()) {
                throw IllegalStateException("Cannot read Purrfect apk")
            }
        }

        runCatching {
            if (getModuleUniqueHash(ZipFile(embeddedModule)) == getModuleUniqueHash(ZipFile(seAppApk))) {
                context.log.verbose("Embedded Purrfect is up to date", TAG)
                return
            }
        }.onFailure {
            throw IllegalStateException("Failed to compare module signature", it)
        }

        context.log.verbose("updating", TAG)
        context.shortToast(
            translatedOrFallback(
                context,
                "toast_updating_purrfect",
                "Updating Purrfect. Please wait..."
            )
        )
        // copy embedded module to cache
        runCatching {
            seAppApk.copyTo(embeddedModule, overwrite = true)
        }.onFailure {
            seAppApk.delete()
            context.log.error("Failed to copy embedded module", it, TAG)
            context.longToast(
                translatedOrFallback(
                    context,
                    "toast_update_purrfect_failed",
                    "Failed to update Purrfect. Please check logcat for more details."
                )
            )
            context.forceCloseApp()
            return
        }

        context.longToast(
            translatedOrFallback(
                context,
                "toast_purrfect_updated",
                "Purrfect updated!"
            )
        )
        context.log.verbose("updated", TAG)
        context.softRestartApp()
    }

    private fun findEmbeddedModule(context: ModContext): File? {
        val roots = listOfNotNull(context.androidContext.filesDir, context.androidContext.cacheDir)

        roots.asSequence()
            .flatMap { root ->
                sequenceOf(
                    File(root, "lspatch/modules/${Constants.MODULE_PACKAGE_NAME}"),
                    File(root, "lspatch/${Constants.MODULE_PACKAGE_NAME}"),
                    File(root, "org.lsposed.lspatch/${Constants.MODULE_PACKAGE_NAME}")
                )
            }
            .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull(::isEmbeddedPurrfectModule)
            ?.let { return it }

        return roots.asSequence()
            .filter { it.exists() && it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .firstOrNull(::isEmbeddedPurrfectModule)
    }

    private fun isEmbeddedPurrfectModule(file: File): Boolean {
        if (!file.isFile || !file.canRead() || !file.extension.equals("apk", ignoreCase = true)) return false
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(XPOSED_INIT_ENTRY) ?: return@use false
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    reader.lineSequence().any { it.trim() == XPOSED_LOADER_CLASS }
                }
            }
        }.getOrDefault(false)
    }
}

