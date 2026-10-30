package me.eternal.purrfect.mapper.impl

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.getAllConstStrings
import me.eternal.purrfect.mapper.ext.getClassName
import me.eternal.purrfect.mapper.ext.getSuperClassName
import java.util.Locale

class ThemeSurfaceMapper : AbstractClassMapper("ThemeSurface") {
    val valdiBridgeClass = classReference("valdiBridgeClass")
    val valdiSetValueMethod = string("valdiSetValueMethod")
    val valdiGetValueMethod = string("valdiGetValueMethod")
    val valdiDebugDescriptionMethod = string("valdiDebugDescriptionMethod")
    val valdiRetainedChildrenMethod = string("valdiRetainedChildrenMethod")
    val valdiGeneratedRootClasses = map("valdiGeneratedRootClasses")
    val themeCandidateClasses = map("themeCandidateClasses")
    val themeCandidateMethods = map("themeCandidateMethods")
    val surfaceAttributeStrings = map("surfaceAttributeStrings")
    val surfaceTokenStrings = map("surfaceTokenStrings")
    val surfaceStringOwners = map("surfaceStringOwners")

    init {
        mapper {
            val bridge = classes.firstOrNull(::isValdiBridgeClass)
            bridge?.let { classDef ->
                valdiBridgeClass.set(classDef.dotName())
                classDef.methods.forEach { method ->
                    when (method.name) {
                        "setValueForAttribute" -> valdiSetValueMethod.set(method.name)
                        "getValueForAttribute" -> valdiGetValueMethod.set(method.name)
                        "getViewNodeDebugDescription" -> valdiDebugDescriptionMethod.set(method.name)
                        "getRetainedViewNodeChildren" -> valdiRetainedChildrenMethod.set(method.name)
                    }
                }
            }

            classes
                .asSequence()
                .filter { classDef ->
                    classDef.getSuperClassName()?.contains("ValdiGeneratedRootView") == true ||
                        classDef.getClassName().contains("ValdiGeneratedRootView")
                }
                .take(MAX_VALDI_ROOT_CLASSES)
                .forEachIndexed { index, classDef ->
                    valdiGeneratedRootClasses.get()?.put("root_$index", classDef.dotName())
                }

            val scoredClasses = classes
                .mapNotNull(::scoreThemeClass)
                .sortedByDescending { it.score }
                .take(MAX_CANDIDATE_CLASSES)
            scoredClasses.forEachIndexed { index, scored ->
                themeCandidateClasses.get()?.put(
                    "class_$index",
                    "${scored.classDef.dotName()}|score=${scored.score}|reason=${scored.reason}"
                )
            }

            var methodIndex = 0
            var ownerIndex = 0
            classes.forEach { classDef ->
                val className = classDef.dotName()
                if (isIgnoredOwnerClass(className)) return@forEach
                classDef.methods.forEach { method ->
                    val strings = method.implementation?.getAllConstStrings().orEmpty()
                    val methodReason = scoreThemeMethod(className, method, strings)
                    if (methodReason != null && methodIndex < MAX_CANDIDATE_METHODS) {
                        themeCandidateMethods.get()?.put(
                            "method_${methodIndex++}",
                            "$className#${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")")}|$methodReason"
                        )
                    }

                    strings.forEach { rawString ->
                        val classified = classifySurfaceString(rawString) ?: return@forEach
                        when (classified.kind) {
                            SurfaceStringKind.ATTRIBUTE -> {
                                if (surfaceAttributeStrings.get()?.size.orZero() < MAX_SURFACE_ATTRIBUTES) {
                                    surfaceAttributeStrings.get()?.put(classified.normalized, rawString.trim().take(MAX_STRING_VALUE_LENGTH))
                                }
                            }
                            SurfaceStringKind.TOKEN -> {
                                if (surfaceTokenStrings.get()?.size.orZero() < MAX_SURFACE_TOKENS) {
                                    surfaceTokenStrings.get()?.put(classified.normalized, rawString.trim().take(MAX_STRING_VALUE_LENGTH))
                                }
                            }
                        }
                        if (ownerIndex < MAX_STRING_OWNERS) {
                            surfaceStringOwners.get()?.put(
                                "owner_${ownerIndex++}",
                                "${classified.kind.name.lowercase(Locale.US)}:${classified.normalized}|$className#${method.name}|${rawString.trim().take(MAX_STRING_VALUE_LENGTH)}"
                            )
                        }
                    }
                }
            }
        }
    }

    private data class ScoredClass(
        val classDef: ClassDef,
        val score: Int,
        val reason: String
    )

    private data class SurfaceString(
        val kind: SurfaceStringKind,
        val normalized: String
    )

    private enum class SurfaceStringKind {
        ATTRIBUTE,
        TOKEN
    }

    private fun isValdiBridgeClass(classDef: ClassDef): Boolean {
        val methods = classDef.methods.map { it.name }.toSet()
        return methods.contains("setValueForAttribute") &&
            methods.contains("getValueForAttribute") &&
            methods.any { it.contains("ViewNode") }
    }

    private fun scoreThemeClass(classDef: ClassDef): ScoredClass? {
        val className = classDef.dotName()
        if (isIgnoredOwnerClass(className)) return null
        val lower = className.lowercase(Locale.US)
        var score = 0
        val reasons = mutableListOf<String>()

        themeClassTokens.forEach { token ->
            if (token in lower) {
                score += 2
                reasons += token
            }
        }
        val superName = classDef.getSuperClassName()?.replace('/', '.')?.lowercase(Locale.US).orEmpty()
        if ("valdi" in superName || "rootview" in superName) {
            score += 4
            reasons += "super=$superName"
        }
        if (classDef.fields.any { field -> classifySurfaceString(field.name) != null }) {
            score += 2
            reasons += "surface_field"
        }
        if (classDef.methods.any { method -> method.name.contains("Attribute", ignoreCase = true) }) {
            score += 2
            reasons += "attribute_method"
        }
        return if (score > 0) ScoredClass(classDef, score, reasons.distinct().joinToString(",")) else null
    }

    private fun scoreThemeMethod(className: String, method: Method, strings: List<String>): String? {
        if (isIgnoredOwnerClass(className)) return null
        val lowerClass = className.lowercase(Locale.US)
        val lowerMethod = method.name.lowercase(Locale.US)
        val reasons = mutableListOf<String>()
        if (themeClassTokens.any { it in lowerClass }) reasons += "class_token"
        if (themeMethodTokens.any { it in lowerMethod }) reasons += "method_token"
        if (strings.any { classifySurfaceString(it) != null }) reasons += "surface_string"
        return reasons.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun classifySurfaceString(raw: String): SurfaceString? {
        val trimmed = raw.trim()
        if (trimmed.length !in 3..120) return null
        val normalized = normalizeSurfaceKey(trimmed)
        if (normalized.isBlank()) return null
        val compact = normalized.replace(Regex("[._-]+"), "")
        if (!isLikelySurfaceLiteral(trimmed, normalized, compact)) return null
        if (normalized in ignoredSurfaceStrings || ignoredSurfaceFragments.any { it in compact }) return null
        if (attributeNames.any { normalizeSurfaceKey(it) == normalized }) {
            return SurfaceString(SurfaceStringKind.ATTRIBUTE, normalized)
        }
        if (tokenNeedles.any { it in compact }) {
            return SurfaceString(SurfaceStringKind.TOKEN, normalized)
        }
        if (isLikelyExperimentOrConfigSurfaceString(trimmed, normalized, compact)) return null
        if (attributeNeedles.any { it in compact }) {
            return SurfaceString(SurfaceStringKind.ATTRIBUTE, normalized)
        }
        return null
    }

    private fun isLikelySurfaceLiteral(raw: String, normalized: String, compact: String): Boolean {
        if (raw.any { it.isWhitespace() }) return false
        if (raw.any { it == '\'' || it == '"' || it == ':' || it == ';' || it == ',' || it == '(' || it == ')' }) return false
        if (raw.count { it == '/' } > 1) return false
        if (compact.length < 3 || compact.length > 96) return false
        if (compact.all { it.isDigit() }) return false
        if (normalized.count { it == '-' } >= 4) return false
        return true
    }

    private fun isLikelyExperimentOrConfigSurfaceString(raw: String, normalized: String, compact: String): Boolean {
        val upperSnake = raw == raw.uppercase(Locale.US) && raw.contains('_')
        val separated = normalized.any { it == '_' || it == '-' || it == '.' }
        val hasConfigFragment = ignoredConfigFragments.any { it in compact }
        return hasConfigFragment && (upperSnake || separated)
    }

    private fun isIgnoredOwnerClass(className: String): Boolean {
        val lower = className.lowercase(Locale.US)
        return ignoredOwnerPrefixes.any { lower.startsWith(it) }
    }

    private fun ClassDef.dotName(): String = getClassName().replace('/', '.')

    private fun normalizeSurfaceKey(value: String): String {
        return value.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_', '.', '-')
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val MAX_VALDI_ROOT_CLASSES = 80
        private const val MAX_CANDIDATE_CLASSES = 180
        private const val MAX_CANDIDATE_METHODS = 240
        private const val MAX_SURFACE_ATTRIBUTES = 240
        private const val MAX_SURFACE_TOKENS = 260
        private const val MAX_STRING_OWNERS = 360
        private const val MAX_STRING_VALUE_LENGTH = 120

        private val attributeNames = setOf(
            "background",
            "backgroundColor",
            "background_color",
            "backgroundFill",
            "background_fill",
            "backgroundToken",
            "background_token",
            "surface",
            "surfaceColor",
            "surface_color",
            "container",
            "containerColor",
            "container_color",
            "card",
            "cardColor",
            "card_color",
            "panel",
            "panelColor",
            "panel_color",
            "sheet",
            "sheetColor",
            "sheet_color",
            "rowBackground",
            "rowBackgroundColor",
            "cellBackground",
            "cellBackgroundColor",
            "itemBackground",
            "itemBackgroundColor",
            "selectedBackground",
            "selectedBackgroundColor",
            "fill",
            "fillColor",
            "fill_color",
            "primaryFill",
            "primaryFillColor",
            "secondaryFill",
            "secondaryFillColor",
            "chromeColor"
        )

        private val attributeNeedles = listOf(
            "backgroundcolor",
            "backgroundfill",
            "backgroundtoken",
            "surfacecolor",
            "containercolor",
            "cardcolor",
            "panelcolor",
            "sheetcolor",
            "rowbackground",
            "cellbackground",
            "itembackground",
            "selectedbackground",
            "primaryfill",
            "secondaryfill",
            "chromecolor"
        )

        private val tokenNeedles = listOf(
            "backgroundmain",
            "backgroundsubscreen",
            "backgroundsurface",
            "backgroundsurfaceup",
            "backgroundsurfacedown",
            "backgroundobject",
            "backgroundobjectdown",
            "backgroundabovesurface",
            "backgrounddisabled",
            "backgroundoverlay",
            "flatpureblack",
            "flatblack",
            "flatgray",
            "flatgrey",
            "gray900",
            "grey900",
            "gray850",
            "grey850",
            "gray800",
            "grey800",
            "darkgray",
            "darkgrey"
        )

        private val themeClassTokens = listOf(
            "valdi",
            "composer",
            "theme",
            "surface",
            "background",
            "dialog",
            "modal",
            "sheet",
            "panel",
            "card",
            "cell",
            "row",
            "viewnode",
            "rootview"
        )

        private val themeMethodTokens = listOf(
            "attribute",
            "background",
            "surface",
            "theme",
            "color",
            "viewnode",
            "bind",
            "render",
            "inflate"
        )

        private val ignoredSurfaceStrings = setOf(
            "background_thread",
            "transparent",
            "clear",
            "none",
            "color",
            "colors",
            "imagebackground"
        )

        private val ignoredSurfaceFragments = listOf(
            "androidxwork",
            "gcmscheduler",
            "appbackgrounded",
            "appforegrounded",
            "backgrounded",
            "foregrounded",
            "backgroundthread",
            "threadbackground",
            "backgroundworker",
            "appbackgroundobservable",
            "backgroundobservable",
            "browserbackgroundexitmethod",
            "backgroundexitmethod",
            "backgroundenable",
            "backgroundenabled",
            "backgroundoverride",
            "backgroundcoloroverride",
            "backgroundanimation",
            "backgroundcolordelay",
            "backgroundcolorduration",
            "coloranimationdelay",
            "coloranimationduration",
            "backgroundttl",
            "backgroundseconds",
            "backgroundtime",
            "backgroundmills",
            "backgroundmillis",
            "backgroundurl",
            "backgrounduri",
            "backgroundid",
            "bitmojibackground",
            "batterybackground",
            "mushroom",
            "backgroundmetrics",
            "backgroundupload",
            "uploadbackground",
            "jobconstraint",
            "constraints",
            "notificationbackground",
            "workmanager",
            "backoffpolicy"
        )

        private val ignoredConfigFragments = listOf(
            "metrics",
            "durablejob",
            "eligibility",
            "upload",
            "ban",
            "delay",
            "delays",
            "queue",
            "queues",
            "periodic",
            "favoritead",
            "slug",
            "promotion",
            "promotions",
            "flatland",
            "selfie",
            "opacity",
            "defaults",
            "sealqueue",
            "forcebackground",
            "enablebackground",
            "stale",
            "scene",
            "cta",
            "picker",
            "profile",
            "bitmoji",
            "blizzard"
        )

        private val ignoredOwnerPrefixes = listOf(
            "android.",
            "androidx.",
            "kotlin.",
            "kotlinx.",
            "java.",
            "javax.",
            "dalvik.",
            "com.google.",
            "com.android.",
            "okhttp3.",
            "okio.",
            "retrofit2.",
            "org.jetbrains.",
            "org.intellij.",
            "org.xmlpull.",
            "org.json."
        )
    }
}
