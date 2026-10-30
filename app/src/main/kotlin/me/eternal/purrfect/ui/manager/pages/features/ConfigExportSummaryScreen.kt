package me.eternal.purrfect.ui.manager.pages.features

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.R
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.util.saveFile
import me.eternal.purrfect.storage.getLocationCoordinates
import org.json.JSONArray
import org.json.JSONObject

internal object ConfigExportSkinPalette {
    @Composable
    private fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else PurrfectPalette.glowSecondary
    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else PurrfectPalette.backgroundGradient
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else PurrfectPalette.cardOverlay
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else PurrfectPalette.textPrimary
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else PurrfectPalette.textSecondary
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else PurrfectPalette.cardOverlayColor
}

class ConfigExportSummaryScreen : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.features.config_export") }

    private data class ImportedFeature(
        val category: String,
        val name: String,
        val key: String,
        val value: Any,
        val indentation: Int
    )

    private inner class ConfigParser {
        fun parse(configJson: String): Map<String, List<ImportedFeature>> {
            val featureList = mutableListOf<ImportedFeature>()
            val json = JSONObject(configJson)
            fun parseProperties(categoryKey: String, niceCategoryName: String, properties: JSONObject, prefix: String, indent: Int) {
                for (key in properties.keys()) {
                    val value = properties.get(key)
                    val currentPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                    if (value is JSONObject && value.has("state") && value.has("properties")) {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        val featureName = context.translation[featureNameKey] ?: key
                        featureList.add(ImportedFeature(niceCategoryName, featureName, key, value.getBoolean("state"), indent))
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent + 1)
                    } else if (value is JSONObject && value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), currentPrefix, indent)
                    } else {
                        val featureNameKey = "features.properties.$categoryKey.properties.${currentPrefix.split('.').joinToString(".properties.")}.name"
                        var featureName = context.translation[featureNameKey] ?: key
                        if (key == "save_folder") {
                            featureName = "Save Folder"
                        }
                        featureList.add(ImportedFeature(niceCategoryName, featureName, key, value, indent))
                    }
                }
            }
            for (categoryKey in json.keys()) {
                val value = json.get(categoryKey)
                if (value is JSONObject) {
                    val niceCategoryName = context.translation["features.properties.$categoryKey.name"] ?: categoryKey.replaceFirstChar { it.uppercase() }
                    if (value.has("state") && !value.has("properties")) {
                        featureList.add(ImportedFeature(niceCategoryName, translation["enable_feature"], categoryKey, value.getBoolean("state"), 0))
                    } else if (value.has("properties")) {
                        parseProperties(categoryKey, niceCategoryName, value.getJSONObject("properties"), "", 0)
                    }
                }
            }
            return featureList.groupBy { it.category }
        }

        fun parseValue(featureKey: String, value: Any): Any {
            fun innerParse(v: Any): String {
                if (v is String) {
                    if (v.isBlank()) {
                        val emptyKey = "features.options.$featureKey.empty"
                        val translatedEmpty = context.translation[emptyKey]
                        val fallback = context.translation["features.options.empty"]?.takeUnless { it == "features.options.empty" }
                        return if (!translatedEmpty.isNullOrBlank() && translatedEmpty != emptyKey && !translatedEmpty.startsWith("features.")) translatedEmpty else (fallback ?: "Empty")
                    }
                    val translationKey = "features.options.$featureKey.$v"
                    val translated = context.translation[translationKey]
                    return if (!translated.isNullOrBlank() && translated != translationKey && !translated.startsWith("features.")) translated else v
                }
                return v.toString()
            }
            return when (value) {
                is Boolean -> if (value) translation["enabled"] else translation["disabled"]
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        list.add(innerParse(value.get(i)))
                    }
                    list
                }
                else -> innerParse(value)
            }
        }
    }

    @Composable
    private fun NumberBubble(number: Int) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = ConfigExportSkinPalette.textPrimary.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        ConfigExportSkinPalette.glowPrimary.copy(alpha = 0.5f),
                        ConfigExportSkinPalette.glowSecondary.copy(alpha = 0.4f)
                    )
                )
            )
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(number.toString(), color = ConfigExportSkinPalette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }

    override val content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit = { navBackStackEntry ->
        val avenirNext = remember { FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium)) }
        val exportSensitiveData = navBackStackEntry.arguments?.getString("exportSensitiveData")?.toBoolean() ?: false
        val includeSavedLocations = !context.isRedditMode && !context.isInstagramMode &&
            (navBackStackEntry.arguments?.getString("includeSavedLocations")?.toBoolean() ?: false)
        val defaultFileName = when {
            context.isRedditMode -> "reddit-config.json"
            context.isWhatsAppMode -> "whatsapp-config.json"
            context.isInstagramMode -> "instagram-config.json"
            else -> "snap-config.json"
        }
        val exportLabel = context.translation["manager.sections.features.export_option"] ?: "Confirm Export"
        val parser = remember { ConfigParser() }

        val savedLocations = remember {
            if (includeSavedLocations) context.database.getLocationCoordinates() else null
        }

        val exportedJson = remember {
            ScopedConfigJson.exportForActiveTarget(
                context,
                exportSensitiveData,
                includeSavedLocations,
                savedLocations
            )
        }
        val featuresByCategory = remember(exportedJson) { parser.parse(exportedJson) }
        val expandedState = remember { mutableStateMapOf<String, Boolean>() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ConfigExportSkinPalette.backgroundGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = ConfigExportSkinPalette.cardOverlayColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                ConfigExportSkinPalette.glowPrimary.copy(alpha = 0.55f),
                                ConfigExportSkinPalette.glowSecondary.copy(alpha = 0.45f)
                            )
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .background(ConfigExportSkinPalette.cardOverlay, RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { routes.navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = ConfigExportSkinPalette.textPrimary
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = translation["title"] ?: "Export Confirmation",
                                color = ConfigExportSkinPalette.textPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                fontFamily = avenirNext
                            )
                        }

                        Spacer(Modifier.width(48.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = 140.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        ConfigPreviewer(configJson = exportedJson)
                    }

                    items(featuresByCategory.toList()) { (category, features) ->
                        val isExpanded = expandedState[category] ?: false
                        val rotationState by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "rotation")

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedState[category] = !isExpanded },
                            shape = RoundedCornerShape(18.dp),
                            color = ConfigExportSkinPalette.cardOverlayColor,
                            tonalElevation = 0.dp,
                            border = BorderStroke(
                                1.dp,
                                if (isExpanded) Brush.linearGradient(
                                    listOf(
                                        ConfigExportSkinPalette.glowPrimary.copy(alpha = 0.6f),
                                        ConfigExportSkinPalette.glowSecondary.copy(alpha = 0.5f)
                                    )
                                ) else SolidColor(ConfigExportSkinPalette.textPrimary.copy(alpha = 0.12f))
                            )
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = category,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        color = if (isExpanded) ConfigExportSkinPalette.glowPrimary else ConfigExportSkinPalette.textPrimary,
                                        fontFamily = avenirNext,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { expandedState[category] = !isExpanded }) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.graphicsLayer(rotationZ = rotationState),
                                            tint = ConfigExportSkinPalette.textPrimary.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = isExpanded) {
                                    Column(
                                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        features.forEachIndexed { _, feature ->
                                            val parsedValue = parser.parseValue(feature.key, feature.value)
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = (feature.indentation * 12).dp)
                                            ) {
                                                Text(
                                                    text = feature.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = ConfigExportSkinPalette.textPrimary
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                if (parsedValue is List<*>) {
                                                    parsedValue.forEachIndexed { itemIndex, item ->
                                                        Row(
                                                            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            NumberBubble(itemIndex + 1)
                                                            Text(
                                                                text = item.toString(),
                                                                fontSize = 13.sp,
                                                                color = ConfigExportSkinPalette.textSecondary,
                                                                fontWeight = FontWeight.Normal
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Text(
                                                        text = parsedValue.toString(),
                                                        fontSize = 13.sp,
                                                        color = ConfigExportSkinPalette.textSecondary,
                                                        fontWeight = FontWeight.Normal
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

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                ConfigExportSkinPalette.cardOverlayColor.copy(alpha = 0.95f)
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        routes.activityLauncher.saveFile(defaultFileName, "application/json") { uri ->
                            runCatching {
                                context.androidContext.contentResolver.openOutputStream(android.net.Uri.parse(uri))?.use {
                                    context.config.writeConfig()
                                    exportedJson.byteInputStream().copyTo(it)
                                    context.shortToast(context.translation["manager.sections.features.config_export_success_toast"] ?: "Config exported")
                                }
                            }.onFailure {
                                context.longToast(
                                    context.translation.format(
                                        "manager.sections.features.config_export_failure_toast",
                                        "error" to it.message.toString()
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        ConfigExportSkinPalette.glowPrimary,
                                        ConfigExportSkinPalette.glowSecondary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = exportLabel,
                            color = ConfigExportSkinPalette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            fontFamily = avenirNext
                        )
                    }
                }
            }
        }
    }
}
