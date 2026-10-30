package me.eternal.purrfect.core.reddit

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.core.logger.CoreLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object RedditDeletedContentRestorer {
    private const val TAG = RedditAdBlockHooks.TAG
    private const val ARCTIC_SHIFT_BASE_URL = "https://arctic-shift.photon-reddit.com/api/"
    private val installedBuilders = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val commentCache = ConcurrentHashMap<String, JSONObject?>()
    private val postCache = ConcurrentHashMap<String, JSONObject?>()
    private val restoredNodes = AtomicInteger(0)
    private val filteredCommunityNodes = AtomicInteger(0)
    private val restoredCommentModels = AtomicInteger(0)

    fun install(builderClass: Class<*>) {
        runCatching {
            builderClass.declaredMethods
                .filter { method ->
                    method.name == "build" &&
                        method.parameterTypes.isEmpty() &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                val state = RedditFeatureStateStore.current
                                if (!state.restoreDeletedContent && !state.hideRecommendedCommunities) return
                                val builder = param.thisObject ?: return
                                synchronized(installedBuilders) {
                                    if (!installedBuilders.add(builder)) return
                                }
                                addInterceptor(builderClass, builder)
                            }
                        }
                    )
                }
            logInfo("Installed Reddit deleted-content OkHttp builder hook")
        }.onFailure { throwable ->
            logError("Failed to install Reddit deleted-content OkHttp builder hook", throwable)
        }
    }

    fun installCommentModelHook(mapperClass: Class<*>) {
        runCatching {
            val commentClass = mapperClass.classLoader.loadClass("com.reddit.domain.model.Comment")
            mapperClass.declaredMethods
                .filter { method ->
                    method.returnType == commentClass &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                val result = param.result ?: return
                                if (restoreDeletedCommentModel(result)) {
                                    logModelRestore("Restored deleted Reddit comment model via ${method.name}")
                                }
                            }
                        }
                    )
                }
            logInfo("Installed Reddit deleted-comment domain model hook")
        }.onFailure { throwable ->
            logError("Failed to install Reddit deleted-comment domain model hook", throwable)
        }
    }

    private fun addInterceptor(builderClass: Class<*>, builder: Any) {
        runCatching {
            val loader = builderClass.classLoader
            val interceptorClass = loader.loadClass("okhttp3.Interceptor")
            val interceptor = Proxy.newProxyInstance(loader, arrayOf(interceptorClass)) { _, method, args ->
                if (method.name != "intercept") return@newProxyInstance null
                val chain = args?.firstOrNull() ?: return@newProxyInstance null
                try {
                    intercept(loader, chain)
                } catch (throwable: Throwable) {
                    if (throwable is Error) throw throwable
                    logError("Reddit response interceptor recovered from proxy exception", throwable)
                    syntheticResponse(loader, chain, 599, "Purrfect interceptor recovered")
                }
            }
            val addInterceptor = builderClass.methods.firstOrNull { method ->
                method.name == "addInterceptor" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(interceptorClass)
            } ?: return@runCatching
            addInterceptor.invoke(builder, interceptor)
            logInfo("Attached Reddit response interceptor for deleted content/recommendations")
        }.onFailure { throwable ->
            logError("Failed to attach Reddit deleted-content response interceptor", throwable)
        }
    }

    private fun intercept(loader: ClassLoader, chain: Any): Any {
        val chainClass = chain.javaClass
        val request = chainClass.methods.first { it.name == "request" && it.parameterTypes.isEmpty() }.invoke(chain)
        val proceed = chainClass.methods.first { it.name == "proceed" && it.parameterTypes.size == 1 }
        val response = try {
            proceed.invoke(chain, request)
        } catch (throwable: InvocationTargetException) {
            val target = throwable.targetException
            if (target is IOException) {
                logInfo("Reddit upstream request ended before response: ${target.message}")
                return syntheticResponse(loader, chain, 499, target.message ?: "Canceled")
            }
            throw target
        }
        val state = RedditFeatureStateStore.current
        if (!state.restoreDeletedContent && !state.hideRecommendedCommunities) return response

        return runCatching {
            val body = response.javaClass.methods.firstOrNull { it.name == "body" && it.parameterTypes.isEmpty() }?.invoke(response)
                ?: return@runCatching response
            val contentType = body.javaClass.methods.firstOrNull { it.name == "contentType" && it.parameterTypes.isEmpty() }?.invoke(body)
            val mediaType = contentType?.toString().orEmpty()
            if (!mediaType.contains("json", ignoreCase = true)) return@runCatching response

            val bodyString = body.javaClass.methods.firstOrNull { it.name == "string" && it.parameterTypes.isEmpty() }?.invoke(body) as? String
                ?: return@runCatching response
            if (!bodyString.looksRelevant(state)) return@runCatching response.withBody(loader, bodyString, contentType)

            val patched = patchJsonBody(bodyString, state)
            if (patched == bodyString) response.withBody(loader, bodyString, contentType) else {
                logResponsePatch(state, bodyString, patched)
                response.withBody(loader, patched, contentType)
            }
        }.onFailure { throwable ->
            logError("Deleted-content interceptor failed; returning original response", throwable)
        }.getOrDefault(response)
    }

    private fun String.looksRelevant(state: RedditFeatureState): Boolean {
        return (state.restoreDeletedContent && (
            contains("[deleted]") ||
                contains("[removed]") ||
                contains("Comment deleted by user") ||
                contains("removed_by_category") ||
                contains("removedByCategory") ||
                contains("REMOVED", ignoreCase = true) ||
                contains("DELETED", ignoreCase = true)
            )) ||
            (state.hideRecommendedCommunities && containsRecommendationMarker())
    }

    private fun patchJsonBody(body: String, state: RedditFeatureState): String {
        val trimmed = body.trim()
        val changed = AtomicInteger(0)
        val root: Any = when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> return body
        }
        if (state.restoreDeletedContent) patchNode(root, changed)
        if (state.hideRecommendedCommunities) filterRecommendedCommunities(root, changed)
        return if (changed.get() > 0) root.toString() else body
    }

    private fun patchNode(node: Any?, changed: AtomicInteger) {
        when (node) {
            is JSONObject -> {
                maybeRestoreObject(node)?.let { replacement ->
                    mergeRestoredFields(node, replacement)
                    changed.incrementAndGet()
                }
                node.keys().asSequence().toList().forEach { key -> patchNode(node.opt(key), changed) }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) patchNode(node.opt(index), changed)
            }
        }
    }

    private fun maybeRestoreObject(node: JSONObject): JSONObject? {
        if (!isRemovedNode(node)) return null
        val type = contentType(node)
        val id = normalizedId(node, type) ?: return null
        return when (type) {
            ContentType.COMMENT -> commentCache.computeIfAbsent(id) { fetchArcticNode("comments", it) }
            ContentType.POST -> postCache.computeIfAbsent(id) { fetchArcticNode("posts", it) }
            null -> null
        }
    }

    private fun isRemovedNode(node: JSONObject): Boolean {
        val category = node.optString("removed_by_category", node.optString("removedByCategory", ""))
        if (category.isNotBlank() && category != "null") return true
        val reason = node.optString("removal_reason", node.optString("removalReason", ""))
        if (reason.isNotBlank() && reason != "null") return true
        return listOf("body", "bodyText", "text", "content", "selftext", "selfText", "title").any { key ->
            val value = node.optString(key, "")
            value == "[deleted]" ||
                value == "[removed]" ||
                value.equals("deleted", ignoreCase = true) ||
                value.equals("removed", ignoreCase = true) ||
                value.equals("Comment deleted by user", ignoreCase = true)
        }
    }

    private fun normalizedId(node: JSONObject, type: ContentType?): String? {
        val candidates = when (type) {
            ContentType.COMMENT -> listOf("id", "name", "commentId", "comment_id", "thingId", "thing_id")
            ContentType.POST -> listOf("id", "name", "postId", "post_id", "linkId", "link_id", "thingId", "thing_id")
            null -> listOf("id", "name", "thingId", "thing_id")
        }
        for (key in candidates) {
            val raw = node.optString(key, "")
            val id = raw.removeThingPrefix().takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun contentType(node: JSONObject): ContentType? {
        val typename = node.optString("__typename", node.optString("typename", "")).lowercase()
        if (typename.contains("comment")) return ContentType.COMMENT
        if (typename.contains("post") || typename.contains("submission") || typename.contains("link")) return ContentType.POST
        if (node.has("body") || node.has("bodyText") || node.has("parent_id") || node.has("parentId")) return ContentType.COMMENT
        if (node.has("selftext") || node.has("selfText") || node.has("url") || node.has("post_hint") || node.has("postHint")) return ContentType.POST
        return null
    }

    private fun fetchArcticNode(kind: String, id: String): JSONObject? {
        return runCatching {
            val encodedId = URLEncoder.encode(id, "UTF-8")
            val url = URL("${ARCTIC_SHIFT_BASE_URL}${kind}/ids?ids=$encodedId")
            logInfo("Arctic Shift request: $kind/$id")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Purrfect Reddit deleted-content hook")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val data = JSONObject(json).optJSONArray("data") ?: return@runCatching null
                data.optJSONObject(0)
            } finally {
                connection.disconnect()
            }
        }.onFailure { throwable ->
            logError("Failed Arctic Shift restore for $kind/$id", throwable)
        }.getOrNull()
    }

    private fun mergeRestoredFields(target: JSONObject, restored: JSONObject) {
        val emoji = removalEmoji(target)
        val body = restored.optString("body", "")
        val selfText = restored.optString("selftext", "")
        if (body.isNotBlank()) replaceRemovedStrings(target, "$emoji $body")
        if (selfText.isNotBlank()) replaceRemovedStrings(target, "$emoji $selfText")
        copyKeys(restored, target, "author", "author_fullname", "authorFlairText", "author_flair_text")
        copyKeys(restored, target, "title", "selftext", "selfText", "body", "url", "thumbnail", "domain", "preview", "media", "media_embed", "gallery_data", "post_hint")
        target.put("purrfect_restored_deleted_content", true)
        restoredNodes.incrementAndGet()
    }

    private fun replaceRemovedStrings(node: JSONObject, restoredText: String) {
        node.keys().asSequence().toList().forEach { key ->
            if (node.opt(key) is String) {
                val value = node.optString(key)
                if (value == "[deleted]" || value == "[removed]" || value.equals("deleted", true) || value.equals("removed", true)) {
                    node.put(key, restoredText)
                }
            }
        }
    }

    private fun copyKeys(source: JSONObject, target: JSONObject, vararg keys: String) {
        keys.forEach { key ->
            if (source.has(key) && !source.isNull(key)) target.put(key, source.get(key))
        }
    }

    private fun filterRecommendedCommunities(node: Any?, changed: AtomicInteger) {
        when (node) {
            is JSONObject -> {
                node.keys().asSequence().toList().forEach { key ->
                    val value = node.opt(key)
                    if (key.isRecommendationKey() || value.looksLikeRecommendationNode()) {
                        node.remove(key)
                        changed.incrementAndGet()
                        filteredCommunityNodes.incrementAndGet()
                    } else {
                        filterRecommendedCommunities(value, changed)
                    }
                }
            }
            is JSONArray -> {
                for (index in node.length() - 1 downTo 0) {
                    val value = node.opt(index)
                    if (value.looksLikeRecommendationNode()) {
                        node.remove(index)
                        changed.incrementAndGet()
                        filteredCommunityNodes.incrementAndGet()
                    } else {
                        filterRecommendedCommunities(value, changed)
                    }
                }
            }
        }
    }

    private fun String.containsRecommendationMarker(): Boolean {
        return contains("related_community", ignoreCase = true) ||
            contains("relatedCommunity", ignoreCase = true) ||
            contains("relatedCommunities", ignoreCase = true) ||
            contains("RelatedCommunities", ignoreCase = true) ||
            contains("communityRecommendations", ignoreCase = true) ||
            contains("CommunityRecommendations", ignoreCase = true) ||
            contains("community_recomendation_section_", ignoreCase = true) ||
            contains("community_recommendation_section_", ignoreCase = true) ||
            contains("RankedCommunityFeedElement", ignoreCase = true) ||
            contains("CarouselCommunityRecommendations", ignoreCase = true) ||
            contains("ListStyleCommunityRecommendations", ignoreCase = true) ||
            contains("CompactPostCommunityRecommendations", ignoreCase = true) ||
            contains("CardPostCommunityRecommendations", ignoreCase = true)
    }

    private fun String.isRecommendationKey(): Boolean {
        return contains("relatedCommunities", ignoreCase = true) ||
            contains("relatedCommunity", ignoreCase = true) ||
            contains("communityRecommendations", ignoreCase = true) ||
            contains("recommendedCommunities", ignoreCase = true)
    }

    private fun Any?.looksLikeRecommendationNode(): Boolean {
        if (this == null || this == JSONObject.NULL) return false
        if (this is String) return containsRecommendationMarker()
        if (this !is JSONObject && this !is JSONArray) return false
        val text = toString()
        if (!text.containsRecommendationMarker()) return false
        val lower = text.lowercase()
        return lower.contains("related") ||
            lower.contains("recommend") ||
            lower.contains("join_related_community") ||
            lower.contains("community_recomendation_section_") ||
            lower.contains("community_recommendation_section_")
    }

    private fun restoreDeletedCommentModel(comment: Any): Boolean {
        if (!RedditFeatureStateStore.current.restoreDeletedContent) return false
        return runCatching {
            val id = stringFromGetter(comment, "getId")?.removeThingPrefix() ?: return false
            val body = stringFromGetter(comment, "getBody").orEmpty()
            val author = stringFromGetter(comment, "getAuthor").orEmpty()
            val deleted = booleanFromGetter(comment, "getIsDeletedByRedditor") ||
                booleanFromGetter(comment, "isDeletedByRedditor") ||
                body == "[deleted]" ||
                body == "[removed]" ||
                author == "[deleted]"
            val removedCategory = runCatching {
                comment.javaClass.methods.firstOrNull { it.name == "getRemovedByCategory" && it.parameterTypes.isEmpty() }?.invoke(comment)
            }.getOrNull()
            if (!deleted && removedCategory == null) return false

            val restored = commentCache.computeIfAbsent(id) { fetchArcticNode("comments", it) } ?: return false
            val restoredBody = restored.optString("body", "").takeIf { it.isNotBlank() } ?: return false
            val restoredAuthor = restored.optString("author", "").takeIf { it.isNotBlank() && it != "[deleted]" }
            val text = "${removalEmoji(JSONObject().put("body", if (deleted) "[deleted]" else "[removed]"))} $restoredBody"
            setField(comment, "body", text)
            setField(comment, "bodyPreview", text)
            if (restoredAuthor != null) setField(comment, "author", restoredAuthor)
            setField(comment, "isDeletedByRedditor", false)
            setField(comment, "isRemoved", false)
            setField(comment, "removed", false)
            setField(comment, "removedByCategory", null)
            setField(comment, "deletedAccount", false)
            restoredCommentModels.incrementAndGet()
            true
        }.onFailure { throwable ->
            logError("Failed to restore deleted Reddit comment model", throwable)
        }.getOrDefault(false)
    }

    private fun stringFromGetter(target: Any, name: String): String? {
        return target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target) as? String
    }

    private fun booleanFromGetter(target: Any, name: String): Boolean {
        return target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target) as? Boolean ?: false
    }

    private fun setField(target: Any, name: String, value: Any?) {
        runCatching {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null) {
                val field = clazz.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    field.set(target, value)
                    return
                }
                clazz = clazz.superclass
            }
        }
    }

    private fun String.removeThingPrefix(): String {
        return substringAfter("t1_").substringAfter("t3_")
    }

    private fun removalEmoji(node: JSONObject): String {
        val category = node.optString("removed_by_category", node.optString("removedByCategory", "")).lowercase()
        val selfText = node.optString("selftext", node.optString("body", ""))
        return when {
            selfText == "[deleted]" -> "\uD83D\uDDD1\uFE0F"
            category == "moderator" -> "\uD83E\uDDF9"
            category == "reddit" -> "\uD83E\uDD16"
            category == "anti_evil_ops" -> "\uD83D\uDC7F"
            category == "copyright_takedown" -> "\u00A9\uFE0F"
            category == "content_takedown" || node.optString("removal_reason") == "legal" -> "\uD83D\uDEA8"
            else -> "\uD83D\uDDD1\uFE0F"
        }
    }

    private fun Any.withBody(loader: ClassLoader, bodyString: String, contentType: Any?): Any {
        val responseBodyClass = loader.loadClass("okhttp3.ResponseBody")
        val newBody = createResponseBody(responseBodyClass, bodyString, contentType)
        val builder = javaClass.methods.first { it.name == "newBuilder" && it.parameterTypes.isEmpty() }.invoke(this)
        builder.javaClass.methods.first { it.name == "body" && it.parameterTypes.size == 1 }.invoke(builder, newBody)
        return builder.javaClass.methods.first { it.name == "build" && it.parameterTypes.isEmpty() }.invoke(builder)
    }

    private fun syntheticResponse(loader: ClassLoader, chain: Any, code: Int, message: String): Any {
        val request = chain.javaClass.methods.first { it.name == "request" && it.parameterTypes.isEmpty() }.invoke(chain)
        val responseClass = loader.loadClass("okhttp3.Response")
        val responseBuilderClass = loader.loadClass("okhttp3.Response\$Builder")
        val protocolClass = loader.loadClass("okhttp3.Protocol")
        val responseBodyClass = loader.loadClass("okhttp3.ResponseBody")
        val protocol = protocolClass.enumConstants.firstOrNull { it.toString() == "http/1.1" }
            ?: protocolClass.enumConstants.first()
        val body = createResponseBody(responseBodyClass, "", null)
        val builder = responseBuilderClass.getConstructor().newInstance()
        builder.callSingleArg("request", request)
        builder.callSingleArg("protocol", protocol)
        builder.callSingleArg("code", code)
        builder.callSingleArg("message", message)
        builder.callSingleArg("body", body)
        return responseBuilderClass.methods.first { it.name == "build" && it.parameterTypes.isEmpty() }.invoke(builder)
            .also { responseClass.cast(it) }
    }

    private fun Any.callSingleArg(name: String, value: Any?) {
        javaClass.methods.first { method ->
            method.name == name && method.parameterTypes.size == 1
        }.invoke(this, value)
    }

    private fun createResponseBody(responseBodyClass: Class<*>, bodyString: String, contentType: Any?): Any {
        responseBodyClass.methods.firstOrNull { method ->
            method.name == "create" &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java
        }?.let { return it.invoke(null, bodyString, contentType) }

        val companion = responseBodyClass.getField("Companion").get(null)
        val method: Method = companion.javaClass.methods.first {
            it.name == "create" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java
        }
        return method.invoke(companion, bodyString, contentType)
    }

    private fun logBlock(message: String) {
        val count = restoredNodes.get()
        if (count <= 5 || count % 25 == 0) logInfo("$message (restored=$count)")
    }

    private fun logResponsePatch(state: RedditFeatureState, original: String, patched: String) {
        if (state.restoreDeletedContent && original != patched) logBlock("Patched Reddit JSON response")
        if (state.hideRecommendedCommunities && original.containsRecommendationMarker()) {
            val count = filteredCommunityNodes.get()
            if (count <= 5 || count % 25 == 0) logInfo("Filtered Reddit recommended/related community JSON nodes (removed=$count)")
        }
    }

    private fun logModelRestore(message: String) {
        val count = restoredCommentModels.get()
        if (count <= 5 || count % 25 == 0) logInfo("$message (models=$count)")
    }

    private fun logInfo(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
    }

    private fun logError(message: String, throwable: Throwable) {
        XposedBridge.log("[$TAG] $message: ${throwable.stackTraceToString()}")
        CoreLogger.xposedLog("$message: ${throwable.message}", TAG)
    }

    private enum class ContentType {
        COMMENT,
        POST
    }
}
