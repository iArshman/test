package me.eternal.purrfect.ui.manager.pages.features

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.config.*
import me.eternal.purrfect.common.config.FeatureNotice
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.github.skydoves.colorpicker.compose.*
import me.eternal.purrfect.common.ui.TopBarActionButton
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.toWrapper
import me.eternal.purrfect.common.theme.SnapchatThemeMappedSurface
import me.eternal.purrfect.common.theme.SnapchatThemeSurfaceMap
import me.eternal.purrfect.common.theme.SnapchatThemeSurfaceMapCodec
import me.eternal.purrfect.core.features.impl.experiments.RandomizedDeviceProfile
import me.eternal.purrfect.core.features.impl.experiments.RandomizedDeviceProfileStore
import me.eternal.purrfect.core.whatsapp.WhatsAppUiElementSelector
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.manager.theme.aetherGlass
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.util.*
import me.eternal.purrfect.ui.util.Dialog
import me.eternal.purrfect.ui.util.DialogProperties
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FeaturesRootSection : Routes.Route() {
    private var showResetConfirmationDialog by mutableStateOf(false)
    private var showExportDialog by mutableStateOf(false)
    private val snapchatThemeGlobalBackgroundKeys = setOf(
        "background",
        "appbackground",
        "valdi.token.backgroundmain",
        "valdi.token.backgroundsubscreen",
        "android.attr.colorbackground",
        "android.attr.windowbackground",
        "android.attr.0x1010031",
        "android.attr.0x1010054"
    )

    override val title: @Composable (() -> Unit)? = @Composable {
        val navBackStackEntry by routes.navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val titleText = when (currentDestination?.route) {
            FEATURE_CONTAINER_ROUTE -> {
                navBackStackEntry?.arguments?.getString("name")?.let { containerName ->
                    allContainers[containerName]?.let {
                        context.translation[it.key.propertyName()]
                    }
                } ?: routeInfo.translatedKey?.value
            }
            SEARCH_FEATURE_ROUTE -> {
                translation["search_button"] ?: "Search"
            }
            else -> {
                routeInfo.translatedKey?.value
            }
        }
        Text(titleText ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    internal val alertDialogs by lazy { AlertDialogs(context.translation) }
    internal val gson by lazy { Gson() }
    internal val listTypeToken = object : TypeToken<List<String>>() {}.type

    companion object {
        const val FEATURE_CONTAINER_ROUTE = "feature_container/{name}"
        const val SEARCH_FEATURE_ROUTE = "search_feature/{keyword}"
        const val INSTAGRAM_EMOJI_FONT_URI = "content://me.eternal.purrfect.instagram.emoji/font"
    }

    internal fun featureRootContainer(): ConfigContainer {
        return when {
            context.isInstagramMode -> context.config.root.instagram
            context.isWhatsAppMode -> context.config.root.whatsapp
            context.isRedditMode -> context.config.root.reddit
            else -> context.config.root
        }
    }

    internal val allContainers: Map<String, PropertyPair<*>>
        get() {
        val containers = mutableMapOf<String, PropertyPair<*>>()
        fun queryContainerRecursive(container: ConfigContainer) {
            container.properties.forEach {
                if (
                    it.key.dataType.type == DataProcessors.Type.CONTAINER &&
                    isVisibleForCurrentTarget(container, it.key)
                ) {
                    containers[it.key.name] = (it.key to it.value).toPropertyPair() as PropertyPair<Any>
                    queryContainerRecursive(it.value.get() as ConfigContainer)
                }
            }
        }
        queryContainerRecursive(featureRootContainer())
        return containers
    }

    internal val allProperties: Map<PropertyKey<*>, PropertyValue<*>>
        get() {
        val properties = mutableMapOf<PropertyKey<*>, PropertyValue<*>>()
        allContainers.values.forEach {
            val container = it.value.get() as ConfigContainer
            container.properties.forEach { property ->
                properties[property.key] = property.value
            }
        }
        return properties
    }

    internal fun isSearchVisibleProperty(propertyKey: PropertyKey<*>): Boolean {
        return !propertyKey.params.flags.contains(ConfigFlag.HIDDEN)
    }

    internal fun isVisibleForCurrentTarget(container: ConfigContainer, propertyKey: PropertyKey<*>): Boolean {
        if (propertyKey.params.flags.contains(ConfigFlag.HIDDEN)) return false
        if (
            !context.isLimitedTargetMode &&
            container === context.config.root &&
            propertyKey.name in setOf("reddit", "whatsapp", "instagram")
        ) return false
        return true
    }

    internal fun getFolderReadablePath(context: android.content.Context, folderUri: String?): String? {
        if (folderUri == null) return null
        return try {
            val uri = android.net.Uri.parse(folderUri)
            val path = uri.path ?: return folderUri
            if (path.contains("tree/")) {
                path.substringAfter("tree/").replace("primary:", "Internal Storage/").replace(":", "/")
            } else {
                folderUri
            }
        } catch (e: Exception) {
            folderUri
        }
    }

    internal fun isRandomizedProfileEnabled(): Boolean {
        return context.config.root.experimental.spoof.randomizeDeviceProfile.globalState == true
    }

    internal fun requestFreshRandomizedProfile() {
        val randomizeConfig = context.config.root.experimental.spoof.randomizeDeviceProfile
        val newToken = UUID.randomUUID().toString()
        
        val newProfile = RandomizedDeviceProfileStore.getOrCreate(context.androidContext, context.log, newToken)
        val profileJsonString = newProfile.toJson().toString()
        
        randomizeConfig.profileGenerationToken.set(newToken)
        randomizeConfig.currentProfileSnapshot.set(newProfile.toJson().toString(2))
        randomizeConfig.profileData.set(profileJsonString)
        
        context.config.writeConfig()
        
        context.androidContext.getSharedPreferences("purrfect_spoof", 0)
            .edit()
            .putString("randomized_device_profile", profileJsonString)
            .putString("randomized_device_profile_token", newToken)
            .putString("android_id", newProfile.androidId)
            .putString("advertising_id", newProfile.advertisingId)
            .putString("bluetooth_address", newProfile.bluetoothMacAddress)
            .putString("gsf_id", newProfile.gsfId)
            .putString("random_device", newProfile.deviceInfo.model)
            .putString("device_fingerprint", newProfile.buildFingerprint)
            .apply()
    }

    internal fun getRandomizedProfileSnapshot(): String {
        val legacyPrefs = context.androidContext.getSharedPreferences("purrfect_spoof", 0)
        val hookedProfileJson = legacyPrefs.getString("randomized_device_profile", null)
        
        if (!hookedProfileJson.isNullOrBlank()) {
            return hookedProfileJson
        }

        val configProfileJson = context.config.root.experimental.spoof.randomizeDeviceProfile.profileData.getNullable()
        if (!configProfileJson.isNullOrBlank()) {
            return configProfileJson
        }

        return context.translation["manager.dialogs.randomize_device_profile.empty"]
            ?: "No generated profile is available yet. Enable the feature in Snapchat first."
    }

    internal fun isRandomizedProfileActionProperty(propertyName: String): Boolean {
        return propertyName == "generate_fresh_profile_action" ||
            propertyName == "view_current_profile_action" ||
            propertyName == "backup_profile_action" ||
            propertyName == "restore_profile_action"
    }

    internal data class HiddenUiElementEntry(
        val value: String,
        val label: String,
        val isSelector: Boolean
    )

    internal data class SnapchatThemeCatalogEntry(
        val key: String,
        val title: String,
        val description: String,
        val defaultColor: Int = 0xFF000000.toInt(),
        val category: String = "Built-in",
        val source: String = "builtin",
        val hitCount: Int = 0,
        val lastSeenMs: Long = 0L,
        val attributeName: String? = null,
        val className: String? = null,
        val resourceName: String? = null,
        val selector: String? = null,
        val selectorDisplayName: String? = null,
        val screenHint: String? = null,
        val role: String? = null,
        val screenBounds: String? = null,
        val localBounds: String? = null,
        val valueSummary: String? = null,
        val confidence: Float = 0f,
        val details: Map<String, String> = emptyMap()
    ) {
        val searchKey: String = listOf(
            title,
            description,
            key,
            category,
            source,
            attributeName.orEmpty(),
            className.orEmpty(),
            resourceName.orEmpty(),
            selector.orEmpty(),
            selectorDisplayName.orEmpty(),
            screenHint.orEmpty(),
            role.orEmpty(),
            screenBounds.orEmpty(),
            localBounds.orEmpty(),
            valueSummary.orEmpty(),
            confidence.toString(),
            details.entries.joinToString(" ") { "${it.key} ${it.value}" }
        ).joinToString(" ").lowercase(Locale.US)
    }

    internal data class SnapchatThemeCatalogState(
        val entries: List<SnapchatThemeCatalogEntry>,
        val mappedSurfaceCount: Int,
        val uniqueHash: Long,
        val snapchatVersionLabel: String,
        val updatedAtMs: Long
    )

    internal data class SnapchatThemeSurfaceRule(
        val target: String,
        val isSelector: Boolean,
        val label: String,
        val color: Int
    )

    internal data class SnapchatThemeSurfaceOverride(
        val key: String,
        val label: String,
        val color: Int
    )

    internal data class SnapchatThemeBackgroundRule(
        val target: String,
        val isSelector: Boolean,
        val label: String,
        val path: String,
        val name: String
    )

    internal data class SnapchatThemeColorEditRequest(
        val target: String,
        val isSelector: Boolean,
        val label: String,
        val currentColor: Int,
        val isCatalogSurface: Boolean
    )

    internal data class HiddenUiIdCatalogEntry(
        val name: String,
        val hexId: String
    ) {
        val searchKey: String = "$name $hexId".lowercase()
        val displayTitle: String = if (hexId.isBlank()) name else "$name\n$hexId"
    }

    internal fun hiddenUiElementEntries(
        catalog: List<HiddenUiIdCatalogEntry> = emptyList()
    ): List<HiddenUiElementEntry> {
        val catalogByName = catalog.associateBy { it.name }
        val rawIds: String
        val rawSelectors: String
        when {
            context.isInstagramMode -> {
                val uiElements = context.config.root.instagram.hiddenUiElements
                rawIds = uiElements.hiddenUiElementIds.getNullable().orEmpty()
                rawSelectors = uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
            }
            context.isWhatsAppMode -> {
                val uiElements = context.config.root.whatsapp.uiElements
                rawIds = uiElements.hiddenUiElementIds.getNullable().orEmpty()
                rawSelectors = uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
            }
            else -> {
                val uiElements = context.config.root.userInterface.uiElements
                rawIds = uiElements.hiddenUiElementIds.getNullable().orEmpty()
                rawSelectors = uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
            }
        }
        val ids = splitHiddenUiElementValues(rawIds)
            .map(::normalizeHiddenUiElementId)
            .filter { it.isNotEmpty() }
            .distinct()
        val selectors = splitHiddenUiElementValues(rawSelectors)
            .map(::normalizeHiddenUiElementSelector)
            .filter { it.isNotEmpty() }
            .distinct()

        return buildList {
            ids.forEach { id ->
                add(HiddenUiElementEntry(value = id, label = catalogByName[id]?.displayTitle ?: id, isSelector = false))
            }
            selectors.forEach { selector ->
                add(
                    HiddenUiElementEntry(
                        value = selector,
                        label = WhatsAppUiElementSelector.toDisplayName(selector),
                        isSelector = true
                    )
                )
            }
        }
    }

    internal fun selectedHiddenUiElementIds(): List<String> {
        val rawIds = when {
            context.isInstagramMode -> context.config.root.instagram.hiddenUiElements.hiddenUiElementIds.getNullable().orEmpty()
            context.isWhatsAppMode -> context.config.root.whatsapp.uiElements.hiddenUiElementIds.getNullable().orEmpty()
            else -> context.config.root.userInterface.uiElements.hiddenUiElementIds.getNullable().orEmpty()
        }
        return splitHiddenUiElementValues(rawIds).map(::normalizeHiddenUiElementId).filter { it.isNotEmpty() }.distinct()
    }

    internal fun selectedHiddenUiElementSelectors(): List<String> {
        val rawSelectors = when {
            context.isInstagramMode -> context.config.root.instagram.hiddenUiElements.hiddenUiElementSelectors.getNullable().orEmpty()
            context.isWhatsAppMode -> context.config.root.whatsapp.uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
            else -> context.config.root.userInterface.uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
        }
        return splitHiddenUiElementValues(rawSelectors).map(::normalizeHiddenUiElementSelector).filter { it.isNotEmpty() }.distinct()
    }

    internal fun loadInstagramHiddenUiIdCatalog(): List<HiddenUiIdCatalogEntry> {
        return runCatching {
            context.androidContext.assets.open("instagram_view_ids.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { rawLine ->
                    val line = rawLine.removePrefix("\uFEFF")
                    val parts = line.split('\t', limit = 2)
                    val name = parts.getOrNull(0)?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    HiddenUiIdCatalogEntry(
                        name = name,
                        hexId = parts.getOrNull(1)?.trim().orEmpty()
                    )
                }.toList()
            }
        }.onFailure {
            context.log.warn("Failed to load Instagram hidden UI element catalog: ${it.message}")
        }.getOrDefault(emptyList())
    }

    internal fun splitHiddenUiElementValues(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    internal fun normalizeHiddenUiElementId(rawId: String): String {
        var clean = rawId.trim()
        if (clean.isEmpty()) return ""
        clean = clean.substringBefore('\t').trim()
        clean = clean.substringBefore(' ').trim()
        val slashIndex = clean.lastIndexOf('/')
        if (slashIndex >= 0 && slashIndex < clean.length - 1) clean = clean.substring(slashIndex + 1).trim()
        val dotIndex = clean.lastIndexOf(".id.")
        if (dotIndex >= 0 && dotIndex + 4 < clean.length) clean = clean.substring(dotIndex + 4).trim()
        if (isObfuscatedResourcePlaceholder(clean)) return ""
        return clean
    }

    internal fun isObfuscatedResourcePlaceholder(value: String): Boolean {
        return value.endsWith("_resource_name_obfuscated") ||
            value == "0" ||
            value.matches(Regex("\\d+_resource_name_obfuscated"))
    }

    internal fun normalizeHiddenUiElementSelector(rawSelector: String): String {
        val clean = rawSelector.trim()
        return if (clean.startsWith("selector:v1|")) clean else ""
    }

    internal fun persistHiddenUiElementValues(
        ids: List<String>,
        selectors: List<String>,
        onConfigChanged: () -> Unit
    ) {
        when {
            context.isInstagramMode -> {
                val uiElements = context.config.root.instagram.hiddenUiElements
                uiElements.hiddenUiElementIds.set(ids.joinToString("\n"))
                uiElements.hiddenUiElementSelectors.set(selectors.joinToString("\n"))
            }
            context.isWhatsAppMode -> {
                val uiElements = context.config.root.whatsapp.uiElements
                uiElements.hiddenUiElementIds.set(ids.joinToString("\n"))
                uiElements.hiddenUiElementSelectors.set(selectors.joinToString("\n"))
            }
            else -> {
                val uiElements = context.config.root.userInterface.uiElements
                uiElements.hiddenUiElementIds.set(ids.joinToString("\n"))
                uiElements.hiddenUiElementSelectors.set(selectors.joinToString("\n"))
            }
        }
        context.config.writeConfig()
        context.mirrorWhatsAppFeaturePrefs()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun removeHiddenUiElement(entry: HiddenUiElementEntry, onConfigChanged: () -> Unit) {
        val ids = selectedHiddenUiElementIds()
        val selectors = selectedHiddenUiElementSelectors()

        persistHiddenUiElementValues(
            ids = if (entry.isSelector) ids else ids.filterNot { it == entry.value },
            selectors = if (entry.isSelector) selectors.filterNot { it == entry.value } else selectors,
            onConfigChanged = onConfigChanged
        )
    }

    internal fun setHiddenUiElementHidden(idOrSelector: String, hidden: Boolean, onConfigChanged: () -> Unit) {
        val isSelector = WhatsAppUiElementSelector.isSelector(idOrSelector)
        val value = if (isSelector) normalizeHiddenUiElementSelector(idOrSelector) else normalizeHiddenUiElementId(idOrSelector)
        if (value.isEmpty()) return
        val ids = selectedHiddenUiElementIds().toMutableList()
        val selectors = selectedHiddenUiElementSelectors().toMutableList()
        val target = if (isSelector) selectors else ids
        if (hidden) {
            if (target.none { it == value }) target += value
        } else {
            target.removeAll { it == value }
        }
        persistHiddenUiElementValues(ids, selectors, onConfigChanged)
    }

    internal fun clearHiddenUiElements(onConfigChanged: () -> Unit) {
        persistHiddenUiElementValues(
            ids = emptyList(),
            selectors = emptyList(),
            onConfigChanged = onConfigChanged
        )
    }

    internal fun snapchatThemeCatalog(): List<SnapchatThemeCatalogEntry> {
        return listOf(
            SnapchatThemeCatalogEntry(
                key = "background",
                title = "App background",
                description = "Root page surfaces and generic background fallbacks"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundmain",
                title = "Main pages",
                description = "Camera, Chat, Stories, Spotlight, and Map base pages"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundsubscreen",
                title = "Subscreens",
                description = "Secondary pages opened from Chat, Profile, Spotlight, and settings"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.attr.background",
                title = "Composer background attribute",
                description = "Generic Valdi/Composer background attribute"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.attr.backgroundcolor",
                title = "Composer background color",
                description = "Explicit Composer backgroundColor values"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundsurface",
                title = "Sheets and panels",
                description = "Bottom sheets, popups, modal panels, and raised containers"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundsurfaceup",
                title = "Raised sheets",
                description = "Elevated cards and overlays above the main surface"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundsurfacedown",
                title = "Inset sheets",
                description = "Lowered panels, pressed sheet states, and inset surfaces"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundobject",
                title = "Rows and cards",
                description = "Chat rows, list cells, profile cards, and repeated objects"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundobjectdown",
                title = "Pressed rows",
                description = "Pressed or selected row and card states"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundabovesurface",
                title = "Above-surface blocks",
                description = "Floating blocks rendered above sheets and cards"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgrounddisabled",
                title = "Disabled surfaces",
                description = "Disabled buttons, inactive fields, and unavailable row states"
            ),
            SnapchatThemeCatalogEntry(
                key = "valdi.token.backgroundoverlay",
                title = "Overlays and scrims",
                description = "Snackbar overlays, translucent panels, and dimmed surface layers"
            ),
            SnapchatThemeCatalogEntry(
                key = "surface",
                title = "Generic surface",
                description = "Fallback for any token or attribute named surface"
            ),
            SnapchatThemeCatalogEntry(
                key = "surfacecolor",
                title = "Generic surface color",
                description = "Fallback for explicit surfaceColor values"
            ),
            SnapchatThemeCatalogEntry(
                key = "container",
                title = "Containers",
                description = "General containers around lists, toolbars, and grouped controls"
            ),
            SnapchatThemeCatalogEntry(
                key = "containercolor",
                title = "Container colors",
                description = "Explicit containerColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "card",
                title = "Cards",
                description = "Standalone cards and grouped content blocks"
            ),
            SnapchatThemeCatalogEntry(
                key = "cardcolor",
                title = "Card colors",
                description = "Explicit cardColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "panel",
                title = "Panels",
                description = "Panels inside profiles, menus, search, and creation flows"
            ),
            SnapchatThemeCatalogEntry(
                key = "panelcolor",
                title = "Panel colors",
                description = "Explicit panelColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "sheet",
                title = "Bottom sheets",
                description = "Action sheets, create-chat sheets, share sheets, and menus"
            ),
            SnapchatThemeCatalogEntry(
                key = "sheetcolor",
                title = "Bottom sheet colors",
                description = "Explicit sheetColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "rowbackground",
                title = "List row backgrounds",
                description = "Friend rows, search rows, settings rows, and menu options"
            ),
            SnapchatThemeCatalogEntry(
                key = "rowbackgroundcolor",
                title = "List row background colors",
                description = "Explicit rowBackgroundColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "cellbackground",
                title = "Grid and table cells",
                description = "Cells in grids, pickers, lenses, and compact selectors"
            ),
            SnapchatThemeCatalogEntry(
                key = "cellbackgroundcolor",
                title = "Grid and table cell colors",
                description = "Explicit cellBackgroundColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "backgroundfill",
                title = "Filled backgrounds",
                description = "Newer Composer backgroundFill attributes used by generated surfaces"
            ),
            SnapchatThemeCatalogEntry(
                key = "itembackground",
                title = "Item backgrounds",
                description = "Friend picker, create-chat, menu, and search item containers"
            ),
            SnapchatThemeCatalogEntry(
                key = "itembackgroundcolor",
                title = "Item background colors",
                description = "Explicit itemBackgroundColor attributes in newer Snapchat screens"
            ),
            SnapchatThemeCatalogEntry(
                key = "selectedbackground",
                title = "Selected item backgrounds",
                description = "Selected friends, pressed items, and active row states"
            ),
            SnapchatThemeCatalogEntry(
                key = "selectedbackgroundcolor",
                title = "Selected item background colors",
                description = "Explicit selectedBackgroundColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "primaryfill",
                title = "Primary fills",
                description = "Primary generated fills on profile, spotlight, and creation surfaces"
            ),
            SnapchatThemeCatalogEntry(
                key = "primaryfillcolor",
                title = "Primary fill colors",
                description = "Explicit primaryFillColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "secondaryfill",
                title = "Secondary fills",
                description = "Secondary generated fills on cards, sheets, and grouped controls"
            ),
            SnapchatThemeCatalogEntry(
                key = "secondaryfillcolor",
                title = "Secondary fill colors",
                description = "Explicit secondaryFillColor attributes"
            ),
            SnapchatThemeCatalogEntry(
                key = "chromecolor",
                title = "Chrome colors",
                description = "Toolbar, header, and system-like chrome requested by Snapchat surfaces"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.background",
                title = "Android view backgrounds",
                description = "Native Android view backgrounds used around Composer screens"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.0x10100d4",
                title = "Framework background attribute",
                description = "The raw android:background attribute for native views"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.colorbackground",
                title = "Android window background",
                description = "System colorBackground used by native screens and windows"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.windowbackground",
                title = "Android window drawable",
                description = "Window background requested by native screens"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.navigationbarcolor",
                title = "Navigation bar color",
                description = "Native navigation bar and lower system chrome"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.statusbarcolor",
                title = "Status bar color",
                description = "Native status bar and upper system chrome"
            ),
            SnapchatThemeCatalogEntry(
                key = "android.attr.coloraccent",
                title = "Accent color",
                description = "Accent surfaces, selected controls, and active highlights"
            ),
            SnapchatThemeCatalogEntry(
                key = "fill",
                title = "Generic fills",
                description = "Fallback fill colors for surfaces without a named token"
            ),
            SnapchatThemeCatalogEntry(
                key = "fillcolor",
                title = "Generic fill colors",
                description = "Explicit fillColor values on Composer nodes"
            ),
            SnapchatThemeCatalogEntry(
                key = "color",
                title = "Last-resort color",
                description = "Very broad fallback for unnamed Composer surface color values"
            )
        )
    }

    internal fun readSnapchatThemeSurfaceMap(): SnapchatThemeSurfaceMap? {
        return runCatching {
            val handle = context.fileHandleManager
                .getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.SNAPCHAT_THEME_SURFACES.key)
                ?: run {
                    context.log.verbose("[THEME MAP UI] surface map handle missing")
                    return null
                }
            if (!handle.exists()) {
                context.log.verbose("[THEME MAP UI] surface map file does not exist")
                return null
            }
            val raw = handle.toWrapper().readBytes().toString(Charsets.UTF_8)
            if (raw.isBlank()) {
                context.log.verbose("[THEME MAP UI] surface map file is blank")
                return null
            }
            SnapchatThemeSurfaceMapCodec.parse(raw).also { map ->
                context.log.verbose(
                    "[THEME MAP UI] surface map parsed bytes=${raw.length} surfaces=${map.surfaces.size} " +
                        "hash=${map.uniqueHash} snap=${map.snapchatVersionName}/${map.snapchatVersionCode} " +
                        "updated=${map.updatedAtMs} sample=${map.surfaces.take(20).joinToString { "${it.key}:${it.source}:${it.hitCount}" }}"
                )
            }
        }.onFailure {
            context.log.warn("Failed to read Snapchat theme surface map: ${it.message}")
        }.getOrNull()
    }

    internal fun mappedSnapchatThemeDescription(surface: SnapchatThemeMappedSurface): String {
        val details = buildList {
            add(surface.category)
            surface.attributeName?.takeIf { it.isNotBlank() }?.let { add("attr $it") }
            surface.className?.takeIf { it.isNotBlank() }?.substringAfterLast('.')?.let { add("class $it") }
            surface.resourceName?.takeIf { it.isNotBlank() }?.let { add("id $it") }
            surface.selectorDisplayName?.takeIf { it.isNotBlank() }?.let { add("selector $it") }
            surface.role?.takeIf { it.isNotBlank() }?.let { add(it) }
            surface.screenHint?.takeIf { it.isNotBlank() }?.let { add("screen $it") }
            surface.screenBounds?.takeIf { it.isNotBlank() }?.let { add("bounds $it") }
            if (surface.hitCount > 0) add("${surface.hitCount} hits")
            surface.source.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" - ")
        val description = surface.description.takeIf { it.isNotBlank() }
            ?: surface.valueSummary?.takeIf { it.isNotBlank() }
            ?: details
        return buildString {
            append(description.take(150))
            if (details.isNotBlank() && details !in description) {
                append(" - ").append(details.take(120))
            }
        }
    }

    internal fun mappedSnapchatThemeCatalogEntry(surface: SnapchatThemeMappedSurface): SnapchatThemeCatalogEntry? {
        val key = normalizeSnapchatThemeSurfaceKey(surface.key)
        if (key.isBlank()) return null
        return SnapchatThemeCatalogEntry(
            key = key,
            title = surface.label.ifBlank { SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key) },
            description = mappedSnapchatThemeDescription(surface),
            defaultColor = surface.defaultColor ?: 0xFF000000.toInt(),
            category = surface.category,
            source = surface.source,
            hitCount = surface.hitCount,
            lastSeenMs = surface.lastSeenMs,
            attributeName = surface.attributeName,
            className = surface.className ?: surface.viewClass,
            resourceName = surface.resourceName,
            selector = surface.selector,
            selectorDisplayName = surface.selectorDisplayName,
            screenHint = surface.screenHint,
            role = surface.role,
            screenBounds = surface.screenBounds,
            localBounds = surface.localBounds,
            valueSummary = surface.valueSummary,
            confidence = surface.confidence,
            details = surface.details
        )
    }

    internal fun snapchatThemeCatalogState(): SnapchatThemeCatalogState {
        val builtIns = snapchatThemeCatalog()
        val map = readSnapchatThemeSurfaceMap()
        val merged = linkedMapOf<String, SnapchatThemeCatalogEntry>()
        builtIns.forEach { entry ->
            merged[normalizeSnapchatThemeSurfaceKey(entry.key)] = entry
        }
        map?.surfaces
            ?.mapNotNull(::mappedSnapchatThemeCatalogEntry)
            ?.forEach { mapped ->
                val key = normalizeSnapchatThemeSurfaceKey(mapped.key)
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    mapped
                } else {
                    existing.copy(
                        description = if (mapped.hitCount > 0) {
                            "${existing.description} - mapped ${mapped.hitCount}x via ${mapped.source}"
                        } else {
                            existing.description
                        },
                        category = mapped.category,
                        source = mapped.source,
                        hitCount = mapped.hitCount,
                        lastSeenMs = mapped.lastSeenMs,
                        attributeName = mapped.attributeName,
                        className = mapped.className,
                        resourceName = mapped.resourceName,
                        selector = mapped.selector,
                        selectorDisplayName = mapped.selectorDisplayName,
                        screenHint = mapped.screenHint,
                        role = mapped.role,
                        screenBounds = mapped.screenBounds,
                        localBounds = mapped.localBounds,
                        valueSummary = mapped.valueSummary,
                        confidence = mapped.confidence,
                        details = mapped.details
                    )
                }
            }
        context.log.verbose(
            "[THEME MAP UI] catalog loaded builtIns=${builtIns.size} mapped=${map?.surfaces?.size ?: 0} " +
                "merged=${merged.size} uniqueHash=${map?.uniqueHash ?: -1} updated=${map?.updatedAtMs ?: 0}"
        )
        val versionLabel = map?.let {
            val name = it.snapchatVersionName?.takeIf { version -> version.isNotBlank() } ?: "Snapchat"
            "$name (${it.snapchatVersionCode})"
        } ?: "not built yet"
        return SnapchatThemeCatalogState(
            entries = merged.values.sortedWith(
                compareByDescending<SnapchatThemeCatalogEntry> { it.hitCount > 0 }
                    .thenBy { it.category }
                    .thenBy { it.title.lowercase(Locale.US) }
            ),
            mappedSurfaceCount = map?.surfaces?.size ?: 0,
            uniqueHash = map?.uniqueHash ?: -1L,
            snapchatVersionLabel = versionLabel,
            updatedAtMs = map?.updatedAtMs ?: 0L
        )
    }

    internal fun requestSnapchatThemeSurfaceMapRefresh() {
        val targetPackage = context.packageNameForTargetApp(TargetApp.SNAPCHAT)
        val deleted = runCatching {
            context.fileHandleManager
                .getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.SNAPCHAT_THEME_SURFACES.key)
                ?.delete() == true
        }.onFailure {
            context.log.warn("Failed to delete Snapchat theme surface map before refresh: ${it.message}")
        }.getOrDefault(false)
        context.androidContext.sendBroadcast(
            Intent(Constants.SNAPCHAT_THEME_SURFACE_MAP_REFRESH_ACTION)
                .setPackage(targetPackage)
        )
        context.log.verbose("[THEME MAP UI] manual refresh requested deleted=$deleted targetPackage=$targetPackage")
        context.shortToast("Theme surface map refresh requested")
    }

    internal fun rebuildSnapchatThemeApkMappings(): Boolean {
        context.log.verbose("[THEME MAP UI] full APK theme mapper refresh requested")
        return runCatching {
            context.mappings.init(context.androidContext)
            val warnings = context.mappings.refresh()
            if (warnings.isNotEmpty()) {
                context.log.warn("[THEME MAP UI] APK mapper refresh completed with ${warnings.size} warnings:\n${warnings.joinToString("\n")}")
            }
            context.log.verbose("[THEME MAP UI] full APK theme mapper refresh completed warnings=${warnings.size}")
            true
        }.onFailure {
            context.log.error("[THEME MAP UI] full APK theme mapper refresh failed", it)
            context.shortToast("Theme APK mapper refresh failed: ${it.message}")
        }.getOrDefault(false)
    }

    internal suspend fun rebuildSnapchatThemeMapperAndRefreshCache(): Boolean {
        return withContext(Dispatchers.IO) {
            context.log.verbose("[THEME MAP UI] mapper+cache refresh start thread=${Thread.currentThread().name}")
            val rebuilt = rebuildSnapchatThemeApkMappings()
            requestSnapchatThemeSurfaceMapRefresh()
            context.log.verbose("[THEME MAP UI] mapper+cache refresh end rebuilt=$rebuilt")
            rebuilt
        }
    }

    internal fun parseSnapchatThemeColor(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
            ?: runCatching {
                val clean = raw.removePrefix("0x").removePrefix("#")
                val parsed = clean.toLong(16)
                when (clean.length) {
                    6 -> 0xFF000000.toInt() or parsed.toInt()
                    8 -> parsed.toInt()
                    else -> null
                }
            }.getOrNull()
    }

    internal fun snapchatThemeColorToHex(color: Int): String {
        return "#%08X".format(Locale.US, color)
    }

    internal fun normalizeSnapchatThemeSurfaceKey(value: String): String {
        return value.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_', '.', '-')
    }

    internal fun isSnapchatThemeSelectorTarget(value: String): Boolean {
        return value.startsWith("selector:v1|") || value.startsWith("snapvirtual:v1|")
    }

    internal fun snapchatThemeSelectorDisplayName(value: String): String {
        if (value.startsWith("snapvirtual:v1|")) {
            val text = snapchatVirtualSelectorValue(value, "text")
            val description = snapchatVirtualSelectorValue(value, "desc")
            val viewId = snapchatVirtualSelectorValue(value, "view_id")?.substringAfterLast('/')
            val className = snapchatVirtualSelectorValue(value, "class")?.substringAfterLast('.')
            val raw = text ?: description ?: viewId ?: className ?: "Virtual surface"
            val label = raw
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .ifBlank { "Virtual surface" }
            val bounds = snapchatVirtualSelectorValue(value, "bounds")
            return if (!bounds.isNullOrBlank()) "$label\n$bounds" else label
        }
        return WhatsAppUiElementSelector.toDisplayName(value)
    }

    private fun snapchatVirtualSelectorValue(selector: String, key: String): String? {
        if (!selector.startsWith("snapvirtual:v1|")) return null
        return selector.substring("snapvirtual:v1|".length)
            .split('|')
            .mapNotNull { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) null else part.substring(0, equals) to unescapeSnapchatVirtualSelectorValue(part.substring(equals + 1))
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun unescapeSnapchatVirtualSelectorValue(value: String): String {
        return value
            .replace("%0A", "\n")
            .replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")
    }

    internal fun snapchatThemeCatalogSupportsBackgroundImage(entry: SnapchatThemeCatalogEntry): Boolean {
        val key = normalizeSnapchatThemeSurfaceKey(entry.key)
        return !entry.selector.isNullOrBlank() ||
            key in snapchatThemeGlobalBackgroundKeys ||
            key.startsWith("android.view.") ||
            !entry.resourceName.isNullOrBlank() ||
            entry.category.contains("Android view", ignoreCase = true)
    }

    internal fun snapchatThemeCatalogMetaLine(entry: SnapchatThemeCatalogEntry): String {
        val values = buildList {
            add(entry.category)
            add(entry.source)
            if (entry.hitCount > 0) add("${entry.hitCount} hits")
            if (entry.confidence > 0f) add("${(entry.confidence * 100f).roundToInt()}% confidence")
            entry.attributeName?.takeIf { it.isNotBlank() }?.let { add("attr $it") }
            entry.className?.takeIf { it.isNotBlank() }?.substringAfterLast('.')?.let { add("class $it") }
            entry.resourceName?.takeIf { it.isNotBlank() }?.let { add("id $it") }
            entry.selectorDisplayName?.takeIf { it.isNotBlank() }?.let { add("selector $it") }
            entry.role?.takeIf { it.isNotBlank() }?.let { add(it) }
            entry.screenHint?.takeIf { it.isNotBlank() }?.let { add("screen $it") }
            entry.screenBounds?.takeIf { it.isNotBlank() }?.let { add("bounds $it") }
            entry.valueSummary?.takeIf { it.isNotBlank() && it != "none" }?.let { add(it.take(90)) }
        }
        return values.distinct().joinToString(" - ")
    }

    internal fun parseSnapchatThemeSurfaceOverrides(raw: String): List<SnapchatThemeSurfaceOverride> {
        if (raw.isBlank()) return emptyList()
        val values = linkedMapOf<String, SnapchatThemeSurfaceOverride>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parsed = runCatching {
                if (line.startsWith("{")) {
                    val json = JSONObject(line)
                    val key = normalizeSnapchatThemeSurfaceKey(
                        json.optString("key").ifEmpty { json.optString("surface") }
                    )
                    val color = parseSnapchatThemeColor(json.optString("color"))
                    if (key.isNotEmpty() && color != null) {
                        SnapchatThemeSurfaceOverride(
                            key = key,
                            label = json.optString("label").trim().ifEmpty { key },
                            color = color
                        )
                    } else {
                        null
                    }
                } else {
                    val key = normalizeSnapchatThemeSurfaceKey(line.substringBefore('=').trim())
                    val color = parseSnapchatThemeColor(line.substringAfter('=', "").trim())
                    if (key.isNotEmpty() && color != null) {
                        SnapchatThemeSurfaceOverride(key = key, label = key, color = color)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (parsed != null) values[parsed.key] = parsed
        }
        return values.values.toList()
    }

    internal fun parseSnapchatThemeSurfaceRules(raw: String): List<SnapchatThemeSurfaceRule> {
        if (raw.isBlank()) return emptyList()
        val values = linkedMapOf<String, SnapchatThemeSurfaceRule>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parsed = runCatching {
                val json = JSONObject(line)
                val target = json.optString("target").trim()
                val color = parseSnapchatThemeColor(json.optString("color")) ?: return@runCatching null
                if (target.isEmpty()) return@runCatching null
                SnapchatThemeSurfaceRule(
                    target = target,
                    isSelector = json.optBoolean("selector", isSnapchatThemeSelectorTarget(target)),
                    label = json.optString("label").trim().ifEmpty {
                        if (isSnapchatThemeSelectorTarget(target)) {
                            snapchatThemeSelectorDisplayName(target)
                        } else {
                            target
                        }
                    },
                    color = color
                )
            }.getOrNull()
            if (parsed != null) values["${parsed.isSelector}:${parsed.target}"] = parsed
        }
        return values.values.toList()
    }

    internal fun parseSnapchatThemeBackgroundRules(raw: String): List<SnapchatThemeBackgroundRule> {
        if (raw.isBlank()) return emptyList()
        val values = linkedMapOf<String, SnapchatThemeBackgroundRule>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parsed = runCatching {
                val json = JSONObject(line)
                val target = json.optString("target").trim()
                val path = json.optString("path").trim()
                if (target.isEmpty() || path.isEmpty()) return@runCatching null
                SnapchatThemeBackgroundRule(
                    target = target,
                    isSelector = json.optBoolean("selector", isSnapchatThemeSelectorTarget(target)),
                    label = json.optString("label").trim().ifEmpty {
                        if (isSnapchatThemeSelectorTarget(target)) snapchatThemeSelectorDisplayName(target) else target
                    },
                    path = path,
                    name = json.optString("name").trim().ifEmpty { File(path).name }
                )
            }.getOrNull()
            if (parsed != null) values["${parsed.isSelector}:${parsed.target}"] = parsed
        }
        return values.values.toList()
    }

    internal fun serializeSnapchatThemeOverride(entry: SnapchatThemeSurfaceOverride): String {
        return JSONObject()
            .put("key", entry.key)
            .put("label", entry.label)
            .put("color", snapchatThemeColorToHex(entry.color))
            .toString()
    }

    internal fun serializeSnapchatThemeRule(rule: SnapchatThemeSurfaceRule): String {
        return JSONObject()
            .put("target", rule.target)
            .put("selector", rule.isSelector)
            .put("label", rule.label)
            .put("color", snapchatThemeColorToHex(rule.color))
            .toString()
    }

    internal fun serializeSnapchatThemeBackground(rule: SnapchatThemeBackgroundRule): String {
        return JSONObject()
            .put("target", rule.target)
            .put("selector", rule.isSelector)
            .put("label", rule.label)
            .put("path", rule.path)
            .put("name", rule.name)
            .toString()
    }

    internal fun sendSnapchatThemeConfigChangedBroadcast() {
        val targetPackage = context.packageNameForTargetApp(TargetApp.SNAPCHAT)
        context.androidContext.sendBroadcast(
            Intent(Constants.SNAPCHAT_THEME_CONFIG_CHANGED_ACTION)
                .setPackage(targetPackage)
        )
        context.log.verbose("[THEME MAP UI] config changed broadcast sent targetPackage=$targetPackage")
    }

    internal fun persistSnapchatThemeConfig(onConfigChanged: () -> Unit) {
        context.config.writeConfig()
        context.log.verbose("[THEME MAP UI] custom theme config persisted")
        sendSnapchatThemeConfigChangedBroadcast()
        onConfigChanged()
    }

    internal fun setSnapchatThemeCaptureEnabled(enabled: Boolean, onConfigChanged: () -> Unit) {
        context.log.verbose("[THEME MAP UI] captureThemeSurfaces set enabled=$enabled")
        context.config.root.userInterface.customTheme.captureThemeSurfaces.set(enabled)
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun setSnapchatThemeEnabled(enabled: Boolean, onConfigChanged: () -> Unit) {
        context.log.verbose("[THEME MAP UI] custom theme enabled set enabled=$enabled")
        context.config.root.userInterface.customTheme.enabled.set(enabled)
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun setSnapchatThemeSurfaceOverride(
        key: String,
        label: String,
        color: Int,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val values = parseSnapchatThemeSurfaceOverrides(customTheme.surfaceColorOverrides.getNullable().orEmpty())
            .associateBy { it.key }
            .toMutableMap()
        values[normalizeSnapchatThemeSurfaceKey(key)] = SnapchatThemeSurfaceOverride(
            key = normalizeSnapchatThemeSurfaceKey(key),
            label = label,
            color = color
        )
        customTheme.enabled.set(true)
        customTheme.surfaceColorOverrides.set(values.values.joinToString("\n", transform = ::serializeSnapchatThemeOverride))
        context.log.verbose(
            "[THEME MAP UI] surface override set key=${normalizeSnapchatThemeSurfaceKey(key)} label=$label " +
                "color=${snapchatThemeColorToHex(color)} total=${values.size}"
        )
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun removeSnapchatThemeSurfaceOverride(key: String, onConfigChanged: () -> Unit) {
        val customTheme = context.config.root.userInterface.customTheme
        val normalizedKey = normalizeSnapchatThemeSurfaceKey(key)
        val values = parseSnapchatThemeSurfaceOverrides(customTheme.surfaceColorOverrides.getNullable().orEmpty())
            .filterNot { it.key == normalizedKey }
        customTheme.surfaceColorOverrides.set(values.joinToString("\n", transform = ::serializeSnapchatThemeOverride))
        context.log.verbose("[THEME MAP UI] surface override removed key=$normalizedKey remaining=${values.size}")
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun setSnapchatThemeSurfaceRule(
        rule: SnapchatThemeSurfaceRule,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val values = parseSnapchatThemeSurfaceRules(customTheme.customSurfaceRules.getNullable().orEmpty())
            .associateBy { "${it.isSelector}:${it.target}" }
            .toMutableMap()
        values["${rule.isSelector}:${rule.target}"] = rule
        customTheme.enabled.set(true)
        customTheme.customSurfaceRules.set(values.values.joinToString("\n", transform = ::serializeSnapchatThemeRule))
        context.log.verbose(
            "[THEME MAP UI] surface selector rule set target=${rule.target} selector=${rule.isSelector} " +
                "label=${rule.label} color=${snapchatThemeColorToHex(rule.color)} total=${values.size}"
        )
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun removeSnapchatThemeSurfaceRule(
        rule: SnapchatThemeSurfaceRule,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val targetKey = "${rule.isSelector}:${rule.target}"
        val values = parseSnapchatThemeSurfaceRules(customTheme.customSurfaceRules.getNullable().orEmpty())
            .filterNot { "${it.isSelector}:${it.target}" == targetKey }
        customTheme.customSurfaceRules.set(values.joinToString("\n", transform = ::serializeSnapchatThemeRule))
        context.log.verbose("[THEME MAP UI] surface selector rule removed key=$targetKey remaining=${values.size}")
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun setSnapchatThemeBackgroundRule(
        rule: SnapchatThemeBackgroundRule,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val values = parseSnapchatThemeBackgroundRules(customTheme.screenBackgrounds.getNullable().orEmpty())
            .associateBy { "${it.isSelector}:${it.target}" }
            .toMutableMap()
        values["${rule.isSelector}:${rule.target}"] = rule
        customTheme.enabled.set(true)
        customTheme.screenBackgrounds.set(values.values.joinToString("\n", transform = ::serializeSnapchatThemeBackground))
        context.log.verbose(
            "[THEME MAP UI] background rule set target=${rule.target} selector=${rule.isSelector} " +
                "label=${rule.label} path=${rule.path} name=${rule.name} total=${values.size}"
        )
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun removeSnapchatThemeBackgroundRule(
        rule: SnapchatThemeBackgroundRule,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val targetKey = "${rule.isSelector}:${rule.target}"
        val values = parseSnapchatThemeBackgroundRules(customTheme.screenBackgrounds.getNullable().orEmpty())
            .filterNot { "${it.isSelector}:${it.target}" == targetKey }
        customTheme.screenBackgrounds.set(values.joinToString("\n", transform = ::serializeSnapchatThemeBackground))
        context.log.verbose("[THEME MAP UI] background rule removed key=$targetKey remaining=${values.size}")
        persistSnapchatThemeConfig(onConfigChanged)
    }

    internal fun sendSnapchatThemePreview(target: String, isSelector: Boolean, color: Int?) {
        val targetPackage = context.packageNameForTargetApp(TargetApp.SNAPCHAT)
        context.androidContext.sendBroadcast(
            Intent(Constants.SNAPCHAT_THEME_PREVIEW_ACTION)
                .setPackage(targetPackage)
                .putExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA, target)
                .putExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isSelector)
                .putExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA, color?.let(::snapchatThemeColorToHex).orEmpty())
        )
        context.log.verbose(
            "[THEME MAP UI] preview broadcast sent target=$target selector=$isSelector " +
                "color=${color?.let(::snapchatThemeColorToHex) ?: "default"} targetPackage=$targetPackage"
        )
    }

    internal fun importSnapchatThemeBackground(
        target: String,
        isSelector: Boolean,
        label: String,
        onConfigChanged: () -> Unit
    ) {
        activityLauncher {
            openFile("image/*") { uriString ->
                if (uriString.isBlank()) return@openFile
                val uri = uriString.toUri()
                runCatching {
                    val appContext = context.androidContext
                    val displayName = queryDisplayName(uri).ifBlank { uri.lastPathSegment ?: "snapchat-background" }
                    val extension = displayName.substringAfterLast('.', "img").take(12).ifBlank { "img" }
                    val backgroundDir = File(appContext.filesDir, "snapchat_theme_backgrounds")
                    backgroundDir.mkdirs()
                    val targetName = target
                        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                        .trim('_')
                        .take(80)
                        .ifBlank { "surface" }
                    val backgroundFile = File(backgroundDir, "${targetName}_${System.currentTimeMillis()}.$extension")
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        backgroundFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to open selected image")
                    makeWorldReadable(backgroundFile)
                    val backgroundUri = Uri.parse(Constants.SNAPCHAT_THEME_BACKGROUND_BASE_URI)
                        .buildUpon()
                        .appendPath(backgroundFile.name)
                        .build()
                        .toString()
                    setSnapchatThemeBackgroundRule(
                        SnapchatThemeBackgroundRule(
                            target = target,
                            isSelector = isSelector,
                            label = label,
                            path = backgroundUri,
                            name = displayName
                        ),
                        onConfigChanged
                    )
                    context.shortToast("Snapchat background image saved")
                }.onFailure {
                    context.log.error("Failed to import Snapchat background image", it)
                    context.shortToast("Failed to import background image")
                }
            }
        }
    }

    internal fun backupRandomizedProfile(onConfigChanged: () -> Unit) {
        val profileSnapshot = getRandomizedProfileSnapshot()
        if (profileSnapshot.startsWith("No generated profile")) {
            context.shortToast(
                context.translation["manager.dialogs.randomize_device_profile.empty"]
                    ?: "No generated profile is available yet. Enable the feature in Snapchat first."
            )
            return
        }
        activityLauncher {
            saveFile("randomized-device-profile.json", "application/json") { uri ->
                runCatching {
                    context.androidContext.contentResolver.openOutputStream(uri.toUri())?.bufferedWriter()?.use {
                        it.write(profileSnapshot)
                    } ?: error("Failed to open backup destination")
                    onConfigChanged()
                    context.shortToast("Randomized profile backup saved")
                }.onFailure {
                    context.log.error("Failed to back up randomized profile", it)
                    context.shortToast("Failed to back up randomized profile")
                }
            }
        }
    }

    internal fun restoreRandomizedProfile(onConfigChanged: () -> Unit) {
        activityLauncher {
            openFile("application/json") { uri ->
                runCatching {
                    val importedJson = context.androidContext.contentResolver.openInputStream(uri.toUri())
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.trim()
                        ?: error("Failed to read randomized profile backup")
                    val profile = RandomizedDeviceProfile.fromJson(importedJson)
                    val generationToken = UUID.randomUUID().toString()
                    val profileJson = profile.toJson().toString()
                    
                    context.androidContext.getSharedPreferences("purrfect_spoof", 0)
                        .edit()
                        .putString("randomized_device_profile", profileJson)
                        .putString("randomized_device_profile_token", generationToken)
                        .putString("android_id", profile.androidId)
                        .putString("advertising_id", profile.advertisingId)
                        .putString("bluetooth_address", profile.bluetoothMacAddress)
                        .putString("gsf_id", profile.gsfId)
                        .putString("random_device", profile.deviceInfo.model)
                        .putString("device_fingerprint", profile.buildFingerprint)
                        .apply()

                    val randomizeConfig = context.config.root.experimental.spoof.randomizeDeviceProfile
                    randomizeConfig.profileGenerationToken.set(generationToken)
                    randomizeConfig.currentProfileSnapshot.set(profile.toJson().toString(2))
                    randomizeConfig.profileData.set(profileJson)
                    
                    context.config.writeConfig()
                    onConfigChanged()
                    context.shortToast("Randomized profile restored. Restart Snapchat to apply it.")
                }.onFailure {
                    context.log.error("Failed to restore randomized profile", it)
                    context.shortToast("Failed to restore randomized profile")
                }
            }
        }
    }

    fun navigateToMainRoot() {
        routes.navController.navigate(routeInfo.id, NavOptions.Builder()
            .setPopUpTo(routes.navController.graph.findStartDestination().id, false)
            .setLaunchSingleTop(true)
            .build()
        )
    }

    private fun activityLauncher(block: ActivityLauncherHelper.() -> Unit) {
        routes.activityLauncher.let(block)
    }

    internal val actions: @Composable () -> List<Triple<String, ImageVector, () -> Unit>> = {
        val haptic = LocalHapticFeedback.current
        remember(context.activeTargetApp) {
            val list = mutableListOf<Triple<String, ImageVector, () -> Unit>>()
            list.add(Triple(translation["export_option"] ?: "Export", Icons.Filled.SaveAlt) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (context.isRedditMode) {
                    routes.configExportSummary.navigate {
                        put("exportSensitiveData", "false")
                        put("includeSavedLocations", "false")
                    }
                } else {
                    showExportDialog = true // Handled by caller
                }
            })
            list.add(Triple(translation["import_option"] ?: "Import", Icons.Filled.FileDownload) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                activityLauncher {
                    openFile("application/json") { uriString ->
                        runCatching {
                            val uri = android.net.Uri.parse(uriString)
                            context.androidContext.contentResolver.openInputStream(uri)?.use {
                                routes.configJsonForImport = it.readBytes().toString(Charsets.UTF_8)
                                routes.configImportConfirmation.navigate()
                            }
                        }.onFailure { err ->
                            context.log.error("Failed to read config file", err)
                            context.longToast(translation.format("config_import_failure_toast", "error" to (err.message ?: "Unknown")))
                        }
                    }
                }
            })
            list.add(Triple(translation["reset_option"] ?: "Reset", Icons.Filled.Refresh) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showResetConfirmationDialog = true // Handled by caller
            })

            if (context.isInstagramMode) {
                list.add(Triple(translation["instagram_json_select_config"] ?: "Select JSON Config", Icons.Filled.Dataset) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activityLauncher {
                        openFile("application/json") { uriString ->
                            runCatching {
                                val uri = android.net.Uri.parse(uriString)
                                context.androidContext.contentResolver.openInputStream(uri)?.use {
                                    val json = it.readBytes().toString(Charsets.UTF_8)
                                    context.androidContext.sendBroadcast(
                                        Intent(Constants.INSTAGRAM_DEV_CONFIG_IMPORT_ACTION)
                                            .setPackage(context.packageNameForTargetApp(TargetApp.INSTAGRAM))
                                            .putExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA, json)
                                            .putExtra("json_content", json)
                                    )
                                    context.shortToast(translation["instagram_json_sent"] ?: "Sent to Instagram")
                                }
                            }.onFailure { err ->
                                context.log.error("Failed to read instagram config file", err)
                                context.longToast(translation.format("instagram_json_read_failed", "error" to (err.message ?: "Unknown")))
                            }
                        }
                    }
                })
                list.add(Triple(translation["export_option"] ?: "Export Instagram JSON", Icons.Filled.DatasetLinked) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    exportInstagramDevConfigFromManager()
                })
                list.add(Triple(translation["instagram_db_update"] ?: "Update Instagram Database", Icons.Filled.Storage) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.androidContext.sendBroadcast(
                        Intent(Constants.INSTAGRAM_DB_UPDATE_ACTION)
                            .setPackage(context.packageNameForTargetApp(TargetApp.INSTAGRAM))
                    )
                })
            }
            list
        }
    }
    internal fun importInstagramEmojiFontFromManager(onConfigChanged: () -> Unit) {
        activityLauncher {
            openFile("*/*") { uriString ->
                if (uriString.isBlank()) return@openFile
                val uri = uriString.toUri()
                runCatching {
                    val appContext = context.androidContext
                    val displayName = queryDisplayName(uri).ifBlank { uri.lastPathSegment ?: "custom_emoji_font.ttf" }
                    val fontFile = File(File(appContext.filesDir, "emoji"), "custom_emoji_font.ttf")
                    fontFile.parentFile?.mkdirs()
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        fontFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Unable to open selected font")
                    Typeface.createFromFile(fontFile)
                    makeWorldReadable(fontFile)

                    val emojiConfig = context.config.root.instagram.misc.customEmojiFont
                    context.config.root.instagram.misc.customEmojiFontEnabled.set(true)
                    emojiConfig.customEmojiFontName.set(displayName)
                    emojiConfig.customEmojiFontPath.set(fontFile.absolutePath)
                    emojiConfig.customEmojiFontUri.set(INSTAGRAM_EMOJI_FONT_URI)
                    persistInstagramConfig(onConfigChanged)
                    context.shortToast(
                        context.translation["features.properties.instagram.properties.misc.custom_emoji_font.imported_toast"]
                            ?: "Custom emoji font imported."
                    )
                }.onFailure {
                    context.log.error("Failed to import Instagram emoji font", it)
                    context.shortToast(
                        context.translation["features.properties.instagram.properties.misc.custom_emoji_font.failed_toast"]
                            ?: "Custom emoji font import failed"
                    )
                }
            }
        }
    }

    internal fun resetInstagramEmojiFont(onConfigChanged: () -> Unit) {
        runCatching {
            File(File(context.androidContext.filesDir, "emoji"), "custom_emoji_font.ttf").delete()
        }
        val emojiConfig = context.config.root.instagram.misc.customEmojiFont
        context.config.root.instagram.misc.customEmojiFontEnabled.set(false)
        emojiConfig.customEmojiFontName.set("")
        emojiConfig.customEmojiFontPath.set("")
        emojiConfig.customEmojiFontUri.set("")
        persistInstagramConfig(onConfigChanged)
        context.shortToast(
            context.translation["features.properties.instagram.properties.misc.custom_emoji_font.reset_toast"]
                ?: "Custom emoji font reset."
        )
    }

    internal fun queryDisplayName(uri: Uri): String {
        val resolver = context.androidContext.contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index).orEmpty() else ""
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    internal fun makeWorldReadable(file: File) {
        runCatching {
            file.parentFile?.setReadable(true, false)
            file.parentFile?.setExecutable(true, false)
            file.setReadable(true, false)
        }
    }

    internal fun installedInstagramPackages(): List<String> {
        val packageManager = context.androidContext.packageManager
        return Constants.INSTAGRAM_PACKAGE_NAMES.filter { packageName ->
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }
    }

    internal fun primaryInstagramPackage(): String {
        return installedInstagramPackages().firstOrNull() ?: Constants.INSTAGRAM_PACKAGE_NAME
    }

    internal val instagramDistractionKeys = listOf(
        "disableStories",
        "disableFeed",
        "disableReels",
        "disableReelsExceptDM",
        "disableExplore",
        "disableComments"
    )

    internal val instagramMiscMasterKeys = listOf(
        "disableStoryFlipping",
        "disableVideoAutoPlay",
        "feedVideosStartWithSound",
        "storiesStartWithSound",
        "showFollowerToast",
        "showFeatureToasts",
        "enableStoryMentions",
        "localInstagramPlus",
        "restoreOldPostReelContextMenu",
        "sendCustomEmojiReactionsToStory",
        "changeLikeReactions",
        "enableCopyComment",
        "enableCopyBio",
        "disableDoubleTapLike",
        "enableShareSheetEmojiShortcuts",
        "enableActivityHistory",
        "enableNavigationTabCustomization",
        "enableConfirmRefresh",
        "stripShareTrackingParameters",
        "doNotSaveRecentSearches",
        "openLinksExternally",
        "enableStoryTrayLongPressActions",
        "customizeStoryRingSize",
        "disableGroupCreationFromShareSheet",
        "improveImageViewing",
        "moreOptionsOnPost",
        "removeEmptyBottomSpace"
    )

    internal val instagramDownloaderMasterKeys = listOf(
        "enablePostDownload",
        "enableStoryDownload",
        "enableReelDownload",
        "enableProfileDownload",
        "enableDmContextMenuOptions",
        "enableReelThumbnailDownload",
        "enableStoryMarkSeenButton",
        "enableStoryRepostButton",
        "enableHighQualityStoryUpload",
        "enableDmAnyFileUpload",
        "enableUploadInstantsFromGallery",
        "preventDmMessageListAutoscroll"
    )

    internal val instagramSelectionStringProperties = setOf(
        "navigationTabHidden",
        "navigationTabOrder",
        "navigationDefaultTab",
        "storyRingSize",
        "likeReactionAnimation",
        "confirmRefreshScope",
        "notesSpoofMapLocation",
        "hiddenChatNames"
    )

    internal val instagramNavigationTabs = listOf(
        "home" to "Home",
        "search" to "Search",
        "reels" to "Reels",
        "create" to "Create",
        "direct" to "Direct",
        "shop" to "Shop",
        "profile" to "Profile"
    )

    internal val instagramLikeReactionAnimations = listOf(
        "ARES_LIKE_ACTIVATION" to "Ares",
        "ANYWAY_LIKE_ACTIVATION" to "Anyway",
        "RINGS_LIKE_ADRIAN" to "Adrian",
        "RINGS_LIKE_AKI_KOCHI" to "Aki Kochi",
        "RINGS_LIKE_BRIAN" to "Brian",
        "RINGS_LIKE_BRICKLEY" to "Brickley",
        "RINGS_LIKE_COLE" to "Cole",
        "RINGS_LIKE_DJAG" to "DJAG",
        "RINGS_LIKE_DOLLY" to "Dolly",
        "RINGS_LIKE_ELYSE" to "Elyse",
        "RINGS_LIKE_FILM" to "Film",
        "RINGS_LIKE_FUTURA" to "Futura",
        "RINGS_LIKE_GABRIEL" to "Gabriel",
        "RINGS_LIKE_GOLLORIA" to "Golloria",
        "RINGS_LIKE_HOMESTEAD" to "Homestead",
        "RINGS_LIKE_KIDS" to "Kids",
        "RINGS_LIKE_LAUFEY" to "Laufey",
        "RINGS_LIKE_LINDA" to "Linda",
        "RINGS_LIKE_MIMLES" to "Mimles",
        "RINGS_LIKE_NIGEL" to "Nigel",
        "RINGS_LIKE_NINA" to "Nina",
        "RINGS_LIKE_OLIVIA" to "Olivia",
        "RINGS_LIKE_SEB" to "Seb",
        "RINGS_LIKE_TWINS" to "Twins",
        "RINGS_LIKE_TYSHAWN" to "Tyshawn",
        "RINGS_LIKE_ZARNA" to "Zarna"
    )

    internal val instagramConfirmRefreshScopes = listOf(
        "both" to "Feed + Reels",
        "feed" to "Feed only",
        "reels" to "Reels only"
    )

    internal fun persistInstagramConfig(onConfigChanged: () -> Unit) {
        context.config.writeConfig()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun openInstagramDevOptionsFromManager(onConfigChanged: () -> Unit) {
        val developer = context.config.root.instagram.developer
        val wasDevEnabled = developer.isDevEnabled.get()
        fun persistDevEnabled(enabled: Boolean) {
            developer.isDevEnabled.set(enabled)
            context.config.writeConfig()
            context.mirrorInstagramFeaturePrefs()
            onConfigChanged()
        }
        if (!wasDevEnabled) persistDevEnabled(true)

        val packages = installedInstagramPackages().ifEmpty { listOf(Constants.INSTAGRAM_PACKAGE_NAME) }
        Handler(Looper.getMainLooper()).post {
            // Do not force-stop here: the receiver can race and kill the freshly launched dev-options activity.
            var launched = false
            val deepLinks = listOf(
                "instagram://settings_devoptions",
                "instagram://developer_options",
                "instagram://settings/developer_options",
                "instagram://internal_settings",
                "instagram://settings/internal",
                "instagram://debug",
                "instagram://debug_settings",
                "instagram://settings/debug",
                "instagram://settings/account/dev_options",
                "instagram://settings/dev_options"
            )
            for (packageName in packages) {
                if (launched) break
                for (uri in deepLinks) {
                    if (launched) break
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        .setPackage(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    val launcher = context.activity ?: context.androidContext
                    if (runCatching {
                            launcher.startActivity(intent)
                            context.activity?.moveTaskToBack(true)
                        }.isSuccess) {
                        launched = true
                    }
                }
            }
            if (!launched) {
                if (!wasDevEnabled) persistDevEnabled(false)
                context.shortToast("Unable to open Instagram developer options")
                return@post
            }
            // Keep Developer Mode enabled after an explicit open request. The Instagram process may
            // receive the deep link before its session is ready, and reverting this flag races the
            // on-demand fragment fallback.
        }
    }

    internal fun isInstagramAdsAndLinksCoreEnabled(): Boolean {
        val ads = context.config.root.instagram.adsAndLinks
        return ads.isAdBlockEnabled.get() && ads.isAnalyticsBlocked.get() && ads.disableTrackingLinks.get()
    }

    internal fun setInstagramAdsAndLinksCore(enabled: Boolean, onConfigChanged: () -> Unit) {
        val ads = context.config.root.instagram.adsAndLinks
        ads.isAdBlockEnabled.set(enabled)
        ads.isAnalyticsBlocked.set(enabled)
        ads.disableTrackingLinks.set(enabled)
        persistInstagramConfig(onConfigChanged)
    }

    internal fun getInstagramBooleanFeature(key: String): Boolean {
        val instagram = context.config.root.instagram
        return when (key) {
            "disableStories" -> instagram.distractionFree.disableStories.get()
            "disableFeed" -> instagram.distractionFree.disableFeed.get()
            "disableReels" -> instagram.distractionFree.disableReels.get()
            "disableReelsExceptDM" -> instagram.distractionFree.disableReelsExceptDM.get()
            "disableExplore" -> instagram.distractionFree.disableExplore.get()
            "disableComments" -> instagram.distractionFree.disableComments.get()
            "hideSuggestionsInFeed" -> instagram.feedAndSearch.hideSuggestionsInFeed.get()
            "hideSuggestedForYouInFeed" -> instagram.feedAndSearch.hideSuggestedForYouInFeed.get()
            "hideSuggestionsInDm" -> instagram.feedAndSearch.hideSuggestionsInDm.get()
            "hideDiscoverPeopleInProfile" -> instagram.feedAndSearch.hideDiscoverPeopleInProfile.get()
            "disableStoryFlipping" -> instagram.misc.disableStoryFlipping.get()
            "disableVideoAutoPlay" -> instagram.misc.disableVideoAutoPlay.get()
            "feedVideosStartWithSound" -> instagram.misc.feedVideosStartWithSound.get()
            "storiesStartWithSound" -> instagram.misc.storiesStartWithSound.get()
            "showFollowerToast" -> instagram.misc.showFollowerToast.get()
            "showFeatureToasts" -> instagram.misc.showFeatureToasts.get()
            "enableStoryMentions" -> instagram.misc.enableStoryMentions.get()
            "localInstagramPlus" -> instagram.misc.localInstagramPlus.get()
            "restoreOldPostReelContextMenu" -> instagram.misc.restoreOldPostReelContextMenu.get()
            "sendCustomEmojiReactionsToStory" -> instagram.misc.sendCustomEmojiReactionsToStory.get()
            "changeLikeReactions" -> instagram.misc.changeLikeReactions.get()
            "enableCopyComment" -> instagram.misc.enableCopyComment.get()
            "enableCopyBio" -> instagram.misc.enableCopyBio.get()
            "disableDoubleTapLike" -> instagram.misc.disableDoubleTapLike.get()
            "customEmojiFontEnabled" -> instagram.misc.customEmojiFontEnabled.get()
            "enableShareSheetEmojiShortcuts" -> instagram.misc.enableShareSheetEmojiShortcuts.get()
            "enableActivityHistory" -> instagram.misc.enableActivityHistory.get()
            "enableNavigationTabCustomization" -> instagram.misc.enableNavigationTabCustomization.get()
            "enableConfirmRefresh" -> instagram.misc.enableConfirmRefresh.get()
            "enableNotesLocationSpoof" -> instagram.misc.notesLocation.globalState ?: instagram.misc.enableNotesLocationSpoof.get()
            "enableHideChats" -> instagram.misc.hiddenChats.globalState ?: instagram.misc.enableHideChats.get()
            "stripShareTrackingParameters" -> instagram.misc.stripShareTrackingParameters.get()
            "doNotSaveRecentSearches" -> instagram.misc.doNotSaveRecentSearches.get()
            "enableCustomDateFormat" -> instagram.misc.customDateFormat.globalState ?: instagram.misc.enableCustomDateFormat.get()
            "openLinksExternally" -> instagram.misc.openLinksExternally.get()
            "replaceShareLinkDomain" -> instagram.misc.shareLinkDomain.globalState ?: instagram.misc.replaceShareLinkDomain.get()
            "enableStoryTrayLongPressActions" -> instagram.misc.enableStoryTrayLongPressActions.get()
            "customizeStoryRingSize" -> instagram.misc.storyUi.customizeStoryRingSize.get()
            "disableGroupCreationFromShareSheet" -> instagram.misc.disableGroupCreationFromShareSheet.get()
            "improveImageViewing" -> instagram.misc.improveImageViewing.get()
            "moreOptionsOnPost" -> instagram.misc.moreOptionsOnPost.get()
            "removeEmptyBottomSpace" -> instagram.misc.removeEmptyBottomSpace.get()
            "enablePostDownload" -> instagram.downloader.enablePostDownload.get()
            "enableStoryDownload" -> instagram.downloader.enableStoryDownload.get()
            "enableReelDownload" -> instagram.downloader.enableReelDownload.get()
            "enableProfileDownload" -> instagram.downloader.enableProfileDownload.get()
            "enableDmContextMenuOptions" -> instagram.downloader.enableDmContextMenuOptions.get()
            "enableReelThumbnailDownload" -> instagram.downloader.enableReelThumbnailDownload.get()
            "enableStoryMarkSeenButton" -> instagram.downloader.enableStoryMarkSeenButton.get()
            "enableStoryRepostButton" -> instagram.downloader.enableStoryRepostButton.get()
            "enableHighQualityStoryUpload" -> instagram.downloader.enableHighQualityStoryUpload.get()
            "enableDmAnyFileUpload" -> instagram.downloader.enableDmAnyFileUpload.get()
            "enableUploadInstantsFromGallery" -> instagram.downloader.enableUploadInstantsFromGallery.get()
            "preventDmMessageListAutoscroll" -> instagram.downloader.preventDmMessageListAutoscroll.get()
            else -> false
        }
    }

    internal fun setInstagramBooleanFeature(key: String, enabled: Boolean) {
        val instagram = context.config.root.instagram
        when (key) {
            "disableStories" -> instagram.distractionFree.disableStories.set(enabled)
            "disableFeed" -> instagram.distractionFree.disableFeed.set(enabled)
            "disableReels" -> instagram.distractionFree.disableReels.set(enabled)
            "disableReelsExceptDM" -> instagram.distractionFree.disableReelsExceptDM.set(enabled)
            "disableExplore" -> instagram.distractionFree.disableExplore.set(enabled)
            "disableComments" -> instagram.distractionFree.disableComments.set(enabled)
            "hideSuggestionsInFeed" -> instagram.feedAndSearch.hideSuggestionsInFeed.set(enabled)
            "hideSuggestedForYouInFeed" -> instagram.feedAndSearch.hideSuggestedForYouInFeed.set(enabled)
            "hideSuggestionsInDm" -> instagram.feedAndSearch.hideSuggestionsInDm.set(enabled)
            "hideDiscoverPeopleInProfile" -> instagram.feedAndSearch.hideDiscoverPeopleInProfile.set(enabled)
            "disableStoryFlipping" -> instagram.misc.disableStoryFlipping.set(enabled)
            "disableVideoAutoPlay" -> instagram.misc.disableVideoAutoPlay.set(enabled)
            "feedVideosStartWithSound" -> instagram.misc.feedVideosStartWithSound.set(enabled)
            "storiesStartWithSound" -> instagram.misc.storiesStartWithSound.set(enabled)
            "showFollowerToast" -> instagram.misc.showFollowerToast.set(enabled)
            "showFeatureToasts" -> instagram.misc.showFeatureToasts.set(enabled)
            "enableStoryMentions" -> instagram.misc.enableStoryMentions.set(enabled)
            "localInstagramPlus" -> instagram.misc.localInstagramPlus.set(enabled)
            "restoreOldPostReelContextMenu" -> instagram.misc.restoreOldPostReelContextMenu.set(enabled)
            "sendCustomEmojiReactionsToStory" -> instagram.misc.sendCustomEmojiReactionsToStory.set(enabled)
            "changeLikeReactions" -> instagram.misc.changeLikeReactions.set(enabled)
            "enableCopyComment" -> instagram.misc.enableCopyComment.set(enabled)
            "enableCopyBio" -> instagram.misc.enableCopyBio.set(enabled)
            "disableDoubleTapLike" -> instagram.misc.disableDoubleTapLike.set(enabled)
            "customEmojiFontEnabled" -> instagram.misc.customEmojiFontEnabled.set(enabled)
            "enableShareSheetEmojiShortcuts" -> instagram.misc.enableShareSheetEmojiShortcuts.set(enabled)
            "enableActivityHistory" -> instagram.misc.enableActivityHistory.set(enabled)
            "enableNavigationTabCustomization" -> instagram.misc.enableNavigationTabCustomization.set(enabled)
            "enableConfirmRefresh" -> instagram.misc.enableConfirmRefresh.set(enabled)
            "enableNotesLocationSpoof" -> {
                instagram.misc.notesLocation.globalState = enabled
                instagram.misc.enableNotesLocationSpoof.set(enabled)
            }
            "enableHideChats" -> {
                instagram.misc.hiddenChats.globalState = enabled
                instagram.misc.enableHideChats.set(enabled)
            }
            "stripShareTrackingParameters" -> instagram.misc.stripShareTrackingParameters.set(enabled)
            "doNotSaveRecentSearches" -> instagram.misc.doNotSaveRecentSearches.set(enabled)
            "enableCustomDateFormat" -> {
                instagram.misc.customDateFormat.globalState = enabled
                instagram.misc.enableCustomDateFormat.set(enabled)
            }
            "openLinksExternally" -> instagram.misc.openLinksExternally.set(enabled)
            "replaceShareLinkDomain" -> {
                instagram.misc.shareLinkDomain.globalState = enabled
                instagram.misc.replaceShareLinkDomain.set(enabled)
            }
            "enableStoryTrayLongPressActions" -> instagram.misc.enableStoryTrayLongPressActions.set(enabled)
            "customizeStoryRingSize" -> instagram.misc.storyUi.customizeStoryRingSize.set(enabled)
            "disableGroupCreationFromShareSheet" -> instagram.misc.disableGroupCreationFromShareSheet.set(enabled)
            "improveImageViewing" -> instagram.misc.improveImageViewing.set(enabled)
            "moreOptionsOnPost" -> instagram.misc.moreOptionsOnPost.set(enabled)
            "removeEmptyBottomSpace" -> instagram.misc.removeEmptyBottomSpace.set(enabled)
            "enablePostDownload" -> instagram.downloader.enablePostDownload.set(enabled)
            "enableStoryDownload" -> instagram.downloader.enableStoryDownload.set(enabled)
            "enableReelDownload" -> instagram.downloader.enableReelDownload.set(enabled)
            "enableProfileDownload" -> instagram.downloader.enableProfileDownload.set(enabled)
            "enableDmContextMenuOptions" -> instagram.downloader.enableDmContextMenuOptions.set(enabled)
            "enableReelThumbnailDownload" -> instagram.downloader.enableReelThumbnailDownload.set(enabled)
            "enableStoryMarkSeenButton" -> instagram.downloader.enableStoryMarkSeenButton.set(enabled)
            "enableStoryRepostButton" -> instagram.downloader.enableStoryRepostButton.set(enabled)
            "enableHighQualityStoryUpload" -> instagram.downloader.enableHighQualityStoryUpload.set(enabled)
            "enableDmAnyFileUpload" -> instagram.downloader.enableDmAnyFileUpload.set(enabled)
            "enableUploadInstantsFromGallery" -> instagram.downloader.enableUploadInstantsFromGallery.set(enabled)
            "preventDmMessageListAutoscroll" -> instagram.downloader.preventDmMessageListAutoscroll.set(enabled)
        }
    }

    internal fun instagramContainerGlobalState(propertyName: String, container: ConfigContainer): Boolean {
        if (!context.isInstagramMode) return container.globalState ?: false
        val misc = context.config.root.instagram.misc
        return when (propertyName) {
            "notes_location" -> misc.notesLocation.globalState ?: misc.enableNotesLocationSpoof.get()
            "hidden_chats" -> misc.hiddenChats.globalState ?: misc.enableHideChats.get()
            "custom_date_format" -> misc.customDateFormat.globalState ?: misc.enableCustomDateFormat.get()
            "share_link_domain" -> misc.shareLinkDomain.globalState ?: misc.replaceShareLinkDomain.get()
            else -> container.globalState ?: false
        }
    }

    internal fun syncInstagramContainerGlobalState(propertyName: String, enabled: Boolean) {
        if (!context.isInstagramMode) return
        val misc = context.config.root.instagram.misc
        when (propertyName) {
            "notes_location" -> misc.enableNotesLocationSpoof.set(enabled)
            "hidden_chats" -> misc.enableHideChats.set(enabled)
            "custom_date_format" -> misc.enableCustomDateFormat.set(enabled)
            "share_link_domain" -> misc.replaceShareLinkDomain.set(enabled)
        }
    }

    internal fun isInstagramFeatureGroupEnabled(keys: List<String>): Boolean {
        return keys.all(::getInstagramBooleanFeature)
    }

    internal fun setInstagramFeatureGroup(keys: List<String>, enabled: Boolean, onConfigChanged: () -> Unit) {
        keys.forEach { setInstagramBooleanFeature(it, enabled) }
        persistInstagramConfig(onConfigChanged)
    }

    internal fun hasInstagramDistractionSelection(): Boolean {
        return instagramDistractionKeys.any(::getInstagramBooleanFeature)
    }

    internal fun isInstagramDistractionPropertyDisabled(propertyName: String): Boolean {
        val distraction = context.config.root.instagram.distractionFree
        if (propertyName == "isExtremeMode") {
            return !distraction.isExtremeMode.get() && !hasInstagramDistractionSelection()
        }
        return propertyName in instagramDistractionKeys && distraction.isExtremeMode.get()
    }

    internal fun applyInstagramBooleanSideEffects(propertyName: String, enabled: Boolean) {
        val distraction = context.config.root.instagram.distractionFree
        when (propertyName) {
            "isExtremeMode" -> {
                if (enabled) distraction.isDistractionFree.set(true)
            }
            "disableReels" -> {
                if (!enabled) distraction.disableReelsExceptDM.set(false)
            }
            "disableReelsExceptDM" -> {
                if (enabled) distraction.disableReels.set(true)
            }
        }
    }

    internal fun isInstagramGhostSettingsCoreEnabled(): Boolean {
        val ghost = context.config.root.instagram.privacy
        return ghost.isGhostSeen.get() &&
            ghost.isGhostTyping.get() &&
            ghost.isGhostStory.get() &&
            ghost.isGhostLive.get() &&
            ghost.hideVoiceMessageSeen.get() &&
            ghost.allowScreenshots.get() &&
            ghost.isGhostScreenshot.get() &&
            ghost.isGhostViewOnce.get() &&
            ghost.enableUnlimitedReplays.get() &&
            ghost.permanentViewMode.get() &&
            ghost.keepUnsentMessages.get() &&
            ghost.markTextsSeenAfterReply.get() &&
            ghost.storyInteractionSendsSeen.get()
    }

    internal fun setInstagramGhostSettingsCore(enabled: Boolean, onConfigChanged: () -> Unit) {
        val ghost = context.config.root.instagram.privacy
        ghost.isGhostSeen.set(enabled)
        ghost.isGhostTyping.set(enabled)
        ghost.isGhostStory.set(enabled)
        ghost.isGhostLive.set(enabled)
        ghost.hideVoiceMessageSeen.set(enabled)
        ghost.allowScreenshots.set(enabled)
        ghost.isGhostScreenshot.set(enabled)
        ghost.isGhostViewOnce.set(enabled)
        ghost.enableUnlimitedReplays.set(enabled)
        ghost.permanentViewMode.set(enabled)
        ghost.keepUnsentMessages.set(enabled)
        ghost.markTextsSeenAfterReply.set(enabled)
        ghost.storyInteractionSendsSeen.set(enabled)
        context.config.writeConfig()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun isInstagramQuickToggleCoreEnabled(): Boolean {
        val quick = context.config.root.instagram.privacy.quickToggle
        return quick.quickToggleSeen.get() &&
            quick.quickToggleTyping.get() &&
            quick.quickToggleScreenshot.get() &&
            quick.quickToggleViewOnce.get() &&
            quick.quickToggleStory.get() &&
            quick.quickToggleLive.get() &&
            quick.quickToggleUnsend.get() &&
            quick.quickToggleReplays.get() &&
            quick.quickTogglePermanentView.get() &&
            quick.quickToggleAllowScreenshots.get()
    }

    internal fun setInstagramQuickToggleCore(enabled: Boolean, onConfigChanged: () -> Unit) {
        val quick = context.config.root.instagram.privacy.quickToggle
        quick.quickToggleSeen.set(enabled)
        quick.quickToggleTyping.set(enabled)
        quick.quickToggleScreenshot.set(enabled)
        quick.quickToggleViewOnce.set(enabled)
        quick.quickToggleStory.set(enabled)
        quick.quickToggleLive.set(enabled)
        quick.quickToggleUnsend.set(enabled)
        quick.quickToggleReplays.set(enabled)
        quick.quickTogglePermanentView.set(enabled)
        quick.quickToggleAllowScreenshots.set(enabled)
        context.config.writeConfig()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun instagramDmMarkSeenControlMode(): String {
        return context.config.root.instagram.privacy.dmMarkSeenControlMode.get()
    }

    internal fun setInstagramDmMarkSeenControlMode(mode: String, onConfigChanged: () -> Unit) {
        val normalizedMode = if (mode == "hold_gallery") "hold_gallery" else "eye"
        context.config.root.instagram.privacy.dmMarkSeenControlMode.set(normalizedMode)
        context.config.writeConfig()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun instagramReelDownloadControlMode(): String {
        return context.config.root.instagram.downloader.reelDownloadControlMode.get()
    }

    internal fun setInstagramReelDownloadControlMode(mode: String, onConfigChanged: () -> Unit) {
        val normalizedMode = when (mode) {
            "like_long_press", "both" -> mode
            else -> "menu"
        }
        context.config.root.instagram.downloader.reelDownloadControlMode.set(normalizedMode)
        context.config.writeConfig()
        context.mirrorInstagramFeaturePrefs()
        onConfigChanged()
    }

    internal fun importInstagramDevConfigFromManager() {
        runCatching {
            context.androidContext.startActivity(
                Intent().apply {
                    component = ComponentName(
                        context.androidContext,
                        "me.eternal.purrfect.instagram.InstagramJsonImportActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("target_package", primaryInstagramPackage())
                    putExtra("broadcast_action", Constants.INSTAGRAM_DEV_CONFIG_IMPORT_ACTION)
                }
            )
        }.onFailure {
            context.log.error("Failed to start Instagram developer config import", it)
            context.shortToast("Unable to open config importer")
        }
    }

    internal fun exportInstagramDevConfigFromManager() {
        val appContext = context.androidContext
        var handled = false
        lateinit var receiver: BroadcastReceiver
        fun unregisterReceiver() {
            runCatching { appContext.unregisterReceiver(receiver) }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (handled || intent.action != Constants.INSTAGRAM_DEV_CONFIG_SEND_ACTION) return
                handled = true
                unregisterReceiver()

                val error = intent.getStringExtra(Constants.INSTAGRAM_DEV_CONFIG_ERROR_EXTRA)
                    ?: intent.getStringExtra("error")
                if (!error.isNullOrBlank()) {
                    this@FeaturesRootSection.context.shortToast(error)
                    return
                }

                val json = intent.getStringExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA)
                    ?: intent.getStringExtra("json_content")
                if (json.isNullOrBlank()) {
                    this@FeaturesRootSection.context.shortToast(
                        this@FeaturesRootSection.context.translation["instagram_export_no_config_data"]
                            ?: "No config data received."
                    )
                    return
                }

                runCatching {
                    appContext.startActivity(
                        Intent().apply {
                            component = ComponentName(
                                appContext,
                                "me.eternal.purrfect.instagram.InstagramJsonExportActivity"
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA, json)
                            putExtra("json_content", json)
                        }
                    )
                }.onFailure {
                    this@FeaturesRootSection.context.log.error("Failed to start Instagram developer config export", it)
                    this@FeaturesRootSection.context.shortToast("Unable to open config exporter")
                }
            }
        }

        runCatching {
            val filter = IntentFilter(Constants.INSTAGRAM_DEV_CONFIG_SEND_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }

            val packages = installedInstagramPackages().ifEmpty { listOf(Constants.INSTAGRAM_PACKAGE_NAME) }
            packages.forEach { packageName ->
                appContext.sendBroadcast(
                    Intent(Constants.INSTAGRAM_DEV_CONFIG_EXPORT_REQUEST_ACTION)
                        .setPackage(packageName)
                )
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (!handled) {
                    handled = true
                    unregisterReceiver()
                    context.shortToast("No config data received.")
                }
            }, 10_000L)
        }.onFailure {
            handled = true
            unregisterReceiver()
            context.log.error("Failed to request Instagram developer config export", it)
            context.shortToast("Unable to request config export")
        }
    }


    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(initialValue = context.config.root.global.uiSettings.managerTheme.get()) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }
        val skin = LocalPurrfectSkin.current
        key(themeId, skin.id) {
            LaunchedEffect(themeId) {
                routes.navigation?.globalScrollOffset = 0
            }
            with(ManagerTheme.fromId(themeId).theme) {
                this@FeaturesRootSection.FeaturesScreen(nav)
            }
        }
    }

    override val customComposables: NavGraphBuilder.() -> Unit = {
        routeInfo.childIds.addAll(listOf(FEATURE_CONTAINER_ROUTE, SEARCH_FEATURE_ROUTE))

        composable(
            FEATURE_CONTAINER_ROUTE,
            enterTransition = {
                fadeIn(animationSpec = tween(120)) +
                    slideInHorizontally(animationSpec = tween(140)) { it / 6 }
            },
            exitTransition = {
                fadeOut(animationSpec = tween(90))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(100))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(100)) +
                    slideOutHorizontally(animationSpec = tween(120)) { it / 6 }
            }
        ) { backStackEntry ->
            backStackEntry.arguments?.getString("name")?.let { containerName ->
                allContainers[containerName]?.let {
                    val containerTitle = translation[it.key.propertyName()]
                    val containerSubtitle = translation[it.key.propertyDescription()]
                    Container(
                        configContainer = it.value.get() as ConfigContainer,
                        stateKey = "${routeInfo.id}:container:$containerName",
                        sectionTitle = containerTitle,
                        sectionSubtitle = containerSubtitle,
                        onBack = { routes.navController.popBackStack() }
                    )
                }
            }
        }

        composable(SEARCH_FEATURE_ROUTE) { backStackEntry ->
            backStackEntry.arguments?.getString("keyword")?.let { keyword ->
                val properties = allProperties.filter {
                    isSearchVisibleProperty(it.key) && (
                        it.key.name.contains(keyword, ignoreCase = true) ||
                            context.translation[it.key.propertyName()].contains(keyword, ignoreCase = true) ||
                            context.translation[it.key.propertyDescription()].contains(keyword, ignoreCase = true)
                    )
                }.map { (it.key to it.value).toPropertyPair() }

                PropertiesView(
                    properties = properties,
                    stateKey = "${routeInfo.id}:search:$keyword",
                    isSearchResults = true,
                    searchKeyword = keyword,
                    enableGlobalSearch = true,
                    onBack = { navigateToMainRoot() }
                )
            }
        }

    }

    @Composable
    internal fun FeatureAuroraBackdrop(modifier: Modifier = Modifier) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(skin.backgroundGradient)
        )
    }

    @Composable
    internal fun NoticeBadge(text: String, color: Color) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val bgAlpha = if (skin.isDark) 0.16f else 0.32f
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = bgAlpha),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
        ) {
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }

    @Composable
    internal fun EmptyState(isSearchResults: Boolean) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val isAether = skin.id == "AETHER"
        val shape = RoundedCornerShape(24.dp)
        val border = if (isAether) SolidColor(skin.laserBorder.copy(alpha = 0.45f)) else Brush.linearGradient(
            listOf(
                skin.glowPrimary.copy(alpha = 0.45f),
                skin.glowSecondary.copy(alpha = 0.35f)
            )
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .aetherGlass(skin, 24.dp),
            shape = shape,
            color = Color.Transparent,
            tonalElevation = 10.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, border)
        ) {
            Box(modifier = Modifier.background(skin.cardOverlayColor.copy(alpha = 0.1f), shape)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = skin.glowPrimary.copy(alpha = 0.18f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = skin.textPrimary
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isSearchResults) "No matches found" else "Nothing to show",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = if (isSearchResults) "Try a different keyword or clear filters." else "Toggle visibility with the new controls above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = skin.textPrimary.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    internal fun PropertyAction(
        property: PropertyPair<*>,
        configRefreshNonce: Int,
        onConfigChanged: () -> Unit,
        isInteractionEnabled: Boolean = true,
        registerClickCallback: (() -> Unit) -> (() -> Unit)
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        var showDialog by remember { mutableStateOf(false) }
        var dialogComposable by remember { mutableStateOf<@Composable () -> Unit>({}) }
        var showRandomProfileProgressDialog by remember { mutableStateOf(false) }
        var randomProfileStatus by remember { mutableStateOf("") }
        var showCurrentRandomProfileDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        fun registerDialogOnClickCallback() = registerClickCallback { showDialog = true }

        if (showDialog) {
            Dialog(
                properties = DialogProperties(
                    usePlatformDefaultWidth = false
                ),
                onDismissRequest = { showDialog = false },
            ) {
                dialogComposable()
            }
        }

        val propertyValue = property.value
        val randomProfileEnabled = isRandomizedProfileEnabled()
        val isRandomizedProfileContainer = property.name == "randomize_device_profile"
        fun persistConfig() {
            context.config.writeConfig()
            context.mirrorRedditFeaturePrefs()
            context.mirrorWhatsAppFeaturePrefs()
            context.mirrorInstagramFeaturePrefs()
            onConfigChanged()
        }

        if (showRandomProfileProgressDialog) {
            AestheticDialog(
                onDismissRequest = {},
                title = context.translation["manager.dialogs.randomize_device_profile.title"]
                    ?: "Generating random device profile",
                text = randomProfileStatus,
                icon = Icons.Filled.AutoAwesome,
                confirmButtonText = "",
                onConfirm = {},
                loading = true,
                showIcon = false,
                showCloseButton = false,
                confirmEnabled = false
            )
        }

        if (showCurrentRandomProfileDialog) {
            val profileSnapshot = getRandomizedProfileSnapshot()
            val clipboardManager = LocalClipboardManager.current
            val parsedProfile = remember(profileSnapshot) {
                runCatching { RandomizedDeviceProfile.fromJson(profileSnapshot) }.getOrNull()
            }

            AestheticDialog(
                onDismissRequest = { showCurrentRandomProfileDialog = false },
                title = context.translation["manager.dialogs.randomize_device_profile.view_title"]
                    ?: "Current randomized profile",
                text = "",
                icon = Icons.Filled.Visibility,
                dismissButtonText = context.translation["button.copy"] ?: "Copy",
                onDismiss = {
                    clipboardManager.setText(AnnotatedString(profileSnapshot))
                    context.shortToast(
                        context.translation["manager.dialogs.randomize_device_profile.copied"]
                            ?: "Randomized profile copied"
                    )
                },
                confirmButtonText = context.translation["button.positive"],
                onConfirm = { showCurrentRandomProfileDialog = false },
                showCloseButton = false,
                customContent = {
                    if (parsedProfile != null) {
                        RandomizedProfileViewer(
                            profile = parsedProfile,
                            onCopy = { textToCopy ->
                                clipboardManager.setText(AnnotatedString(textToCopy))
                                context.shortToast("Copied to clipboard")
                            }
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = profileSnapshot,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                color = skin.textSecondary,
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            )
        }

        if (property.key.params.flags.contains(ConfigFlag.USER_IMPORT)) {
            registerDialogOnClickCallback()
            dialogComposable = {
                var isEmpty by remember { mutableStateOf(false) }
                val files = rememberAsyncMutableStateList(defaultValue = listOf()) {
                    context.fileHandleManager.getStoredFiles {
                        property.key.params.filenameFilter?.invoke(it.name) == true
                    }.also {
                        isEmpty = it.isEmpty()
                        if (isEmpty) {
                            propertyValue.setAny(null)
                            persistConfig()
                        }
                    }
                }
                var selectedFile by remember(files.size) { mutableStateOf(files.firstOrNull { it.name == propertyValue.getNullable() }.also {
                    if (files.isNotEmpty() && it == null) propertyValue.setAny(null)
                    if (files.isNotEmpty() && it == null) persistConfig()
                }?.name) }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                skin.glowPrimary.copy(alpha = 0.45f),
                                skin.glowSecondary.copy(alpha = 0.35f)
                            )
                        )
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .background(skin.cardOverlayColor, RoundedCornerShape(24.dp))
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = context.translation["manager.dialogs.file_imports.settings_select_file_hint"] ?: "Select File",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = skin.textPrimary
                                    )
                                    if (isEmpty) {
                                        Text(
                                            text = context.translation["manager.dialogs.file_imports.no_files_settings_hint"] ?: "No files found",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = skin.textPrimary.copy(alpha = 0.75f),
                                            modifier = Modifier.padding(top = 4.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    } else {
                                        Text(
                                            text = translation["manager.dialogs.file_imports.settings_select_file_subtitle"] ?: "Pick a file to import",
                                            fontSize = 13.sp,
                                            color = skin.textPrimary.copy(alpha = 0.6f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            items(files, key = { it.name }) { file ->
                                val isSelected = selectedFile == file.name
                                val fileContent = rememberAsyncMutableState(defaultValue = "") {
                                    if (isSelected) {
                                        context.fileHandleManager.readFile(file.name) ?: ""
                                    } else ""
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedFile = if (isSelected) null else file.name
                                                propertyValue.setAny(selectedFile)
                                                persistConfig()
                                            },
                                        shape = RoundedCornerShape(16.dp),
                                        color = skin.textPrimary.copy(alpha = 0.05f),
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) Brush.linearGradient(
                                                listOf(
                                                    skin.glowPrimary.copy(alpha = 0.6f),
                                                    skin.glowSecondary.copy(alpha = 0.48f)
                                                )
                                            ) else SolidColor(skin.textPrimary.copy(alpha = 0.08f))
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = skin.glowPrimary.copy(alpha = 0.16f)
                                            ) {
                                                Icon(
                                                    Icons.Filled.AttachFile,
                                                    contentDescription = null,
                                                    modifier = Modifier.padding(10.dp),
                                                    tint = skin.textPrimary
                                                )
                                            }
                                            Text(
                                                text = file.name,
                                                modifier = Modifier.weight(1f),
                                                fontSize = 14.sp,
                                                lineHeight = 16.sp,
                                                color = skin.textPrimary
                                            )
                                            if (isSelected) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = skin.glowSecondary.copy(alpha = 0.18f)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.padding(8.dp),
                                                        tint = skin.glowSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (isSelected && fileContent.value.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                                                .fillMaxWidth()
                                        ) {
                                            ConfigPreviewer(configJson = fileContent.value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return
        }

        if (property.key.params.flags.contains(ConfigFlag.FOLDER)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = isInteractionEnabled,
                    onClick = registerClickCallback {
                        routes.activityLauncher.chooseFolder { uri ->
                            if (uri.isBlank()) return@chooseFolder
                            if (context.isInstagramMode && property.key.name == "downloaderCustomPath") {
                                propertyValue.setAny(getFolderReadablePath(context.androidContext, uri) ?: uri)
                                context.config.root.instagram.downloader.downloaderCustomUri.set(uri)
                            } else {
                                propertyValue.setAny(uri)
                            }
                            persistConfig()
                        }
                    }
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Color.White)
                }
                if (context.isInstagramMode && property.key.name == "downloaderCustomPath") {
                    IconButton(
                        enabled = isInteractionEnabled && propertyValue.getNullable()?.toString().orEmpty().isNotBlank(),
                        onClick = {
                            propertyValue.setAny("")
                            context.config.root.instagram.downloader.downloaderCustomUri.set("")
                            persistConfig()
                            context.shortToast(
                                context.translation["features.properties.instagram.properties.downloader.download_folder_reset_toast"]
                                    ?: "Download folder reset to Default"
                            )
                        }
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = Color(0xFFFF453A))
                    }
                }
            }
            return
        }

        when (val dataType = remember { property.key.dataType.type }) {
            DataProcessors.Type.BOOLEAN -> {
                var state by remember(configRefreshNonce) { mutableStateOf(propertyValue.get() as Boolean) }
                var showExtremeConfirm by remember { mutableStateOf(false) }
                val hapticFeedback = LocalHapticFeedback.current

                fun commitBooleanChange(requestedState: Boolean) {
                    state = requestedState
                    propertyValue.setAny(requestedState)
                    if (context.isInstagramMode) {
                        applyInstagramBooleanSideEffects(property.key.name, requestedState)
                    }
                    persistConfig()
                }

                if (showExtremeConfirm) {
                    AestheticDialog(
                        onDismissRequest = { showExtremeConfirm = false },
                        title = context.translation["features.properties.instagram.properties.distraction_free.extreme_title"]
                            ?: "Activate Extreme Mode?",
                        text = context.translation["features.properties.instagram.properties.distraction_free.extreme_message"]
                            ?: "Once activated, you cannot disable Distraction-Free Mode until you reinstall the app. Continue?",
                        icon = Icons.Filled.Warning,
                        dismissButtonText = context.translation["button.negative"] ?: "Cancel",
                        onDismiss = { showExtremeConfirm = false },
                        confirmButtonText = context.translation["button.positive"] ?: "Yes",
                        onConfirm = {
                            showExtremeConfirm = false
                            commitBooleanChange(true)
                        }
                    )
                }

                Switch(
                    checked = state,
                    onCheckedChange = { requestedState ->
                        if (!isInteractionEnabled) return@Switch
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        if (
                            context.isInstagramMode &&
                            property.key.name == "isExtremeMode" &&
                            requestedState &&
                            !state
                        ) {
                            showExtremeConfirm = true
                            return@Switch
                        }
                        commitBooleanChange(requestedState)
                    },
                    enabled = isInteractionEnabled,
                    colors = purrfectSwitchColors()
                )
            }

            DataProcessors.Type.MAP_COORDINATES -> {
                registerDialogOnClickCallback()
                dialogComposable = {
                    alertDialogs.ChooseLocationDialog(property) {
                        showDialog = false
                    }
                }

                Text(
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.widthIn(0.dp, 120.dp).clickable { showDialog = true },
                    color = skin.textPrimary,
                    text = (propertyValue.get() as Pair<*, *>).let {
                        "${it.first.toString().toFloatOrNull() ?: 0F}, ${it.second.toString().toFloatOrNull() ?: 0F}"
                    }
                )
            }

            DataProcessors.Type.STRING_UNIQUE_SELECTION -> {
                registerDialogOnClickCallback()

                dialogComposable = {
                    alertDialogs.UniqueSelectionDialog(property)
                }

                Text(
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.widthIn(0.dp, 120.dp).clickable { showDialog = true },
                    color = skin.textPrimary,
                    text = (propertyValue.getNullable() as? String ?: "null").let {
                        property.key.propertyOption(context.translation, it)
                    }
                )
            }

            DataProcessors.Type.STRING_MULTIPLE_SELECTION, DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> {
                if (dataType == DataProcessors.Type.STRING && isRandomizedProfileActionProperty(property.name)) {
                    val actionLabel = when (property.name) {
                        "generate_fresh_profile_action" -> context.translation[property.key.propertyName()] ?: "Generate Fresh Profile"
                        "view_current_profile_action" -> context.translation[property.key.propertyName()] ?: "View Current Profile"
                        "backup_profile_action" -> context.translation[property.key.propertyName()] ?: "Backup Profile"
                        "restore_profile_action" -> context.translation[property.key.propertyName()] ?: "Restore Profile"
                        else -> property.name
                    }
                    Button(
                        onClick = {
                            if (property.name == "generate_fresh_profile_action") {
                                showRandomProfileProgressDialog = true
                                coroutineScope.launch {
                                    randomProfileStatus = context.translation["manager.dialogs.randomize_device_profile.phase.allocating"]
                                        ?: "Allocating a randomized device fingerprint"
                                    delay(260)
                                    requestFreshRandomizedProfile()
                                    persistConfig()
                                    randomProfileStatus = context.translation["manager.dialogs.randomize_device_profile.phase.finalizing"]
                                        ?: "Finalizing the all-in-one profile and disabling manual overrides"
                                    delay(260)
                                    showRandomProfileProgressDialog = false
                                    context.shortToast(
                                        context.translation["manager.dialogs.randomize_device_profile.refresh_requested"]
                                            ?: "Fresh randomized profile requested. Restart Snapchat to apply it."
                                    )
                                }
                            } else if (property.name == "view_current_profile_action") {
                                showCurrentRandomProfileDialog = true
                            } else if (property.name == "backup_profile_action") {
                                backupRandomizedProfile(onConfigChanged)
                            } else if (property.name == "restore_profile_action") {
                                restoreRandomizedProfile(onConfigChanged)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.glowPrimary.copy(alpha = 0.28f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Text(actionLabel, maxLines = 1)
                    }
                    return
                }

                dialogComposable = {
                    when (dataType) {
                        DataProcessors.Type.STRING_MULTIPLE_SELECTION -> {
                            alertDialogs.MultipleSelectionDialog(property)
                        }
                        DataProcessors.Type.STRING, DataProcessors.Type.INTEGER, DataProcessors.Type.FLOAT -> {
                            val isMessageListProperty = property.key.name.endsWith("_messages")
                            val isSleepWindowProperty = property.key.name.contains("sleep_window")
                            val isSnapchatPlusPurchaseDateProperty = property.key.name == "snapchat_plus_purchase_date"

                            if (isMessageListProperty) {
                                alertDialogs.MessageListPropertyDialog(property) { showDialog = false }
                            } else if (isSleepWindowProperty) {
                                alertDialogs.AutoOpenScheduleDialog(property as PropertyPair<String>) { showDialog = false }
                            } else if (isSnapchatPlusPurchaseDateProperty) {
                                alertDialogs.DatePickerPropertyDialog(property) { showDialog = false }
                            } else if (context.isInstagramMode && property.key.name in instagramSelectionStringProperties) {
                                InstagramSelectionStringDialog(
                                    property = property as PropertyPair<String>,
                                    onDismiss = { showDialog = false },
                                    onPersist = {
                                        persistConfig()
                                        showDialog = false
                                    }
                                )
                            } else {
                                alertDialogs.KeyboardInputDialog(property) { showDialog = false }
                            }
                        }
                        else -> {}
                    }
                }

                val click = registerDialogOnClickCallback()
                if (dataType == DataProcessors.Type.INTEGER ||
                    dataType == DataProcessors.Type.FLOAT) {
                    ValueGlowChip(
                        text = propertyValue.get().toString(),
                        onClick = click
                    )
                } else {
                    val isMessageListProperty = property.key.name.endsWith("_messages")
                    val isSnapchatPlusPurchaseDateProperty = property.key.name == "snapchat_plus_purchase_date"
                    if (isMessageListProperty) {
                        val messageCount = try {
                            val messageList: List<String> = gson.fromJson(propertyValue.get().toString(), listTypeToken) ?: emptyList()
                            messageList.size
                        } catch (e: Exception) {
                            1
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = skin.textPrimary.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                            modifier = Modifier.clickable { click() }
                        ) {
                            Text(
                                text = translation.format("search_results_count", "count" to messageCount.toString()),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.textPrimary
                            )
                        }
                    } else if (isSnapchatPlusPurchaseDateProperty) {
                        Button(
                            onClick = click,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = skin.glowPrimary.copy(alpha = 0.28f),
                                contentColor = skin.textPrimary
                            )
                        ) {
                            Text(translation["button.set"] ?: "Set")
                        }
                    } else {
                        IconButton(onClick = click) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = skin.textPrimary)
                        }
                    }
                }
            }

            DataProcessors.Type.INT_COLOR -> {
                dialogComposable = {
                    alertDialogs.ColorPickerPropertyDialog(property) {
                        showDialog = false
                    }
                }

                val click = registerDialogOnClickCallback()
                CircularAlphaTile(selectedColor = (propertyValue.getNullable() as? Int)?.let { Color(it) })
            }

            DataProcessors.Type.CONTAINER -> {
                val container = propertyValue.get() as ConfigContainer

                registerClickCallback {
                    routes.navController.navigate(FEATURE_CONTAINER_ROUTE.replace("{name}", property.name))
                }

                if (!container.hasGlobalState) return

                var state by remember { mutableStateOf(instagramContainerGlobalState(property.name, container)) }

                Box(
                    modifier = Modifier
                        .padding(end = 15.dp),
                ) {

                    Box(modifier = Modifier
                        .height(50.dp)
                        .width(1.dp)
                        .background(
                            color = skin.glowPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(5.dp)
                        ))
                }

                val hapticFeedback = LocalHapticFeedback.current
                Switch(
                    checked = state,
                    onCheckedChange = { requestedState ->
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        if (isRandomizedProfileContainer && requestedState) {
                            showRandomProfileProgressDialog = true
                            coroutineScope.launch {
                                randomProfileStatus = context.translation["manager.dialogs.randomize_device_profile.phase.allocating"]
                                    ?: "Allocating a randomized device fingerprint"
                                delay(260)
                                randomProfileStatus = context.translation["manager.dialogs.randomize_device_profile.phase.network"]
                                    ?: "Preparing network, locale, and telephony values"
                                delay(260)
                                container.globalState = true
                                state = true
                                persistConfig()
                                randomProfileStatus = context.translation["manager.dialogs.randomize_device_profile.done"]
                                    ?: "Randomized device profile generated"
                                delay(220)
                                showRandomProfileProgressDialog = false
                                context.log.info("Enabled randomized device profile mode from manager UI")
                            }
                            return@Switch
                        }
                        state = requestedState
                        container.globalState = requestedState
                        syncInstagramContainerGlobalState(property.name, requestedState)
                        if (!requestedState && isRandomizedProfileContainer) {
                            context.log.info("Disabled randomized device profile mode from manager UI")
                        }
                        persistConfig()
                    },
                    colors = purrfectSwitchColors()
                )
            }
        }

    }

    @Composable
    internal fun ValueGlowChip(
        text: String,
        onClick: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            shape = CircleShape,
            color = skin.textPrimary.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        skin.glowPrimary.copy(alpha = 0.55f),
                        skin.glowSecondary.copy(alpha = 0.45f)
                    )
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                skin.glowPrimary.copy(alpha = 0.28f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = skin.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    internal fun PropertyCard(
        property: PropertyPair<*>,
        configRefreshNonce: Int,
        onConfigChanged: () -> Unit,
        onOpen: (() -> Unit)? = null
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        var clickCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        val noticeColorMap = remember {
            mapOf(
                FeatureNotice.UNSTABLE.key to Color(0xFFFFFB87),
                FeatureNotice.BAN_RISK.key to Color(0xFFFF8585),
                FeatureNotice.INTERNAL_BEHAVIOR.key to Color(0xFFFFFB87),
            )
        }

        val versionCheck = remember { property.key.params.versionCheck }
        val versionCheckPair = remember(property) { versionCheck?.checkVersion(context.installationSummary.snapchatInfo?.versionCode ?: return@remember null)}
        val isComponentDisabled = remember { versionCheckPair != null && versionCheck?.isDisabled == true }
        val isInstagramConditionalDisabled = context.isInstagramMode &&
            isInstagramDistractionPropertyDisabled(property.key.name)
        val isInteractionEnabled = !isComponentDisabled && !isInstagramConditionalDisabled

        val cardShape = RoundedCornerShape(22.dp)
        val interactionSource = remember { MutableInteractionSource() }
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        val cardBackground = skin.cardOverlay

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .graphicsLayer { if (!isInteractionEnabled) alpha = 0.5f }
                .clickable(
                    enabled = isInteractionEnabled,
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    onOpen?.invoke()
                    clickCallback?.invoke()
                }
                .scaleOnPress(interactionSource),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(cardBackground, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    property.key.params.icon?.let { icon ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = skin.glowPrimary.copy(alpha = 0.16f),
                            tonalElevation = 0.dp,
                            modifier = Modifier.size(62.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                skin.glowPrimary.copy(alpha = 0.35f),
                                                skin.glowSecondary.copy(alpha = 0.28f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = skin.textPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                            Text(
                                text = context.translation[property.key.propertyName()] ?: property.key.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = skin.textPrimary,
                                lineHeight = 20.sp
                            )
                            Text(
                                text = context.translation[property.key.propertyDescription()] ?: "",
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                color = skin.textSecondary
                            )

                        if (property.key.params.notices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                property.key.params.notices.forEach {
                                    NoticeBadge(
                                        text = context.translation["features.notices.${it.key}"] ?: it.key,
                                        color = noticeColorMap[it.key] ?: Color(0xFFFFFB87)
                                    )
                                }
                            }
                        }

                        if (versionCheckPair != null) {
                            NoticeBadge(
                                text = context.translation.format(
                                    "manager.sections.features.${versionCheckPair.second.key}",
                                    "version" to versionCheckPair.first.first
                                ),
                                color = Color(0xFFFF8585)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        PropertyAction(property, configRefreshNonce = configRefreshNonce, onConfigChanged = onConfigChanged, registerClickCallback = { callback ->
                            if (property.key.propertyTranslationPath().startsWith("rules.properties")) {
                                clickCallback = {
                                    routes.manageRuleFeature.navigate {
                                        put("rule_type", property.key.name)
                                    }
                                }
                                return@PropertyAction clickCallback!!
                            }
                            clickCallback = callback
                            callback
                        }, isInteractionEnabled = isInteractionEnabled)
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {}

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    internal fun FloatingControls(
        isSearchResults: Boolean,
        activeSectionTitle: String? = null,
        activeSectionSubtitle: String? = null,
        searchKeyword: String? = null,
        searchHistory: SnapshotStateList<String>,
        onSearchQueryChange: (String) -> Unit,
        onBack: (() -> Unit)? = null,
        scrollOffset: Int = 0,
        modifier: Modifier = Modifier,
        onHeightMeasured: (androidx.compose.ui.unit.Dp) -> Unit = {}
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        var showSearchBar by rememberSaveable { mutableStateOf(isSearchResults) }
        val focusRequester = remember { FocusRequester() }
        val isOverlay = activeSectionTitle != null
        var searchValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(
                    text = searchKeyword.orEmpty(),
                    selection = TextRange(searchKeyword?.length ?: 0)
                )
            )
        }
        val searchEntries = remember { buildSearchEntries() }
        val combinedSuggestions by remember(searchValue.text, searchHistory, searchEntries) {
            derivedStateOf {
                val query = searchValue.text
                val historyMatches = searchHistory.filter {
                    query.isBlank() || it.contains(query, ignoreCase = true)
                }
                val fuzzy = fuzzySuggest(query, searchEntries)
                (historyMatches + fuzzy).distinct().take(6)
            }
        }

        fun updateSearch(keyword: String, record: Boolean = true) {
            val term = keyword.trim()
            onSearchQueryChange(term)
            if (record && term.isNotEmpty()) upsertHistory(term, searchHistory)
        }

        var showExportDropdownMenu by remember { mutableStateOf(false) }

        if (showResetConfirmationDialog) {
            val haptic = LocalHapticFeedback.current
            Dialog(onDismissRequest = { showResetConfirmationDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = skin.cardOverlayColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 16.dp,
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    skin.glowPrimary.copy(alpha = 0.55f),
                                    skin.glowSecondary.copy(alpha = 0.45f)
                                )
                            )
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = context.translation["manager.dialogs.reset_config.title"] ?: "Reset Config",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = skin.textPrimary
                            )
                            Text(
                                text = context.translation["manager.dialogs.reset_config.content"] ?: "Reset all settings to default?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.textSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                            ) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showResetConfirmationDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                        contentColor = skin.textPrimary
                                    )
                                ) {
                                    Text(text = context.translation["button.negative"])
                                }
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        context.resetActiveTargetConfig()
                                        context.shortToast(context.translation["manager.dialogs.reset_config.success_toast"] ?: "Reset successful")
                                        showResetConfirmationDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = skin.glowPrimary.copy(alpha = 0.32f),
                                        contentColor = skin.textPrimary
                                    )
                                ) {
                                    Text(text = context.translation["button.positive"])
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showExportDialog) {
            SensitiveDataDialog(
                onDismiss = { showExportDialog = false },
                onConfirm = { exportSensitiveData, includeSavedLocations ->
                    showExportDialog = false
                    routes.configExportSummary.navigate {
                        put("exportSensitiveData", exportSensitiveData.toString())
                        put("includeSavedLocations", includeSavedLocations.toString())
                    }
                }
            )
        }

        val haptic = LocalHapticFeedback.current
        val actions = remember {
            listOf(
                Triple(translation["export_option"] ?: "Export", Icons.Filled.SaveAlt) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (context.isRedditMode) {
                        routes.configExportSummary.navigate {
                            put("exportSensitiveData", "false")
                            put("includeSavedLocations", "false")
                        }
                    } else {
                        showExportDialog = true
                    }
                },
                Triple(translation["import_option"] ?: "Import", Icons.Filled.FileDownload) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activityLauncher {
                        openFile("application/json") { uriString ->
                            runCatching {
                                val uri = android.net.Uri.parse(uriString)
                                context.androidContext.contentResolver.openInputStream(uri)?.use {
                                    routes.configJsonForImport = it.readBytes().toString(Charsets.UTF_8)
                                    routes.configImportConfirmation.navigate()
                                }
                            }.onFailure { err ->
                                context.log.error("Failed to read config file", err)
                                context.longToast(translation.format("config_import_failure_toast", "error" to (err.message ?: "Unknown")))
                            }
                        }
                    }
                },
                Triple(translation["reset_option"] ?: "Reset", Icons.Filled.Refresh) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showResetConfirmationDialog = true
                }
            )
        }

        val headerTitle = activeSectionTitle ?: translation["manager.routes.features"] ?: "Features"
        val subtitleText = when {
            isSearchResults -> translation["search_button"] ?: "Search"
            !activeSectionSubtitle.isNullOrBlank() -> activeSectionSubtitle
            else -> translation["manager.sections.features.subtitle"] ?: ""
        }

        LaunchedEffect(isSearchResults, searchKeyword) {
            if (isSearchResults || !searchKeyword.isNullOrBlank()) {
                showSearchBar = true
                if (!searchKeyword.isNullOrBlank()) {
                    val text = searchKeyword
                    searchValue = TextFieldValue(
                        text = text,
                        selection = TextRange(text.length)
                    )
                }
            }
        }

        Column(modifier = modifier.headerHeightTracker { onHeightMeasured(it) }) {
            if (isAphelion) {
                me.eternal.purrfect.ui.manager.components.FloatingTopBar(
                    title = headerTitle,
                    subtitle = if (showSearchBar) null else subtitleText,
                    onBack = onBack,
                    scrollOffset = scrollOffset,
                    enableMorph = true,
                    actions = {
                        if (showSearchBar) {
                            TextField(
                                value = searchValue,
                                onValueChange = { keywordValue ->
                                    searchValue = keywordValue
                                    if (keywordValue.text.isEmpty()) {
                                        updateSearch("", record = false)
                                    } else {
                                        updateSearch(keywordValue.text, record = false)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                placeholder = { Text(text = translation["search_button"] ?: "Search", color = skin.textPrimary.copy(alpha = 0.75f)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = skin.textPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (searchValue.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            searchValue = TextFieldValue("", TextRange(0))
                                            updateSearch("", record = false)
                                            if (isSearchResults) {
                                                if (isOverlay) {
                                                    routes.navController.popBackStack(routeInfo.id, false)
                                                }
                                            } else {
                                                showSearchBar = false
                                            }
                                        }) {
                                            Icon(Icons.Filled.Close, contentDescription = null, tint = skin.textPrimary)
                                        }
                                    }
                                },
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        updateSearch(searchValue.text, record = true)
                                    }
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = skin.textPrimary,
                                    focusedTextColor = skin.textPrimary,
                                    unfocusedTextColor = skin.textPrimary,
                                    disabledTextColor = skin.textPrimary.copy(alpha = 0.65f),
                                    focusedPlaceholderColor = skin.textPrimary.copy(alpha = 0.75f),
                                    unfocusedPlaceholderColor = skin.textPrimary.copy(alpha = 0.75f),
                                    focusedLeadingIconColor = skin.textPrimary,
                                    unfocusedLeadingIconColor = skin.textPrimary.copy(alpha = 0.9f),
                                    focusedTrailingIconColor = skin.textPrimary,
                                    unfocusedTrailingIconColor = skin.textPrimary.copy(alpha = 0.9f)
                                )
                            )
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        } else {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showSearchBar = true
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = skin.textPrimary
                                )
                            }
                        }

                        if (context.activity != null) {
                            Box {
                                IconButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showExportDropdownMenu = !showExportDropdownMenu
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = null,
                                        tint = skin.textPrimary
                                    )
                                }
                                DropdownMenu(
                                    expanded = showExportDropdownMenu,
                                    onDismissRequest = { showExportDropdownMenu = false },
                                    offset = DpOffset(0.dp, 8.dp),
                                    containerColor = skin.cardOverlayColor,
                                    shape = RoundedCornerShape(14.dp),
                                    tonalElevation = 8.dp,
                                    shadowElevation = 12.dp
                                ) {
                                    actions.forEach { (name, icon, action) ->
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = skin.textPrimary
                                                )
                                            },
                                            text = { Text(text = name ?: "", color = skin.textPrimary) },
                                            onClick = {
                                                action()
                                                showExportDropdownMenu = false
                                            },
                                            colors = MenuDefaults.itemColors(
                                                textColor = skin.textPrimary,
                                                leadingIconColor = skin.glowPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            } else {
                val topBarShape = RoundedCornerShape(26.dp)
                val topBarBackground = remember { skin.cardOverlay }
                val topBarBorder = remember {
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.55f),
                            skin.glowSecondary.copy(alpha = 0.35f)
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .zIndex(1f)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = topBarShape,
                        color = skin.cardOverlayColor,
                        border = BorderStroke(1.dp, topBarBorder),
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(topBarShape)
                                    .background(topBarBackground)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (onBack != null) {
                                    IconButton(onClick = onBack) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = context.translation["common.back"],
                                            tint = skin.textPrimary
                                        )
                                    }
                                }
                                
                                if (showSearchBar) {
                                    TextField(
                                        value = searchValue,
                                        onValueChange = { keywordValue ->
                                            searchValue = keywordValue
                                            updateSearch(keywordValue.text, record = false)
                                        },
                                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                        singleLine = true,
                                        placeholder = { Text(text = translation["search_button"] ?: "Search", color = skin.textPrimary.copy(alpha = 0.7f)) },
                                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = skin.textPrimary, focusedTextColor = skin.textPrimary, unfocusedTextColor = skin.textPrimary)
                                    )
                                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                } else {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = headerTitle,
                                            color = skin.textPrimary,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            text = subtitleText,
                                            color = skin.textPrimary.copy(alpha = 0.8f),
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showSearchBar = !showSearchBar
                                    }) {
                                        Icon(
                                            imageVector = if (showSearchBar) Icons.Filled.Close else Icons.Filled.Search,
                                            contentDescription = null,
                                            tint = skin.textPrimary
                                        )
                                    }

                                    if (context.activity != null) {
                                        Box {
                                            IconButton(onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showExportDropdownMenu = !showExportDropdownMenu
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.MoreVert,
                                                    contentDescription = null,
                                                    tint = skin.textPrimary
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showExportDropdownMenu,
                                                onDismissRequest = { showExportDropdownMenu = false },
                                                offset = DpOffset(0.dp, 8.dp),
                                                containerColor = skin.cardOverlayColor,
                                                shape = RoundedCornerShape(14.dp),
                                                tonalElevation = 8.dp,
                                                shadowElevation = 12.dp
                                            ) {
                                                actions.forEach { (name, icon, action) ->
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = icon,
                                                                contentDescription = null,
                                                                tint = skin.glowPrimary
                                                            )
                                                        },
                                                        text = { Text(text = name ?: "", color = skin.textPrimary) },
                                                        onClick = {
                                                            action()
                                                            showExportDropdownMenu = false
                                                        },
                                                        colors = MenuDefaults.itemColors(
                                                            textColor = skin.textPrimary,
                                                            leadingIconColor = skin.glowPrimary
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showSearchBar && combinedSuggestions.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(top = 10.dp)
                        .fillMaxWidth(),
                    color = skin.cardOverlayColor,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            combinedSuggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        searchValue = TextFieldValue(suggestion, TextRange(suggestion.length))
                                        updateSearch(suggestion, record = true)
                                    },
                                    label = { Text(text = suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = skin.textPrimary) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                        labelColor = skin.textPrimary,
                                        leadingIconContentColor = skin.textPrimary
                                    )
                                )
                            }
                        }
                        if (searchHistory.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        searchHistory.clear()
                                        saveSearchHistory(emptyList())
                                    }
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = skin.textPrimary.copy(alpha = 0.85f))
                                    Spacer(Modifier.width(6.dp))
                                    Text(text = translation["clear_history"] ?: "Clear history", color = skin.textPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun PropertiesView(
        properties: List<PropertyPair<*>>,
        stateKey: String,
        isSearchResults: Boolean = false,
        activeSectionTitle: String? = null,
        activeSectionSubtitle: String? = null,
        searchKeyword: String? = null,
        enableGlobalSearch: Boolean = false,
        showWhatsAppHiddenUiElementsManager: Boolean = false,
        showSnapchatCustomThemeManager: Boolean = false,
        showInstagramDeveloperTools: Boolean = false,
        showInstagramAdsAndLinksTools: Boolean = false,
        showInstagramGhostSettingsTools: Boolean = false,
        showInstagramQuickToggleTools: Boolean = false,
        showInstagramDistractionFreeTools: Boolean = false,
        showInstagramMiscTools: Boolean = false,
        showInstagramDownloaderTools: Boolean = false,
        showInstagramEmojiFontTools: Boolean = false,
        onBack: (() -> Unit)? = null
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val density = LocalDensity.current
        var controlsHeight by remember { mutableStateOf(100.dp) }
        var configRefreshNonce by rememberSaveable { mutableStateOf(0) }
        
        val listState = rememberLazyListState()

        val sharedSearchHistory = remember { mutableStateListOf<String>().apply { addAll(loadSearchHistory()) } }
        var liveSearchQuery by rememberSaveable { mutableStateOf(searchKeyword.orEmpty()) }
        val isActiveSearch = isSearchResults || liveSearchQuery.isNotBlank()
        val globalSearchProperties = remember(enableGlobalSearch) {
            if (enableGlobalSearch) {
                allProperties.filter { isSearchVisibleProperty(it.key) }.map { (it.key to it.value).toPropertyPair() } as List<PropertyPair<Any>>
            } else {
                emptyList()
            }
        }
        val displayProperties = remember(properties, globalSearchProperties, liveSearchQuery, enableGlobalSearch) {
            val query = liveSearchQuery
            if (query.isBlank()) return@remember properties
            val candidates = if (enableGlobalSearch) (properties + globalSearchProperties) else properties
            candidates.filter {
                it.key.name.contains(query, ignoreCase = true) ||
                    context.translation[it.key.propertyName()].contains(query, ignoreCase = true) ||
                    context.translation[it.key.propertyDescription()].contains(query, ignoreCase = true)
            }
        }

        LaunchedEffect(searchKeyword) {
            if (searchKeyword != null && searchKeyword != liveSearchQuery) {
                liveSearchQuery = searchKeyword
            }
        }
        var lastAppliedQuery by remember { mutableStateOf(liveSearchQuery) }
        LaunchedEffect(liveSearchQuery) {
            if (liveSearchQuery != lastAppliedQuery) {
                if (!listState.isScrollInProgress) {
                    listState.scrollToItem(0)
                }
                lastAppliedQuery = liveSearchQuery
            }
        }

        val computedScrollOffset by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt()
                else listState.firstVisibleItemScrollOffset
            }
        }

        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { computedScrollOffset }.collect {
                routes.navigation?.globalScrollOffset = it
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            FeatureAuroraBackdrop()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 6.dp,
                    top = controlsHeight,
                    bottom = routes.bottomPadding
                )
            ) {
                if (displayProperties.isEmpty()) {
                    item { EmptyState(isActiveSearch) }
                } else {
                    if (showInstagramQuickToggleTools && !isActiveSearch) {
                        item(key = "instagram_quick_toggle_master") {
                            InstagramQuickToggleMasterCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                    }
                    if (showInstagramMiscTools && !isActiveSearch) {
                        item(key = "instagram_misc_master") {
                            InstagramMiscMasterCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                    }
                    if (showInstagramDownloaderTools && !isActiveSearch) {
                        item(key = "instagram_downloader_master") {
                            InstagramDownloaderMasterCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                        item(key = "instagram_reel_download_mode") {
                            InstagramReelDownloadModeCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                    }
                    if (showInstagramEmojiFontTools && !isActiveSearch) {
                        item(key = "instagram_emoji_font_tools") {
                            InstagramEmojiFontToolsCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                    }
                    displayProperties.forEach { propertyItem ->
                        item(key = propertyItem.key.propertyName()) {
                            val onOpen = if (isActiveSearch && liveSearchQuery.isNotBlank()) {
                                {
                                    upsertHistory(liveSearchQuery, sharedSearchHistory)
                                }
                            } else null
                            PropertyCard(
                                property = propertyItem,
                                configRefreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ },
                                onOpen = onOpen
                            )
                        }
                        if (showInstagramDeveloperTools && !isActiveSearch && propertyItem.key.name == "isDevEnabled") {
                            item(key = "instagram_developer_config_actions") {
                                InstagramDeveloperToolsCard(
                                    onConfigChanged = { configRefreshNonce++ }
                                )
                            }
                        }
                        if (showInstagramDistractionFreeTools && !isActiveSearch && propertyItem.key.name == "isExtremeMode") {
                            item(key = "instagram_distraction_master") {
                                InstagramDistractionFreeMasterCard(
                                    refreshNonce = configRefreshNonce,
                                    onConfigChanged = { configRefreshNonce++ }
                                )
                            }
                        }
                        if (showInstagramGhostSettingsTools && !isActiveSearch && propertyItem.key.name == "quick_toggle") {
                            item(key = "instagram_ghost_settings_master") {
                                InstagramGhostSettingsMasterCard(
                                    refreshNonce = configRefreshNonce,
                                    onConfigChanged = { configRefreshNonce++ }
                                )
                            }
                        }
                    }
                    if (showInstagramGhostSettingsTools && !isActiveSearch) {
                        item(key = "instagram_dm_mark_seen_control") {
                            InstagramDmMarkSeenControlCard(
                                refreshNonce = configRefreshNonce,
                                onConfigChanged = { configRefreshNonce++ }
                            )
                        }
                    }
                }
                if (showWhatsAppHiddenUiElementsManager && !isActiveSearch) {
                    item {
                        HiddenUiElementsManager(
                            refreshNonce = configRefreshNonce,
                            onConfigChanged = { configRefreshNonce++ }
                        )
                    }
                }
                if (showSnapchatCustomThemeManager && !isActiveSearch) {
                    item {
                        SnapchatCustomThemeManager(
                            refreshNonce = configRefreshNonce,
                            onConfigChanged = { configRefreshNonce++ }
                        )
                    }
                }
                if (showInstagramAdsAndLinksTools && !isActiveSearch) {
                    item {
                        InstagramAdsAndLinksToolsCard(
                            refreshNonce = configRefreshNonce,
                            onConfigChanged = { configRefreshNonce++ }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            FloatingControls(
                isSearchResults = isActiveSearch,
                activeSectionTitle = activeSectionTitle,
                activeSectionSubtitle = activeSectionSubtitle,
                searchKeyword = liveSearchQuery,
                searchHistory = sharedSearchHistory,
                onSearchQueryChange = { liveSearchQuery = it },
                onBack = onBack,
                scrollOffset = computedScrollOffset,
                onHeightMeasured = { controlsHeight = it }
            )
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {
        fun saveConfig() {
            context.coroutineScope.launch(Dispatchers.IO) {
                context.config.writeConfig()
                context.mirrorRedditFeaturePrefs()
                context.mirrorWhatsAppFeaturePrefs()
                context.mirrorInstagramFeaturePrefs()
                context.log.verbose("saved config!")
            }
        }

        OnLifecycleEvent { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveConfig()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                saveConfig()
            }
        }
    }

    @Composable
    internal fun SensitiveDataDialog(
        onDismiss: () -> Unit,
        onConfirm: (exportSensitiveData: Boolean, includeSavedLocations: Boolean) -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val haptic = LocalHapticFeedback.current
        Dialog(onDismissRequest = onDismiss) {
            val includeSavedLocations = remember { mutableStateOf(false) }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = skin.textPrimary.copy(alpha = 0.06f),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.55f),
                            skin.glowSecondary.copy(alpha = 0.45f)
                        )
                    )
                )
            ) {
                Box(modifier = Modifier.background(skin.cardOverlay)) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = context.translation["manager.dialogs.export_config.title"] ?: "Export Config",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = skin.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = context.translation["manager.dialogs.export_config.content"] ?: "Include sensitive data?",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = skin.textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = context.translation["include_saved_locations"] ?: "Include Saved Locations",
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.textPrimary
                            )
                            Switch(
                                checked = includeSavedLocations.value,
                                onCheckedChange = {
                                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    includeSavedLocations.value = it
                                },
                                colors = purrfectSwitchColors()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(context.translation["button.negative"])
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirm(true, includeSavedLocations.value)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = skin.glowPrimary.copy(alpha = 0.32f),
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(context.translation["button.positive"])
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun HiddenUiElementsManager(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        if (context.isInstagramMode) {
            InstagramHiddenUiElementsManager(refreshNonce, onConfigChanged)
            return
        }

        val entries = remember(refreshNonce) { hiddenUiElementEntries() }
        val translationPrefix = if (context.isWhatsAppMode) {
            "features.properties.whatsapp.properties.ui_elements"
        } else {
            "features.properties.user_interface.properties.ui_elements"
        }
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = context.translation["$translationPrefix.hidden_elements_title"]
                                ?: "Hidden Elements",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PurrfectPalette.textPrimary
                        )
                        TextButton(
                            onClick = { clearHiddenUiElements(onConfigChanged) },
                            enabled = entries.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = context.translation["$translationPrefix.unhide_all"]
                                    ?: "Unhide All"
                            )
                        }
                    }

                    if (entries.isEmpty()) {
                        Text(
                            text = context.translation["$translationPrefix.hidden_elements_empty"]
                                ?: "No hidden elements yet",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = PurrfectPalette.textSecondary
                        )
                    } else {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = entry.label,
                                        fontSize = 15.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PurrfectPalette.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = context.translation[
                                            if (entry.isSelector) {
                                                "$translationPrefix.selector_label"
                                            } else {
                                                "$translationPrefix.resource_id_label"
                                            }
                                        ] ?: if (entry.isSelector) "Exact selector" else "Resource ID",
                                        fontSize = 12.sp,
                                        color = PurrfectPalette.textSecondary
                                    )
                                }
                                Switch(
                                    checked = true,
                                    onCheckedChange = { checked ->
                                        if (!checked) removeHiddenUiElement(entry, onConfigChanged)
                                    },
                                    colors = purrfectSwitchColors()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun SnapchatCustomThemeManager(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val customTheme = context.config.root.userInterface.customTheme
        val coroutineScope = rememberCoroutineScope()
        var mapRefreshNonce by remember { mutableStateOf(0) }
        var isThemeMapperRefreshing by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != Constants.SNAPCHAT_THEME_SURFACE_MAP_UPDATED_ACTION) return
                    mapRefreshNonce++
                    context.log.verbose("[THEME MAP UI] received map update broadcast nonce=$mapRefreshNonce")
                }
            }
            val filter = IntentFilter(Constants.SNAPCHAT_THEME_SURFACE_MAP_UPDATED_ACTION)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.androidContext.registerReceiver(receiver, filter)
                }
            }.onFailure {
                context.log.warn("Failed to register Snapchat theme map update receiver: ${it.message}")
            }
            onDispose {
                runCatching { context.androidContext.unregisterReceiver(receiver) }
            }
        }
        val catalogState = remember(refreshNonce, mapRefreshNonce) { snapchatThemeCatalogState() }
        val catalog = catalogState.entries
        val overrides = remember(refreshNonce) {
            parseSnapchatThemeSurfaceOverrides(customTheme.surfaceColorOverrides.getNullable().orEmpty())
                .associateBy { it.key }
        }
        val rules = remember(refreshNonce) {
            parseSnapchatThemeSurfaceRules(customTheme.customSurfaceRules.getNullable().orEmpty())
        }
        val rulesByTarget = remember(rules) {
            rules.associateBy { "${it.isSelector}:${it.target}" }
        }
        val backgrounds = remember(refreshNonce) {
            parseSnapchatThemeBackgroundRules(customTheme.screenBackgrounds.getNullable().orEmpty())
        }
        val backgroundByTarget = remember(backgrounds) {
            backgrounds.associateBy { "${it.isSelector}:${it.target}" }
        }
        var query by rememberSaveable { mutableStateOf("") }
        var colorEditRequest by remember { mutableStateOf<SnapchatThemeColorEditRequest?>(null) }
        var surfaceDetailsRequest by remember {
            mutableStateOf<Pair<SnapchatThemeCatalogEntry, SnapchatThemeBackgroundRule?>?>(null)
        }
        val normalizedQuery = query.trim().lowercase(Locale.US)
        val filteredCatalog = remember(catalog, overrides, rulesByTarget, normalizedQuery) {
            val rows = catalog.filter { entry ->
                normalizedQuery.isEmpty() || entry.searchKey.contains(normalizedQuery)
            }
            fun isCustomized(entry: SnapchatThemeCatalogEntry): Boolean {
                val selector = entry.selector?.takeIf { it.isNotBlank() }
                return if (selector != null) {
                    rulesByTarget.containsKey("true:$selector")
                } else {
                    overrides.containsKey(entry.key)
                }
            }
            rows.filter(::isCustomized) + rows.filterNot(::isCustomized)
        }
        val filteredRules = remember(rules, normalizedQuery) {
            rules.filter { rule ->
                normalizedQuery.isEmpty() ||
                    rule.label.lowercase(Locale.US).contains(normalizedQuery) ||
                    rule.target.lowercase(Locale.US).contains(normalizedQuery)
            }
        }
        val activeSurfaceCount = overrides.size + rules.size
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember {
            Brush.linearGradient(
                listOf(
                    PurrfectPalette.glowPrimary.copy(alpha = 0.55f),
                    PurrfectPalette.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }

        colorEditRequest?.let { request ->
            SnapchatThemeColorPickerDialog(
                title = request.label,
                initialColor = request.currentColor,
                onPreview = { color ->
                    sendSnapchatThemePreview(request.target, request.isSelector, color)
                },
                onDismiss = { colorEditRequest = null },
                onConfirm = { color ->
                    if (request.isCatalogSurface) {
                        setSnapchatThemeSurfaceOverride(
                            key = request.target,
                            label = request.label,
                            color = color,
                            onConfigChanged = onConfigChanged
                        )
                    } else {
                        setSnapchatThemeSurfaceRule(
                            SnapchatThemeSurfaceRule(
                                target = request.target,
                                isSelector = request.isSelector,
                                label = request.label,
                                color = color
                            ),
                            onConfigChanged = onConfigChanged
                        )
                    }
                    colorEditRequest = null
                }
            )
        }

        surfaceDetailsRequest?.let { (entry, background) ->
            val selector = entry.selector?.takeIf { it.isNotBlank() }
            val activeColor = if (selector != null) {
                rulesByTarget["true:$selector"]?.color
            } else {
                overrides[entry.key]?.color
            }
            SnapchatThemeSurfaceDetailsDialog(
                entry = entry,
                activeColor = activeColor,
                background = background,
                onDismiss = { surfaceDetailsRequest = null }
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(PurrfectPalette.cardOverlay, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Snapchat Custom Theme",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PurrfectPalette.textPrimary
                            )
                            Text(
                                text = "$activeSurfaceCount customized / ${backgrounds.size} images / ${catalogState.mappedSurfaceCount} mapped",
                                fontSize = 12.sp,
                                color = PurrfectPalette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "On",
                                fontSize = 11.sp,
                                color = PurrfectPalette.textSecondary
                            )
                            Switch(
                                checked = customTheme.enabled.get(),
                                onCheckedChange = { setSnapchatThemeEnabled(it, onConfigChanged) },
                                colors = purrfectSwitchColors()
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Live surface picker",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PurrfectPalette.textPrimary
                            )
                            Text(
                                text = "Shows the TH selector inside Snapchat for exact elements and per-screen backgrounds.",
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
                                color = PurrfectPalette.textSecondary
                            )
                        }
                        Switch(
                            checked = customTheme.captureThemeSurfaces.get(),
                            onCheckedChange = { setSnapchatThemeCaptureEnabled(it, onConfigChanged) },
                            colors = purrfectSwitchColors()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                importSnapchatThemeBackground(
                                    target = "__global__",
                                    isSelector = false,
                                    label = "Global app background",
                                    onConfigChanged = onConfigChanged
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Global image", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                if (isThemeMapperRefreshing) return@OutlinedButton
                                isThemeMapperRefreshing = true
                                context.shortToast("Refreshing Snapchat theme mapper")
                                coroutineScope.launch {
                                    val rebuilt = rebuildSnapchatThemeMapperAndRefreshCache()
                                    mapRefreshNonce++
                                    isThemeMapperRefreshing = false
                                    context.shortToast(
                                        if (rebuilt) {
                                            "Theme mapper refreshed"
                                        } else {
                                            "Theme cache refreshed; APK mapper kept previous data"
                                        }
                                    )
                                }
                            },
                            enabled = !isThemeMapperRefreshing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                        ) {
                            if (isThemeMapperRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                customTheme.surfaceColorOverrides.set("")
                                customTheme.customSurfaceRules.set("")
                                customTheme.screenBackgrounds.set("")
                                persistSnapchatThemeConfig(onConfigChanged)
                            },
                            enabled = activeSurfaceCount > 0 || backgrounds.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        text = "Mapper: ${catalogState.snapchatVersionLabel} / hash ${catalogState.uniqueHash} / updated ${catalogState.updatedAtMs}",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = PurrfectPalette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        placeholder = {
                            Text("Search theme surfaces")
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.07f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = PurrfectPalette.textSecondary,
                            unfocusedPlaceholderColor = PurrfectPalette.textSecondary,
                            focusedLeadingIconColor = Color.White,
                            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    if (filteredCatalog.isEmpty() && filteredRules.isEmpty()) {
                        Text(
                            text = "No matching theme surfaces",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = PurrfectPalette.textSecondary
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(filteredCatalog, key = { "catalog:${it.key}" }) { entry ->
                                val selectorTarget = entry.selector?.takeIf { it.isNotBlank() }
                                val target = selectorTarget ?: entry.key
                                val isSelector = selectorTarget != null
                                val selectorRule = if (isSelector) rulesByTarget["true:$target"] else null
                                val override = if (isSelector) null else overrides[entry.key]
                                val activeColor = selectorRule?.color ?: override?.color
                                val background = backgroundByTarget["$isSelector:$target"]
                                val supportsBackgroundImage = snapchatThemeCatalogSupportsBackgroundImage(entry)
                                SnapchatThemeCatalogRow(
                                    entry = entry,
                                    activeColor = activeColor,
                                    background = background,
                                    supportsBackgroundImage = supportsBackgroundImage,
                                    onEditColor = {
                                        colorEditRequest = SnapchatThemeColorEditRequest(
                                            target = target,
                                            isSelector = isSelector,
                                            label = entry.title,
                                            currentColor = activeColor ?: entry.defaultColor,
                                            isCatalogSurface = !isSelector
                                        )
                                    },
                                    onPreview = {
                                        sendSnapchatThemePreview(
                                            target = target,
                                            isSelector = isSelector,
                                            color = activeColor ?: entry.defaultColor
                                        )
                                    },
                                    onReset = {
                                        if (isSelector) {
                                            selectorRule?.let { removeSnapchatThemeSurfaceRule(it, onConfigChanged) }
                                        } else {
                                            removeSnapchatThemeSurfaceOverride(entry.key, onConfigChanged)
                                        }
                                    },
                                    onPickBackground = {
                                        importSnapchatThemeBackground(
                                            target = target,
                                            isSelector = isSelector,
                                            label = entry.title,
                                            onConfigChanged = onConfigChanged
                                        )
                                    },
                                    onRemoveBackground = {
                                        background?.let { removeSnapchatThemeBackgroundRule(it, onConfigChanged) }
                                    },
                                    onShowDetails = {
                                        surfaceDetailsRequest = entry to background
                                    }
                                )
                            }
                            if (filteredRules.isNotEmpty()) {
                                item(key = "captured_theme_header") {
                                    Text(
                                        text = "Captured live surfaces",
                                        modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PurrfectPalette.textSecondary
                                    )
                                }
                            }
                            items(filteredRules, key = { "rule:${it.isSelector}:${it.target}" }) { rule ->
                                val background = backgroundByTarget["${rule.isSelector}:${rule.target}"]
                                SnapchatThemeCapturedRow(
                                    rule = rule,
                                    background = background,
                                    onEditColor = {
                                        colorEditRequest = SnapchatThemeColorEditRequest(
                                            target = rule.target,
                                            isSelector = rule.isSelector,
                                            label = rule.label,
                                            currentColor = rule.color,
                                            isCatalogSurface = false
                                        )
                                    },
                                    onPreview = {
                                        sendSnapchatThemePreview(rule.target, rule.isSelector, rule.color)
                                    },
                                    onPickBackground = {
                                        importSnapchatThemeBackground(
                                            target = rule.target,
                                            isSelector = rule.isSelector,
                                            label = rule.label,
                                            onConfigChanged = onConfigChanged
                                        )
                                    },
                                    onRemoveBackground = {
                                        background?.let { removeSnapchatThemeBackgroundRule(it, onConfigChanged) }
                                    },
                                    onRemove = {
                                        removeSnapchatThemeSurfaceRule(rule, onConfigChanged)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun SnapchatThemeCatalogRow(
        entry: SnapchatThemeCatalogEntry,
        activeColor: Int?,
        background: SnapchatThemeBackgroundRule?,
        supportsBackgroundImage: Boolean,
        onEditColor: () -> Unit,
        onPreview: () -> Unit,
        onReset: () -> Unit,
        onPickBackground: () -> Unit,
        onRemoveBackground: () -> Unit,
        onShowDetails: () -> Unit
    ) {
        Column {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SnapchatThemeColorSwatch(activeColor ?: entry.defaultColor, activeColor != null)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = entry.title,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PurrfectPalette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.description,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = PurrfectPalette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaLine = snapchatThemeCatalogMetaLine(entry)
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = if (background != null) "$metaLine - ${background.name}" else metaLine,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = PurrfectPalette.textSecondary.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                SnapchatThemeIconButton(Icons.Filled.Visibility, onPreview)
                SnapchatThemeIconButton(Icons.Filled.Palette, onEditColor)
                SnapchatThemeIconButton(
                    icon = Icons.Filled.Image,
                    onClick = onPickBackground,
                    enabled = supportsBackgroundImage
                )
                if (background != null) {
                    SnapchatThemeIconButton(Icons.Filled.Close, onRemoveBackground)
                }
                SnapchatThemeIconButton(Icons.Filled.Info, onShowDetails)
                SnapchatThemeIconButton(
                    icon = Icons.Filled.RestartAlt,
                    onClick = onReset,
                    enabled = activeColor != null
                )
            }
        }
    }

    @Composable
    internal fun SnapchatThemeCapturedRow(
        rule: SnapchatThemeSurfaceRule,
        background: SnapchatThemeBackgroundRule?,
        onEditColor: () -> Unit,
        onPreview: () -> Unit,
        onPickBackground: () -> Unit,
        onRemoveBackground: () -> Unit,
        onRemove: () -> Unit
    ) {
        Column {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SnapchatThemeColorSwatch(rule.color, true)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = rule.label,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PurrfectPalette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (background != null) {
                            "${if (rule.isSelector) "Exact selector" else "Resource ID"} - ${background.name}"
                        } else if (rule.isSelector) {
                            "Exact selector"
                        } else {
                            "Resource ID"
                        },
                        fontSize = 12.sp,
                        color = PurrfectPalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SnapchatThemeIconButton(Icons.Filled.Visibility, onPreview)
                SnapchatThemeIconButton(Icons.Filled.Palette, onEditColor)
                SnapchatThemeIconButton(Icons.Filled.Image, onPickBackground)
                SnapchatThemeIconButton(
                    icon = Icons.Filled.Close,
                    onClick = onRemoveBackground,
                    enabled = background != null
                )
                SnapchatThemeIconButton(Icons.Filled.Delete, onRemove)
            }
        }
    }

    @Composable
    internal fun SnapchatThemeColorSwatch(color: Int, active: Boolean) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(
                    BorderStroke(
                        width = 1.dp,
                        color = if (active) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.18f)
                    ),
                    CircleShape
                )
        )
    }

    @Composable
    internal fun SnapchatThemeIconButton(
        icon: ImageVector,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.24f)
            )
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    internal fun SnapchatThemeSurfaceDetailsDialog(
        entry: SnapchatThemeCatalogEntry,
        activeColor: Int?,
        background: SnapchatThemeBackgroundRule?,
        onDismiss: () -> Unit
    ) {
        val detailLines = remember(entry, activeColor, background) {
            buildList {
                fun addValue(label: String, value: String?) {
                    val clean = value?.trim().orEmpty()
                    if (clean.isNotEmpty()) add("$label: $clean")
                }
                addValue("Key", entry.key)
                addValue("Title", entry.title)
                addValue("Category", entry.category)
                addValue("Source", entry.source)
                if (entry.hitCount > 0) add("Hits: ${entry.hitCount}")
                if (entry.confidence > 0f) add("Confidence: ${(entry.confidence * 100f).roundToInt()}%")
                addValue("Default color", snapchatThemeColorToHex(entry.defaultColor))
                activeColor?.let { addValue("Custom color", snapchatThemeColorToHex(it)) }
                addValue("Background image", background?.name)
                addValue("Background uri", background?.path)
                addValue("Attribute", entry.attributeName)
                addValue("Class", entry.className)
                addValue("Resource", entry.resourceName)
                addValue("Selector", entry.selectorDisplayName)
                addValue("Selector raw", entry.selector)
                addValue("Role", entry.role)
                addValue("Screen", entry.screenHint)
                addValue("Screen bounds", entry.screenBounds)
                addValue("Local bounds", entry.localBounds)
                addValue("Value", entry.valueSummary)
                if (entry.details.isNotEmpty()) {
                    add("Mapped details:")
                    entry.details.toSortedMap().forEach { (key, value) ->
                        add("  $key = $value")
                    }
                }
            }.joinToString("\n")
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            },
            title = {
                Text(
                    text = entry.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = entry.description,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = PurrfectPalette.textSecondary
                    )
                    SelectionContainer {
                        Text(
                            text = detailLines,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = PurrfectPalette.textPrimary
                        )
                    }
                }
            },
            containerColor = PurrfectPalette.cardOverlayColor,
            titleContentColor = PurrfectPalette.textPrimary,
            textContentColor = PurrfectPalette.textPrimary
        )
    }

    @Composable
    internal fun SnapchatThemeColorPickerDialog(
        title: String,
        initialColor: Int,
        onPreview: (Int?) -> Unit,
        onDismiss: () -> Unit,
        onConfirm: (Int) -> Unit
    ) {
        var currentColor by remember(initialColor) { mutableStateOf(Color(initialColor)) }
        var hexText by remember(initialColor) { mutableStateOf(snapchatThemeColorToHex(initialColor)) }
        val controller = remember(initialColor) { ColorPickerController() }

        fun updateColor(color: Color, updatePicker: Boolean = false) {
            currentColor = color
            hexText = snapchatThemeColorToHex(color.toArgb())
            if (updatePicker) {
                controller.selectByColor(color, true)
            }
        }

        LaunchedEffect(currentColor) {
            delay(120)
            onPreview(currentColor.toArgb())
        }

        DisposableEffect(Unit) {
            onDispose {
                onPreview(null)
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onConfirm(currentColor.toArgb()) }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
            title = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(currentColor)
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), RoundedCornerShape(14.dp))
                    )

                    TextField(
                        value = hexText,
                        onValueChange = { next ->
                            hexText = next.take(9)
                            parseSnapchatThemeColor(hexText)?.let { parsed ->
                                updateColor(Color(parsed), updatePicker = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ARGB hex") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.07f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    HsvColorPicker(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .padding(top = 2.dp),
                        initialColor = remember(initialColor) { Color(initialColor) },
                        controller = controller,
                        onColorChanged = {
                            if (!it.fromUser) return@HsvColorPicker
                            updateColor(it.color)
                        }
                    )

                    AlphaSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        initialColor = remember(initialColor) { Color(initialColor) },
                        controller = controller
                    )

                    BrightnessSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        initialColor = remember(initialColor) { Color(initialColor) },
                        controller = controller
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlphaTile(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(12.dp)),
                            controller = controller
                        )
                        Text(
                            text = "Drag on the color field, then fine-tune alpha and brightness.",
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = PurrfectPalette.textSecondary
                        )
                    }
                }
            },
            containerColor = PurrfectPalette.cardOverlayColor,
            titleContentColor = PurrfectPalette.textPrimary,
            textContentColor = PurrfectPalette.textPrimary
        )
    }

    @Composable
    internal fun SnapchatThemeColorSlider(
        label: String,
        value: Int,
        color: Color,
        onValueChange: (Int) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, fontSize = 12.sp, color = PurrfectPalette.textSecondary)
                Text(value.toString(), fontSize = 12.sp, color = PurrfectPalette.textSecondary)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
                valueRange = 0f..255f,
                steps = 254,
                colors = SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = color.copy(alpha = 0.75f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                )
            )
        }
    }

    @Composable
    internal fun InstagramHiddenUiElementsManager(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val translationPrefix = "features.properties.instagram.properties.hidden_ui_elements"
        val catalog = remember { loadInstagramHiddenUiIdCatalog() }
        val selectedIds = remember(refreshNonce) { selectedHiddenUiElementIds().toSet() }
        val selectedSelectors = remember(refreshNonce) { selectedHiddenUiElementSelectors().toSet() }
        val selectedEntries = remember(refreshNonce, catalog) { hiddenUiElementEntries(catalog) }
        var query by rememberSaveable { mutableStateOf("") }
        val normalizedQuery = query.trim().lowercase()
        val selectedSelectorRows = remember(selectedEntries, normalizedQuery) {
            selectedEntries.filter { entry ->
                entry.isSelector && (
                    normalizedQuery.isEmpty() ||
                        entry.label.lowercase().contains(normalizedQuery) ||
                        entry.value.lowercase().contains(normalizedQuery)
                    )
            }
        }
        val catalogRows = remember(catalog, selectedIds, normalizedQuery) {
            val matches = catalog.filter { entry ->
                normalizedQuery.isEmpty() || entry.searchKey.contains(normalizedQuery)
            }
            matches.filter { it.name in selectedIds } + matches.filterNot { it.name in selectedIds }
        }
        val totalResults = selectedSelectorRows.size + catalogRows.size
        val hiddenCount = selectedIds.size + selectedSelectors.size
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlay, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = context.translation["$translationPrefix.hidden_elements_title"]
                                    ?: "Hide UI Elements",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = skin.textPrimary
                            )
                            Text(
                                text = if (normalizedQuery.isEmpty()) {
                                    context.translation["$translationPrefix.ids_count"]
                                        ?.format(hiddenCount, catalog.size)
                                        ?: "$hiddenCount hidden / ${catalog.size} IDs"
                                } else {
                                    context.translation["$translationPrefix.ids_filtered_count"]
                                        ?.format(totalResults, catalog.size)
                                        ?: "$totalResults results / ${catalog.size} IDs"
                                },
                                fontSize = 12.sp,
                                color = skin.textSecondary
                            )
                        }
                        TextButton(
                            onClick = { clearHiddenUiElements(onConfigChanged) },
                            enabled = hiddenCount > 0,
                            colors = ButtonDefaults.textButtonColors(contentColor = skin.textPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = context.translation["$translationPrefix.unhide_all"]
                                    ?: "Unhide All UI Elements",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        placeholder = {
                            Text(
                                text = context.translation["$translationPrefix.search_hint"]
                                    ?: "Search Instagram resource IDs"
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = skin.textPrimary.copy(alpha = 0.07f),
                            unfocusedContainerColor = skin.textPrimary.copy(alpha = 0.05f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = skin.textPrimary,
                            focusedTextColor = skin.textPrimary,
                            unfocusedTextColor = skin.textPrimary,
                            focusedPlaceholderColor = skin.textSecondary,
                            unfocusedPlaceholderColor = skin.textSecondary,
                            focusedLeadingIconColor = skin.textPrimary,
                            unfocusedLeadingIconColor = skin.textPrimary.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    if (catalog.isEmpty()) {
                        Text(
                            text = context.translation["$translationPrefix.catalog_empty"]
                                ?: "Instagram resource ID catalog is unavailable.",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    } else if (totalResults == 0) {
                        Text(
                            text = context.translation["$translationPrefix.no_results"]
                                ?: "No matching IDs",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(selectedSelectorRows, key = { it.value }) { entry ->
                                InstagramHiddenUiSelectorRow(entry, onConfigChanged)
                            }
                            items(catalogRows, key = { it.name }) { entry ->
                                InstagramHiddenUiIdRow(
                                    entry = entry,
                                    checked = entry.name in selectedIds,
                                    onConfigChanged = onConfigChanged
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun InstagramHiddenUiSelectorRow(
        entry: HiddenUiElementEntry,
        onConfigChanged: () -> Unit
    ) {
        InstagramHiddenUiToggleRow(
            title = entry.label,
            label = context.translation["features.properties.instagram.properties.hidden_ui_elements.selector_label"]
                ?: "Exact selector",
            checked = true,
            onCheckedChange = { checked ->
                if (!checked) setHiddenUiElementHidden(entry.value, hidden = false, onConfigChanged = onConfigChanged)
            }
        )
    }

    @Composable
    internal fun InstagramHiddenUiIdRow(
        entry: HiddenUiIdCatalogEntry,
        checked: Boolean,
        onConfigChanged: () -> Unit
    ) {
        InstagramHiddenUiToggleRow(
            title = entry.displayTitle,
            label = context.translation["features.properties.instagram.properties.hidden_ui_elements.resource_id_label"]
                ?: "Resource ID",
            checked = checked,
            onCheckedChange = { requested ->
                setHiddenUiElementHidden(entry.name, requested, onConfigChanged)
            }
        )
    }

    @Composable
    internal fun InstagramHiddenUiToggleRow(
        title: String,
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        Column {
            HorizontalDivider(color = skin.textPrimary.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = skin.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = purrfectSwitchColors()
                )
            }
        }
    }

    @Composable
    internal fun InstagramDeveloperToolsCard(
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        var showResetDevConfigDialog by rememberSaveable { mutableStateOf(false) }
        var isResetDevConfigLoading by rememberSaveable { mutableStateOf(false) }

        if (showResetDevConfigDialog) {
            Dialog(onDismissRequest = { if (!isResetDevConfigLoading) showResetDevConfigDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = skin.cardOverlayColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                skin.glowPrimary.copy(alpha = 0.55f),
                                skin.glowSecondary.copy(alpha = 0.45f)
                            )
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Reset Dev Config",
                            color = skin.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Remove the imported Instagram developer config file.",
                            color = skin.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showResetDevConfigDialog = false },
                                enabled = !isResetDevConfigLoading,
                                colors = ButtonDefaults.textButtonColors(contentColor = skin.textSecondary)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    isResetDevConfigLoading = true
                                    Thread {
                                        val result = runCatching {
                                            val packages = installedInstagramPackages().ifEmpty { listOf(Constants.INSTAGRAM_PACKAGE_NAME) }
                                            if (packages.isEmpty()) error("Instagram package not found")
                                            packages.forEach { packageName ->
                                                context.androidContext.sendBroadcast(
                                                    Intent(Constants.INSTAGRAM_DEV_CONFIG_RESET_ACTION)
                                                        .setPackage(packageName)
                                                )
                                            }
                                        }
                                        Handler(Looper.getMainLooper()).post {
                                            isResetDevConfigLoading = false
                                            showResetDevConfigDialog = false
                                            context.shortToast(
                                                result.fold(
                                                    onSuccess = { "Dev Config reset sent." },
                                                    onFailure = { it.message ?: "Reset failed." }
                                                )
                                            )
                                            if (result.isSuccess) onConfigChanged()
                                        }
                                    }.apply {
                                        name = "PurrfectInstagramResetDevConfig"
                                        isDaemon = true
                                        start()
                                    }
                                },
                                enabled = !isResetDevConfigLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF453A).copy(alpha = 0.28f),
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                if (isResetDevConfigLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = skin.textPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlayColor, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.developer.tools_title"
                            ] ?: "Config",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                    }

                    Button(
                        onClick = { openInstagramDevOptionsFromManager(onConfigChanged) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.glowPrimary.copy(alpha = 0.30f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.developer.openInstagramDevOptions.name"
                            ] ?: "Open Instagram Dev Options",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { importInstagramDevConfigFromManager() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF30D158).copy(alpha = 0.26f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.developer.importDevConfig.name"
                            ] ?: "Import Dev Config",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = { exportInstagramDevConfigFromManager() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0A84FF).copy(alpha = 0.28f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.developer.exportDevConfig.name"
                            ] ?: "Export Dev Config",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { showResetDevConfigDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.34f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Reset Dev Config",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                }
            }
        }
    }

    @Composable
    internal fun InstagramQuickToggleMasterCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        InstagramCoreMasterSwitchCard(
            refreshNonce = refreshNonce,
            title = context.translation[
                "features.properties.instagram.properties.quick_toggle.core_master_title"
            ] ?: "Enable/Disable All",
            description = context.translation[
                "features.properties.instagram.properties.quick_toggle.core_master_description"
            ] ?: "Enable or disable every Quick Toggle item together.",
            checkedProvider = { isInstagramQuickToggleCoreEnabled() },
            onToggle = { setInstagramQuickToggleCore(it, onConfigChanged) }
        )
    }

    @Composable
    internal fun InstagramGhostSettingsMasterCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        InstagramCoreMasterSwitchCard(
            refreshNonce = refreshNonce,
            title = context.translation[
                "features.properties.instagram.properties.privacy.core_master_title"
            ] ?: "Enable/Disable All",
            description = context.translation[
                "features.properties.instagram.properties.privacy.core_master_description"
            ] ?: "Enable or disable every Ghost Settings feature together.",
            checkedProvider = { isInstagramGhostSettingsCoreEnabled() },
            onToggle = { setInstagramGhostSettingsCore(it, onConfigChanged) }
        )
    }

    @Composable
    internal fun InstagramDistractionFreeMasterCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val extremeActive = context.config.root.instagram.distractionFree.isExtremeMode.get()
        InstagramCoreMasterSwitchCard(
            refreshNonce = refreshNonce,
            title = context.translation[
                "features.properties.instagram.properties.distraction_free.core_master_title"
            ] ?: "Enable/Disable All",
            description = context.translation[
                "features.properties.instagram.properties.distraction_free.core_master_description"
            ] ?: "Enable or disable all Distraction Free features together.",
            checkedProvider = { isInstagramFeatureGroupEnabled(instagramDistractionKeys) },
            onToggle = { setInstagramFeatureGroup(instagramDistractionKeys, it, onConfigChanged) },
            enabled = !extremeActive
        )
    }

    @Composable
    internal fun InstagramMiscMasterCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        InstagramCoreMasterSwitchCard(
            refreshNonce = refreshNonce,
            title = context.translation[
                "features.properties.instagram.properties.misc.core_master_title"
            ] ?: "Enable/Disable All",
            description = context.translation[
                "features.properties.instagram.properties.misc.core_master_description"
            ] ?: "Enable or disable all Misc features together.",
            checkedProvider = { isInstagramFeatureGroupEnabled(instagramMiscMasterKeys) },
            onToggle = { setInstagramFeatureGroup(instagramMiscMasterKeys, it, onConfigChanged) }
        )
    }

    @Composable
    internal fun InstagramDownloaderMasterCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        InstagramCoreMasterSwitchCard(
            refreshNonce = refreshNonce,
            title = context.translation[
                "features.properties.instagram.properties.downloader.core_master_title"
            ] ?: "Enable/Disable All",
            description = context.translation[
                "features.properties.instagram.properties.downloader.core_master_description"
            ] ?: "Enable or disable all Downloader features together.",
            checkedProvider = { isInstagramFeatureGroupEnabled(instagramDownloaderMasterKeys) },
            onToggle = { setInstagramFeatureGroup(instagramDownloaderMasterKeys, it, onConfigChanged) }
        )
    }

    @Composable
    internal fun InstagramReelDownloadModeCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        var selectedMode by remember(refreshNonce) { mutableStateOf(instagramReelDownloadControlMode()) }
        val options = listOf(
            "menu" to (
                context.translation[
                    "features.properties.instagram.properties.downloader.reel_download_menu"
                ] ?: "Context menu"
                ),
            "like_long_press" to (
                context.translation[
                    "features.properties.instagram.properties.downloader.reel_download_like_long_press"
                ] ?: "Like long-press"
                ),
            "both" to (
                context.translation[
                    "features.properties.instagram.properties.downloader.reel_download_both"
                ] ?: "Both"
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlay, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.downloader.reel_download_mode_title"
                            ] ?: "Reel download trigger",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.downloader.reel_download_mode_description"
                            ] ?: "Choose how Reel video downloads are triggered.",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { (mode, label) ->
                            val selected = selectedMode == mode
                            Button(
                                onClick = {
                                    selectedMode = mode
                                    setInstagramReelDownloadControlMode(mode, onConfigChanged)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) {
                                        skin.glowPrimary.copy(alpha = 0.34f)
                                    } else {
                                        skin.textPrimary.copy(alpha = 0.08f)
                                    },
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(
                                    text = label,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseInstagramNavigationCsv(raw: String): List<String> {
        val known = instagramNavigationTabs.map { it.first }.toSet()
        return raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it in known }
            .distinct()
    }

    private fun normalizeInstagramNavigationOrder(raw: String): String {
        val ordered = parseInstagramNavigationCsv(raw).toMutableList()
        instagramNavigationTabs.forEach { (value, _) ->
            if (value !in ordered) ordered += value
        }
        return ordered.joinToString(",")
    }

    private fun firstVisibleInstagramNavigationTab(hidden: Set<String>): String {
        return instagramNavigationTabs.firstOrNull { it.first !in hidden }?.first ?: "home"
    }

    private fun instagramNavigationLabel(value: String): String {
        return instagramNavigationTabs.firstOrNull { it.first == value }?.second ?: value
    }

    @Composable
    internal fun InstagramSelectionStringDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        when (property.key.name) {
            "navigationTabHidden" -> InstagramNavigationHiddenTabsDialog(property, onDismiss, onPersist)
            "navigationTabOrder" -> InstagramNavigationOrderDialog(property, onDismiss, onPersist)
            "navigationDefaultTab" -> InstagramNavigationDefaultTabDialog(property, onDismiss, onPersist)
            "storyRingSize" -> InstagramStoryRingSizeDialog(property, onDismiss, onPersist)
            "likeReactionAnimation" -> InstagramLikeReactionAnimationDialog(property, onDismiss, onPersist)
            "confirmRefreshScope" -> InstagramConfirmRefreshScopeDialog(property, onDismiss, onPersist)
            "notesSpoofMapLocation" -> InstagramNotesLocationMapDialog(onDismiss, onPersist)
            "hiddenChatNames" -> InstagramHiddenChatsDialog(property, onDismiss, onPersist)
            else -> {}
        }
    }

    private fun parseInstagramChatNames(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { isPlausibleInstagramChatName(it) }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            .toList()
    }

    private fun isPlausibleInstagramChatName(value: String): Boolean {
        val clean = value.trim()
        if (clean.length !in 2..80) return false
        val lower = clean.lowercase(Locale.US).trim('\u200e', '\u200f', ' ')
        if (Regex("^\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{1,2}:\\d{2})?$").matches(lower)) return false
        if (Regex("^\\d{1,2}:\\d{2}.*").matches(lower)) return false
        if (lower.contains(" Â·") || lower.contains("Â·")) return false
        if (lower.endsWith("...") || lower.endsWith("â€¦")) return false
        val blocked = setOf(
            "notes", "note", "your note", "add note", "map", "play", "requests", "messages",
            "just curious", "inspo needed", "start your first note", "try sharing a song",
            "make this space yours", "ask friends anything", "your thoughts go here",
            "can't decide", "obsessed with", "ready for", "today's vibe", "unpopular opinion",
            "central park", "civic center", "rabindra sarovar"
        )
        if (lower in blocked) return false
        if (lower == "reply?" || lower == "reply") return false
        if (lower.contains("turned on disappearing messages") || lower.contains("turned off disappearing messages")) return false
        return true
    }

    @Composable
    private fun InstagramHiddenChatsDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val known = remember {
            val fromKnown = parseInstagramChatNames(context.config.root.instagram.misc.hiddenChats.knownChatNames.getNullable()?.toString().orEmpty())
            val fromHidden = parseInstagramChatNames(property.value.getNullable()?.toString().orEmpty())
            (fromKnown + fromHidden)
                .distinctBy { it.lowercase(Locale.US) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        }
        val selected = remember {
            mutableStateListOf<String>().apply { addAll(parseInstagramChatNames(property.value.getNullable()?.toString().orEmpty())) }
        }
        var query by remember { mutableStateOf("") }
        val visible = remember(query, known) {
            val needle = query.trim().lowercase(Locale.US)
            if (needle.isBlank()) known else known.filter { it.lowercase(Locale.US).contains(needle) }
        }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Hide conversations",
            text = "",
            icon = Icons.Filled.VisibilityOff,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(selected.distinctBy { it.lowercase(Locale.US) }.joinToString("\n"))
                onPersist()
                context.shortToast("Hidden conversations updated")
            },
            customContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search conversations") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PurrfectPalette.glowSecondary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedLabelColor = PurrfectPalette.glowSecondary,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                            cursorColor = PurrfectPalette.glowSecondary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                visible.forEach { name -> if (selected.none { it.equals(name, ignoreCase = true) }) selected.add(name) }
                            },
                            enabled = visible.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.10f), contentColor = Color.White)
                        ) { Text("Select visible") }
                        Button(
                            onClick = { selected.clear() },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.10f), contentColor = Color.White)
                        ) { Text("Clear") }
                    }
                    if (known.isEmpty()) {
                        Text(
                            text = "Open Instagram Direct once so Purrfect can collect conversation names.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 14.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 430.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(visible, key = { it.lowercase(Locale.US) }) { name ->
                                val checked = selected.any { it.equals(name, ignoreCase = true) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            if (checked) {
                                                selected.removeAll { it.equals(name, ignoreCase = true) }
                                            } else {
                                                selected.add(name)
                                            }
                                        }
                                        .background(Color.White.copy(alpha = if (checked) 0.12f else 0.06f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            if (isChecked) {
                                                if (selected.none { it.equals(name, ignoreCase = true) }) selected.add(name)
                                            } else {
                                                selected.removeAll { it.equals(name, ignoreCase = true) }
                                            }
                                        }
                                    )
                                    Text(
                                        name,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramNavigationHiddenTabsDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val currentDefault = context.config.root.instagram.misc.navigationUi.navigationDefaultTab.get()
        val selected = remember {
            mutableStateListOf<String>().apply { addAll(parseInstagramNavigationCsv(property.value.getNullable()?.toString().orEmpty())) }
        }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Hide navigation tabs",
            text = "",
            icon = Icons.Filled.VisibilityOff,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                val hidden = selected.toMutableList()
                if (hidden.size >= instagramNavigationTabs.size) {
                    hidden.remove(if (currentDefault in hidden) currentDefault else "home")
                    context.shortToast("At least one navigation tab must stay visible.")
                }
                val hiddenSet = hidden.toSet()
                val newDefault = if (currentDefault in hiddenSet || currentDefault !in instagramNavigationTabs.map { it.first }) {
                    firstVisibleInstagramNavigationTab(hiddenSet)
                } else {
                    currentDefault
                }
                property.value.setAny(hidden.joinToString(","))
                context.config.root.instagram.misc.navigationUi.navigationDefaultTab.set(newDefault)
                onPersist()
                context.shortToast("Navigation tabs updated")
            },
            customContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    instagramNavigationTabs.forEach { (value, label) ->
                        val checked = value in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (checked) selected.remove(value) else selected.add(value)
                                }
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        if (value !in selected) selected.add(value)
                                    } else {
                                        selected.remove(value)
                                    }
                                }
                            )
                            Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramNavigationOrderDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val order = remember {
            mutableStateListOf<String>().apply { addAll(parseInstagramNavigationCsv(normalizeInstagramNavigationOrder(property.value.get().toString()))) }
        }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Reorder navigation tabs",
            text = "Move tabs up or down to match the order you want.",
            icon = Icons.Filled.SwapVert,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(normalizeInstagramNavigationOrder(order.joinToString(",")))
                onPersist()
                context.shortToast("Navigation tabs updated")
            },
            customContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    order.forEachIndexed { index, value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = instagramNavigationLabel(value),
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val moved = order.removeAt(index)
                                    order.add(index - 1, moved)
                                }
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White)
                            }
                            IconButton(
                                enabled = index < order.lastIndex,
                                onClick = {
                                    val moved = order.removeAt(index)
                                    order.add(index + 1, moved)
                                }
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramNavigationDefaultTabDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val hidden = parseInstagramNavigationCsv(context.config.root.instagram.misc.navigationUi.navigationTabHidden.getNullable().orEmpty()).toSet()
        val visibleTabs = instagramNavigationTabs.filter { it.first !in hidden }.ifEmpty { listOf("home" to "Home") }
        var selected by remember { mutableStateOf(property.value.get().takeIf { tab -> visibleTabs.any { it.first == tab } } ?: visibleTabs.first().first) }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Default tab on open",
            text = "",
            icon = Icons.Filled.Home,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(selected)
                onPersist()
                context.shortToast("Navigation tabs updated")
            },
            customContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleTabs.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selected = value }
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == value, onClick = { selected = value })
                            Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramLikeReactionAnimationDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val allowed = instagramLikeReactionAnimations.map { it.first }.toSet()
        var selected by remember {
            mutableStateOf(property.value.get().takeIf { it in allowed } ?: "ARES_LIKE_ACTIVATION")
        }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Change Like Reactions",
            text = "",
            icon = Icons.Filled.Favorite,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(selected)
                onPersist()
                context.shortToast("Like reaction updated")
            },
            customContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    instagramLikeReactionAnimations.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selected = value }
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == value, onClick = { selected = value })
                            Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramConfirmRefreshScopeDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val allowed = instagramConfirmRefreshScopes.map { it.first }.toSet()
        var selected by remember {
            mutableStateOf(property.value.get().takeIf { it in allowed } ?: "both")
        }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Confirm refresh",
            text = "Choose where refresh confirmation is shown.",
            icon = Icons.Filled.Refresh,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(selected)
                onPersist()
                context.shortToast("Refresh confirmation scope updated")
            },
            customContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    instagramConfirmRefreshScopes.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selected = value }
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == value, onClick = { selected = value })
                            Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramStoryRingSizeDialog(
        property: PropertyPair<String>,
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        fun percentFrom(value: String): Float {
            value.trim().removeSuffix("%").toFloatOrNull()?.let { return it.coerceIn(60f, 160f) }
            return when (value.lowercase(Locale.US)) {
                "small" -> 85f
                "large" -> 115f
                "huge" -> 130f
                else -> 100f
            }
        }
        fun valueFrom(percent: Float): String {
            val rounded = ((percent / 5f).roundToInt() * 5).coerceIn(60, 160)
            return if (rounded == 100) "default" else rounded.toString()
        }
        var selected by remember { mutableFloatStateOf(percentFrom(property.value.get().toString())) }
        AestheticDialog(
            onDismissRequest = onDismiss,
            title = "Home story ring size",
            text = "Adjusts only the top story tray on the home feed.",
            icon = Icons.Filled.Tune,
            dismissButtonText = context.translation["button.negative"] ?: "Cancel",
            onDismiss = onDismiss,
            confirmButtonText = context.translation["ig_dialog_save"] ?: "Save",
            onConfirm = {
                property.value.setAny(valueFrom(selected))
                onPersist()
                context.shortToast("Story ring size updated")
            },
            customContent = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "${selected.roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Slider(
                        value = selected,
                        onValueChange = { selected = ((it / 5f).roundToInt() * 5).coerceIn(60, 160).toFloat() },
                        valueRange = 60f..160f,
                        steps = 19
                    )
                    Button(
                        onClick = { selected = 100f },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.10f),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Reset to default")
                    }
                }
            }
        )
    }

    @Composable
    private fun InstagramNotesLocationMapDialog(
        onDismiss: () -> Unit,
        onPersist: () -> Unit
    ) {
        val notes = context.config.root.instagram.misc.notesLocation
        val initialLat = notes.notesSpoofLatitude.getNullable()?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: 40.7128
        val initialLon = notes.notesSpoofLongitude.getNullable()?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: -74.0060
        val tempValue = remember { PropertyValue(initialLat to initialLon) }
        val tempProperty = remember {
            PropertyPair(
                PropertyKey({ null }, "notesSpoofCoordinates", DataProcessors.MAP_COORDINATES),
                tempValue
            )
        }
        alertDialogs.ChooseLocationDialog(
            property = tempProperty,
            locationSearchProvider = context.config.root.global.betterLocation.locationSearchProvider.getNullable() ?: "osm",
            googleMapsApiKey = context.config.root.global.betterLocation.googleMapsApiKey.getNullable() ?: ""
        ) {
            val selected = tempValue.get()
            notes.notesSpoofLatitude.set(selected.first.toString())
            notes.notesSpoofLongitude.set(selected.second.toString())
            onPersist()
            context.shortToast("Instagram spoof location updated")
            onDismiss()
        }
    }

    @Composable
    internal fun InstagramEmojiFontToolsCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        val title = context.translation["features.properties.instagram.properties.misc.custom_emoji_font.import"]
            ?: "Import Custom Emoji Font"

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlay, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.misc.custom_emoji_font.name"
                            ] ?: "Custom Emoji Font",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    }
                    Button(
                        onClick = { importInstagramEmojiFontFromManager(onConfigChanged) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.glowPrimary.copy(alpha = 0.30f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = context.translation["features.properties.instagram.properties.misc.custom_emoji_font.import"]
                                ?: "Import Custom Emoji Font",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { resetInstagramEmojiFont(onConfigChanged) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF453A).copy(alpha = 0.24f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = context.translation["features.properties.instagram.properties.misc.custom_emoji_font.reset"]
                                ?: "Reset Custom Emoji Font",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    internal fun InstagramCoreMasterSwitchCard(
        refreshNonce: Int,
        title: String,
        description: String,
        checkedProvider: () -> Boolean,
        onToggle: (Boolean) -> Unit,
        enabled: Boolean = true
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        var checked by remember(refreshNonce) { mutableStateOf(checkedProvider()) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlayColor, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .graphicsLayer { if (!enabled) alpha = 0.5f }
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = description,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = { requested ->
                            if (!enabled) return@Switch
                            checked = requested
                            onToggle(requested)
                        },
                        enabled = enabled,
                        colors = purrfectSwitchColors()
                    )
                }
            }
        }
    }

    @Composable
    internal fun InstagramDmMarkSeenControlCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        var selectedMode by remember(refreshNonce) { mutableStateOf(instagramDmMarkSeenControlMode()) }
        val eyeLabel = context.translation[
            "features.properties.instagram.properties.privacy.mark_seen_eye"
        ] ?: "Eye icon"
        val holdGalleryLabel = context.translation[
            "features.properties.instagram.properties.privacy.mark_seen_gallery_hold"
        ] ?: "Tap and hold gallery icon"

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlayColor, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.privacy.mark_seen_control_title"
                            ] ?: "DM mark-as-seen control",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.privacy.mark_seen_control_description"
                            ] ?: "Choose the manual seen control shown in Direct messages.",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("eye" to eyeLabel, "hold_gallery" to holdGalleryLabel).forEach { (mode, label) ->
                            val selected = selectedMode == mode
                            Button(
                                onClick = {
                                    selectedMode = mode
                                    setInstagramDmMarkSeenControlMode(mode, onConfigChanged)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) {
                                        skin.glowPrimary.copy(alpha = 0.34f)
                                    } else {
                                        skin.textPrimary.copy(alpha = 0.08f)
                                    },
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(
                                    text = label,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun InstagramAdsAndLinksToolsCard(
        refreshNonce: Int,
        onConfigChanged: () -> Unit
    ) {
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val cardShape = RoundedCornerShape(22.dp)
        val cardBorder = remember(skin.glowPrimary, skin.glowSecondary) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.55f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        var checked by remember(refreshNonce) { mutableStateOf(isInstagramAdsAndLinksCoreEnabled()) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlayColor, cardShape)
                    .border(BorderStroke(1.dp, cardBorder), cardShape)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.ads_and_links.core_master_title"
                            ] ?: "Enable/Disable All",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary
                        )
                        Text(
                            text = context.translation[
                                "features.properties.instagram.properties.ads_and_links.core_master_description"
                            ] ?: "Block Ads, Block Analytics, and Disable Tracking Links together.",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = skin.textSecondary
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = { requested ->
                            checked = requested
                            setInstagramAdsAndLinksCore(requested, onConfigChanged)
                        },
                        colors = purrfectSwitchColors()
                    )
                }
            }
        }
    }

    @Composable
    internal fun Container(
        configContainer: ConfigContainer,
        stateKey: String,
        sectionTitle: String? = null,
        sectionSubtitle: String? = null,
        searchKeyword: String? = null,
        onBack: (() -> Unit)? = null,
    ) {
        PropertiesView(
            properties = remember(configContainer.globalState) {
                configContainer.properties.map { (it.key to it.value).toPropertyPair() as PropertyPair<Any> }.filter {
                    isVisibleForCurrentTarget(configContainer, it.key) &&
                        (
                            configContainer !== context.config.root.experimental.spoof.randomizeDeviceProfile ||
                                configContainer.globalState == true ||
                                !isRandomizedProfileActionProperty(it.key.name)
                        )
                }
            },
            stateKey = stateKey,
            activeSectionTitle = sectionTitle,
            activeSectionSubtitle = sectionSubtitle,
            searchKeyword = searchKeyword,
            enableGlobalSearch = configContainer == featureRootContainer(),
            showWhatsAppHiddenUiElementsManager =
                configContainer === context.config.root.whatsapp.uiElements ||
                    configContainer === context.config.root.instagram.hiddenUiElements ||
                    configContainer === context.config.root.userInterface.uiElements,
            showSnapchatCustomThemeManager = configContainer === context.config.root.userInterface.customTheme,
            showInstagramDeveloperTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.developer,
            showInstagramAdsAndLinksTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.adsAndLinks,
            showInstagramGhostSettingsTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.privacy,
            showInstagramQuickToggleTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.privacy.quickToggle,
            showInstagramDistractionFreeTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.distractionFree,
            showInstagramMiscTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.misc,
            showInstagramDownloaderTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.downloader,
            showInstagramEmojiFontTools = context.isInstagramMode &&
                configContainer === context.config.root.instagram.misc,
            onBack = onBack
        )
    }

    // Ported Reference Fuzzy Logic
    internal data class SearchEntry(val keyword: String, val tokens: List<String>)

    internal fun buildSearchEntries(): List<SearchEntry> {
        return allProperties.keys.mapNotNull { key ->
            if (!isSearchVisibleProperty(key)) return@mapNotNull null
            val name = context.translation[key.propertyName()]
            val description = context.translation[key.propertyDescription()]
            val tokens = listOfNotNull(name, description, key.name).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) null else SearchEntry(keyword = name ?: key.name, tokens = tokens)
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(min(curr[j] + 1, prev[j + 1] + 1), prev[j] + cost)
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[b.length]
    }

    private fun similarityScore(query: String, target: String): Float {
        val q = query.lowercase()
        val t = target.lowercase()
        val maxLen = max(q.length, t.length)
        if (maxLen == 0) return 1f
        val dist = levenshtein(q, t)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    internal fun fuzzySuggest(query: String, entries: List<SearchEntry>): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return entries.map { entry ->
            val best = entry.tokens.maxOfOrNull { similarityScore(q, it) } ?: 0f
            best to entry.keyword
        }.filter { it.first >= 0.45f }
            .sortedWith(compareByDescending<Pair<Float, String>> { it.first }.thenBy { it.second.length })
            .map { it.second }
            .distinct()
            .take(6)
    }

    internal fun loadSearchHistory(): List<String> {
        return context.sharedPreferences.getString("features_search_history", "")
            ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    internal fun saveSearchHistory(history: List<String>) {
        context.sharedPreferences.edit().putString("features_search_history", history.joinToString("|")).apply()
    }

    internal fun upsertHistory(term: String, history: SnapshotStateList<String>) {
        val cleaned = term.trim()
        if (cleaned.isEmpty()) return
        val existingIndex = history.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
        if (existingIndex >= 0) history.removeAt(existingIndex)
        history.add(0, cleaned)
        while (history.size > 12) history.removeLast()
        saveSearchHistory(history.toList())
    }
}
