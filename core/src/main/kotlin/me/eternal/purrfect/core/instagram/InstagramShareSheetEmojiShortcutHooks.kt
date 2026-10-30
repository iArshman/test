package me.eternal.purrfect.core.instagram

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object InstagramShareSheetEmojiShortcutHooks {
    private const val PREF_NAME = "purrfect_instagram_prefs"
    private const val LEGACY_PREF_NAME = "instaeclipse_prefs"
    private const val PREF_KEY_PREFIX = "share_sheet_emoji_shortcuts:"
    private const val WRAPPER_TAG = "ie_share_sheet_shortcut_wrapper"
    private const val STRIP_TAG = "ie_share_sheet_shortcut_strip"
    private const val ADD_SHORTCUT_LABEL = "+"
    private const val CHIP_SIZE_DP = 48
    private const val STRIP_HEIGHT_DP = 64
    private const val STRIP_SIDE_PADDING_DP = 16
    private const val CHIP_MARGIN_END_DP = 10
    private const val MAX_ATTACH_ATTEMPTS = 4
    private const val ATTACH_RETRY_DELAY_MS = 180L

    private val installed = AtomicBoolean(false)
    private val states = Collections.synchronizedMap(WeakHashMap<Any, ShortcutStripState>())

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val fragmentClass = Class.forName(
                "instagram.features.direct.fragment.sharesheet.DirectShareSheetFragment",
                false,
                classLoader
            )
            XposedBridge.hookAllMethods(
                fragmentClass,
                "onViewCreated",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!InstagramFeatureStateStore.current.enableShareSheetEmojiShortcuts) return
                        val root = param.args.firstOrNull() as? View ?: return
                        root.post { attachStrip(param.thisObject, root, 1) }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                fragmentClass,
                "onDestroyView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        states.remove(param.thisObject)
                    }
                }
            )
            log("Installed share-sheet emoji shortcut hook")
        } catch (throwable: Throwable) {
            installed.set(false)
            log("Share-sheet emoji shortcut hook failed: ${throwable.message}")
        }
    }

    private fun attachStrip(fragment: Any?, root: View, attempt: Int) {
        if (fragment == null || !InstagramFeatureStateStore.current.enableShareSheetEmojiShortcuts) return
        try {
            val recyclerView = readField(fragment, "recyclerView") as? View
            if (recyclerView == null) {
                retryAttach(fragment, root, attempt)
                return
            }
            val parent = recyclerView.parent as? ViewGroup
            if (parent == null) {
                retryAttach(fragment, root, attempt)
                return
            }
            if (parent.findViewWithTag<View>(STRIP_TAG) != null || parent.findViewWithTag<View>(WRAPPER_TAG) != null) return

            val originalIndex = parent.indexOfChild(recyclerView).takeIf { it >= 0 } ?: return
            val originalParams = recyclerView.layoutParams ?: return
            val context = root.context

            val wrapper = LinearLayout(context).apply {
                tag = WRAPPER_TAG
                orientation = LinearLayout.VERTICAL
                layoutParams = originalParams
            }
            val strip = HorizontalScrollView(context).apply {
                tag = STRIP_TAG
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, STRIP_SIDE_PADDING_DP), dp(context, 8), dp(context, STRIP_SIDE_PADDING_DP), dp(context, 8))
            }
            strip.addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            parent.removeView(recyclerView)
            wrapper.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, STRIP_HEIGHT_DP)))
            wrapper.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            parent.addView(wrapper, originalIndex)

            ShortcutStripState(fragment, context, content).also {
                states[fragment] = it
                it.refresh()
            }
            log("Attached share-sheet shortcut strip")
        } catch (throwable: Throwable) {
            log("Share-sheet shortcut strip attach failed: ${throwable.message}")
        }
    }

    private fun retryAttach(fragment: Any, root: View, attempt: Int) {
        if (attempt >= MAX_ATTACH_ATTEMPTS) return
        root.postDelayed({ attachStrip(fragment, root, attempt + 1) }, ATTACH_RETRY_DELAY_MS)
    }

    private class ShortcutStripState(fragment: Any, private val context: Context, private val content: LinearLayout) {
        private val fragmentRef = WeakReference(fragment)
        private val preferenceKey = buildPreferenceKey(fragment)

        fun refresh() {
            content.removeAllViews()
            val addChip = createChip(context, ADD_SHORTCUT_LABEL).apply {
                contentDescription = "Create emoji shortcut"
                setOnClickListener { promptCreateShortcut() }
            }
            content.addView(addChip, chipLayoutParams(context))

            loadShortcuts(context, preferenceKey).forEach { shortcut ->
                val chip = createChip(context, shortcut.emoji).apply {
                    contentDescription = "Send to ${shortcut.describeMembers()}"
                    setOnClickListener { applyShortcut(shortcut) }
                    setOnLongClickListener {
                        showShortcutActions(shortcut)
                        true
                    }
                }
                content.addView(chip, chipLayoutParams(context))
            }
        }

        private fun promptCreateShortcut() {
            val fragment = fragmentRef.get() ?: return
            val members = membersFromCurrentSelection(fragment)
            if (members.isEmpty()) {
                toast(context, "Select one or more people first")
                return
            }
            val activity = resolveDialogActivity(fragment, context)
            if (!isUsableActivity(activity)) {
                toast(context, "Could not open shortcut editor")
                return
            }
            val input = EditText(context).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Emoji"
                gravity = Gravity.CENTER
                textSize = 24f
                setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12))
            }
            AlertDialog.Builder(activity)
                .setTitle("Create emoji shortcut")
                .setMessage("This shortcut will send to: ${describeMembers(members)}")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val emoji = normalizeEmoji(input.text?.toString().orEmpty())
                    if (emoji.isBlank()) {
                        toast(context, "Enter an emoji")
                        return@setPositiveButton
                    }
                    upsertShortcut(context, preferenceKey, Shortcut(emoji, members))
                    refresh()
                    toast(context, "Shortcut saved")
                }
                .show()
        }

        private fun showShortcutActions(shortcut: Shortcut) {
            val fragment = fragmentRef.get() ?: return
            val activity = resolveDialogActivity(fragment, context)
            if (!isUsableActivity(activity)) {
                toast(context, "Could not open shortcut options")
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("Shortcut ${shortcut.emoji}")
                .setMessage("Users:\n${describeMembersMultiline(shortcut.members)}")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Update from selected") { _, _ ->
                    val updatedMembers = membersFromCurrentSelection(fragment)
                    if (updatedMembers.isEmpty()) {
                        toast(context, "Select one or more people first")
                        return@setNeutralButton
                    }
                    upsertShortcut(context, preferenceKey, Shortcut(shortcut.emoji, updatedMembers))
                    refresh()
                    toast(context, "Shortcut updated")
                }
                .setPositiveButton("Delete") { _, _ ->
                    deleteShortcut(context, preferenceKey, shortcut.emoji)
                    refresh()
                    toast(context, "Shortcut deleted")
                }
                .show()
        }

        private fun applyShortcut(shortcut: Shortcut) {
            val fragment = fragmentRef.get() ?: return
            try {
                val adapter = getAdapter(fragment) ?: return
                val currentSelection = selectedTargets(adapter)
                val availableTargets = availableTargetsByMemberId(adapter)
                val desiredTargets = mutableListOf<Any>()
                val missingIds = mutableListOf<String>()
                shortcut.members.forEach { member ->
                    val target = availableTargets[member.id] ?: buildTargetFromCachedUser(fragment, adapter, member.id)
                    if (target == null) missingIds += member.id else desiredTargets += target
                }
                if (desiredTargets.isEmpty()) {
                    toast(context, "Shortcut recipients are not available yet")
                    return
                }
                val desiredIds = desiredTargets.mapNotNull { singleRecipientId(it) }.toSet()
                currentSelection.toList().forEach { selectedTarget ->
                    val selectedId = singleRecipientId(selectedTarget)
                    if (selectedId == null || selectedId !in desiredIds) toggleNativeSelection(adapter, selectedTarget)
                }
                val selectedIdsAfterClear = selectedIds(selectedTargets(adapter)).toMutableSet()
                desiredTargets.forEach { desiredTarget ->
                    val desiredId = singleRecipientId(desiredTarget)
                    if (desiredId != null && desiredId !in selectedIdsAfterClear) {
                        toggleNativeSelection(adapter, desiredTarget)
                        selectedIdsAfterClear += desiredId
                    }
                }
                toast(
                    context,
                    if (missingIds.isEmpty()) "Selected ${desiredTargets.size} people"
                    else "Selected ${desiredTargets.size} of ${shortcut.members.size} people"
                )
            } catch (throwable: Throwable) {
                log("Apply shortcut failed: ${throwable.message}")
                toast(context, "Could not apply shortcut")
            }
        }
    }

    private fun getAdapter(fragment: Any): Any? {
        return runCatching { invokeStaticNamed(fragment.javaClass, "A03", fragment) }.getOrNull()
    }

    private fun selectedTargets(adapter: Any): List<Any> {
        runCatching { invokeNamed(adapter, "A0f") }.getOrNull()?.let { value ->
            if (value is List<*>) return value.filterNotNull()
        }
        readCollectionFieldIfPresent(adapter, "A0F")?.takeIf { containsOnlyDirectShareTargets(it) }?.let { return it }
        return discoverSelectedTargets(adapter)
    }

    private fun selectedIds(targets: List<Any>): Set<String> = targets.mapNotNull { singleRecipientId(it) }.toSet()

    private fun toggleNativeSelection(adapter: Any, target: Any) {
        runCatching {
            invokeNamed(adapter, "A0j", target, 6, 0, 0)
            return
        }
        val toggleMethod = findToggleSelectionMethod(adapter, target)
            ?: error("No native selection toggle method found on ${adapter.javaClass.name}")
        toggleMethod.invoke(adapter, target, 6, 0, 0)
    }

    private fun membersFromCurrentSelection(fragment: Any): List<Member> {
        val adapter = getAdapter(fragment) ?: return emptyList()
        val members = linkedMapOf<String, Member>()
        selectedTargets(adapter).forEach { target ->
            val id = singleRecipientId(target) ?: return@forEach
            members[id] = Member(id, displayLabel(target, id))
        }
        return members.values.toList()
    }

    private fun availableTargetsByMemberId(adapter: Any): Map<String, Any> {
        val targets = linkedMapOf<String, Any>()
        allKnownTargets(adapter).forEach { target ->
            val id = singleRecipientId(target) ?: return@forEach
            targets.putIfAbsent(id, target)
        }
        return targets
    }

    private fun allKnownTargets(adapter: Any): List<Any> {
        val targets = mutableListOf<Any>()
        listOf("A0F", "A0R", "A0H", "A0L", "A0I", "A0N", "A0G", "A0M", "A0Q").forEach { field ->
            addTargets(targets, readCollectionField(adapter, field))
        }
        addTargets(targets, discoverAllDirectShareTargets(adapter))
        return targets
    }

    private fun readCollectionField(owner: Any, fieldName: String): List<Any> {
        val value = readField(owner, fieldName) as? Iterable<*> ?: return emptyList()
        return value.filterNotNull()
    }

    private fun readCollectionFieldIfPresent(owner: Any, fieldName: String): List<Any>? {
        val value = readField(owner, fieldName) ?: return null
        return (value as? Iterable<*>)?.filterNotNull() ?: emptyList()
    }

    private fun addTargets(destination: MutableList<Any>, source: List<Any>) {
        source.forEach { item ->
            if (item !in destination) destination += item
        }
    }

    private fun singleRecipientId(target: Any): String? {
        val ids = runCatching { invokeNamed(target, "A0D") as? List<*> }.getOrNull() ?: return null
        if (ids.size != 1) return null
        return ids.firstOrNull()?.toString()
    }

    private fun displayLabel(target: Any, fallback: String): String {
        return (readField(target, "A0L") as? String)?.takeIf { it.isNotBlank() }
            ?: (readField(target, "A0M") as? String)?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun buildTargetFromCachedUser(fragment: Any, adapter: Any, memberId: String): Any? {
        return runCatching {
            val viewModel = invokeStaticNamed(fragment.javaClass, "A04", fragment) ?: return@runCatching null
            val userCache = readField(viewModel, "A0E") ?: return@runCatching null
            val user = invokeNamed(userCache, "A04", memberId) ?: return@runCatching null
            invokeStaticNamed(viewModel.javaClass, "A01", viewModel, user, ArrayList(allKnownTargets(adapter)))
        }.getOrNull()
    }

    private fun buildPreferenceKey(fragment: Any): String {
        val userId = runCatching {
            val session = invokeNamed(fragment, "getSession")
            readField(session, "userId") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "default"
        return "$PREF_KEY_PREFIX$userId"
    }

    private fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun legacyPreferences(context: Context): SharedPreferences = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)

    private fun loadShortcuts(context: Context, preferenceKey: String): List<Shortcut> {
        val raw = preferences(context).getString(preferenceKey, null)
            ?: legacyPreferences(context).getString(preferenceKey, null)
            ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val shortcuts = mutableListOf<Shortcut>()
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val emoji = normalizeEmoji(item.optString("emoji", ""))
                if (emoji.isBlank()) continue
                val members = linkedMapOf<String, Member>()
                val memberArray = item.optJSONArray("members") ?: JSONArray()
                for (j in 0 until memberArray.length()) {
                    val member = memberArray.optJSONObject(j) ?: continue
                    val id = member.optString("id", "").trim()
                    if (id.isBlank()) continue
                    members[id] = Member(id, member.optString("label", id))
                }
                if (members.isNotEmpty()) shortcuts += Shortcut(emoji, members.values.toList())
            }
            shortcuts
        }.getOrElse {
            log("Load shortcuts failed: ${it.message}")
            emptyList()
        }
    }

    private fun upsertShortcut(context: Context, preferenceKey: String, replacement: Shortcut) {
        val shortcuts = loadShortcuts(context, preferenceKey).toMutableList()
        val index = shortcuts.indexOfFirst { it.emoji == replacement.emoji }
        if (index >= 0) shortcuts[index] = replacement else shortcuts += replacement
        saveShortcuts(context, preferenceKey, shortcuts)
    }

    private fun deleteShortcut(context: Context, preferenceKey: String, emoji: String) {
        saveShortcuts(context, preferenceKey, loadShortcuts(context, preferenceKey).filterNot { it.emoji == emoji })
    }

    private fun saveShortcuts(context: Context, preferenceKey: String, shortcuts: List<Shortcut>) {
        val array = JSONArray()
        shortcuts.forEach { shortcut ->
            val item = JSONObject()
            item.put("emoji", shortcut.emoji)
            val members = JSONArray()
            shortcut.members.forEach { member ->
                members.put(JSONObject().put("id", member.id).put("label", member.label))
            }
            item.put("members", members)
            array.put(item)
        }
        val value = array.toString()
        preferences(context).edit().putString(preferenceKey, value).apply()
        legacyPreferences(context).edit().putString(preferenceKey, value).apply()
    }

    private fun normalizeEmoji(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.codePointCount(0, trimmed.length) <= 8) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 8))
    }

    private fun createChip(context: Context, label: String): View = ShortcutChipView(context, label, label == ADD_SHORTCUT_LABEL)

    private fun chipLayoutParams(context: Context) = LinearLayout.LayoutParams(dp(context, CHIP_SIZE_DP), dp(context, CHIP_SIZE_DP)).apply {
        marginEnd = dp(context, CHIP_MARGIN_END_DP)
    }

    private fun describeMembers(members: List<Member>): String = TextUtils.join(", ", members.map { it.label })

    private fun describeMembersMultiline(members: List<Member>): String = TextUtils.join("\n", members.map { "- ${it.label}" })

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun resolveDialogActivity(fragment: Any, fallbackContext: Context): Activity? {
        val activity = runCatching { invokeNamed(fragment, "getActivity") as? Activity }.getOrNull()
        return activity ?: activityFromContext(fallbackContext)
    }

    private fun activityFromContext(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun isUsableActivity(activity: Activity?): Boolean {
        return activity != null &&
            !activity.isFinishing &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed) &&
            activity.window?.decorView?.windowToken != null
    }

    private fun discoverSelectedTargets(adapter: Any): List<Any> {
        allFields(adapter.javaClass).forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(adapter)
            }.getOrNull() as? Set<*> ?: return@forEach
            val targets = directShareTargetsFrom(value)
            if (targets.isNotEmpty() && containsOnlyDirectShareTargets(targets)) return targets
        }
        return emptyList()
    }

    private fun discoverAllDirectShareTargets(adapter: Any): List<Any> {
        val targets = mutableListOf<Any>()
        allFields(adapter.javaClass).forEach { field ->
            val iterable = runCatching {
                field.isAccessible = true
                field.get(adapter)
            }.getOrNull() as? Iterable<*> ?: return@forEach
            addTargets(targets, directShareTargetsFrom(iterable))
        }
        return targets
    }

    private fun directShareTargetsFrom(iterable: Iterable<*>): List<Any> = iterable.filterNotNull().filter { isDirectShareTarget(it) }

    private fun containsOnlyDirectShareTargets(targets: List<Any>): Boolean = targets.isNotEmpty() && targets.all { isDirectShareTarget(it) }

    private fun isDirectShareTarget(value: Any): Boolean = value.javaClass.name == "com.instagram.model.direct.DirectShareTarget"

    private fun findToggleSelectionMethod(adapter: Any, target: Any): Method? {
        return allMethods(adapter.javaClass).firstOrNull { method ->
            val params = method.parameterTypes
            if (params.size != 4) return@firstOrNull false
            if (!params[0].isAssignableFrom(target.javaClass)) return@firstOrNull false
            if (params[1] != Int::class.javaPrimitiveType || params[2] != Int::class.javaPrimitiveType || params[3] != Int::class.javaPrimitiveType) return@firstOrNull false
            val returnType = method.returnType
            val matches = returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java
            if (matches) method.isAccessible = true
            matches
        }
    }

    private fun readField(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return allFields(owner.javaClass).firstOrNull { it.name == name }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
        }
    }

    private fun invokeNamed(owner: Any, name: String, vararg args: Any?): Any? {
        val method = allMethods(owner.javaClass).firstOrNull { it.name == name && parametersMatch(it.parameterTypes, args) }
            ?: error("Method $name(${args.size}) not found on ${owner.javaClass.name}")
        method.isAccessible = true
        return method.invoke(owner, *args)
    }

    private fun invokeStaticNamed(type: Class<*>, name: String, vararg args: Any?): Any? {
        val method = allMethods(type).firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) && parametersMatch(it.parameterTypes, args)
        } ?: error("Static method $name(${args.size}) not found on ${type.name}")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val arg = args[index] ?: return@all !types[index].isPrimitive
            boxedType(types[index]).isAssignableFrom(arg.javaClass)
        }
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

    private fun allFields(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields
            current = current.superclass
        }
        return fields
    }

    private fun allMethods(type: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods
            current = current.superclass
        }
        return methods
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
    }

    private fun sp(context: Context, value: Int): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), context.resources.displayMetrics)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun log(message: String) {
        XposedBridge.log("[${InstagramFeatureState.TAG}|ShareEmoji] $message")
    }

    private class ShortcutChipView(context: Context, rawLabel: String, private val addButton: Boolean) : View(context) {
        private val label = rawLabel.ifBlank { if (addButton) ADD_SHORTCUT_LABEL else "?" }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            textSize = sp(context, if (addButton) 32 else 28)
        }

        init {
            isClickable = true
            isFocusable = true
            minimumWidth = dp(context, CHIP_SIZE_DP)
            minimumHeight = dp(context, CHIP_SIZE_DP)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = dp(context, CHIP_SIZE_DP)
            setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fillAlpha = if (isPressed) 0x22 else 0x12
            val strokeAlpha = if (isPressed) 0x55 else 0x33
            fillPaint.color = withAlpha(Color.WHITE, fillAlpha)
            strokePaint.color = withAlpha(Color.WHITE, strokeAlpha)
            val strokeHalf = strokePaint.strokeWidth / 2f
            val radius = minOf(width, height) / 2f - strokeHalf
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            val metrics = textPaint.fontMetrics
            canvas.drawText(label, cx, cy - (metrics.ascent + metrics.descent) / 2f, textPaint)
        }
    }

    private data class Shortcut(val emoji: String, val members: List<Member>) {
        fun describeMembers(): String = InstagramShareSheetEmojiShortcutHooks.describeMembers(members)
    }

    private data class Member(val id: String, val label: String)
}
