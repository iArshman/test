package me.eternal.purrfect.ui.setup.screens.impl

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.common.ui.theme.Catppuccin
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectColorSet
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.scaleOnPress
import androidx.compose.ui.graphics.SolidColor

/**
 * ThemeSelectorScreen — Step 1 of the PurrfectSnap setup flow.
 *
 * Shows three buttons up-front: Aurora Theme, Aphelion Theme, Skip.
 * Tapping "Aphelion Theme" fades in the skin picker below — identical
 * to the reveal animation used in AphelionSettings.
 * Writes directly to SharedPreferences so AphelionSkinProvider reacts live.
 */
class ThemeSelectorScreen : SetupScreen() {

    @Composable
    override fun Content() {
        val skin = LocalPurrfectSkin.current
        val prefs = remember {
            context.androidContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }

        // Read initial values from prefs (same keys as AphelionSkinProvider)
        var currentSkinId by remember {
            mutableStateOf(prefs.getString("aphelion_skin", "UMBRA") ?: "UMBRA")
        }
        var luminaMode by remember {
            mutableStateOf(prefs.getString("lumina_mode", "AUTO") ?: "AUTO")
        }
        var luminaAccent by remember {
            mutableStateOf(prefs.getString("lumina_accent", "MAUVE") ?: "MAUVE")
        }
        var aetherMode by remember {
            mutableStateOf(prefs.getString("aether_mode", "AUTO") ?: "AUTO")
        }
        var aetherAccent by remember {
            mutableStateOf(prefs.getString("aether_accent", "MAUVE") ?: "MAUVE")
        }
        var aetherAmoled by remember {
            mutableStateOf(prefs.getBoolean("aether_amoled", false))
        }
        var cyberwareStyle by remember {
            mutableStateOf(prefs.getString("cyberware_style", "SYNTHWAVE") ?: "SYNTHWAVE")
        }

        // Allow proceeding immediately (skip is always valid)
        LaunchedEffect(Unit) {
            allowNext(true)
            showSkip?.invoke("Skip Theme") {
                prefs.edit().putString("manager_theme", "APHELION").apply()
                context.config.root.global.uiSettings.managerTheme.set("APHELION")
                context.config.writeConfig()
                goNext()
            }
        }

        SetupCard {
            // ── Status pill (Dynamic) ─────────────────────────────────────────
            val skinDisplayName = remember(currentSkinId) {
                when (currentSkinId) {
                    "UMBRA"   -> "Umbra"
                    "LUX"     -> "Lux"
                    "AMBER"   -> "Amber"
                    "NOX"     -> "Nox"
                    "LUMINA"  -> "Lumina"
                    "AETHER"  -> "Aether"
                    "CYBER"   -> "Cyber"
                    else      -> currentSkinId.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(30),
                    color = skin.glowPrimary.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, skin.glowPrimary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Select Aphelion theme Skins",
                        color = if (skin.isDark) Color.White else Color.Black.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }

            Text(
                text = "Choose a tailored visual identity, refined for depth, clarity, and premium accents.",
                fontSize = 12.sp,
                color = skin.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                lineHeight = 16.sp
            )

            // ── Skin picker ─────────────────────────────────────────────────
            SetupSkinPicker(
                currentSkinId = currentSkinId,
                onSkinSelected = { id ->
                    currentSkinId = id
                    prefs.edit()
                        .putString("aphelion_skin", id)
                        .putString("manager_theme", "APHELION")
                        .apply()
                    context.config.root.global.uiSettings.managerTheme.set("APHELION")
                    context.config.root.global.uiSettings.aphelionSkin.set(id)
                    context.config.writeConfig()
                }
            )

            // ── Cyber sub-panel ────────────────────────────────────────────
            AnimatedVisibility(
                visible = currentSkinId == "CYBER",
                enter = expandVertically() + fadeIn(tween(250)),
                exit = shrinkVertically() + fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = skin.textPrimary.copy(alpha = 0.08f))

                    // Style Switcher
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("SYNTHWAVE", "NIGHTCITY").forEach { style ->
                            val isSelected = cyberwareStyle == style
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable {
                                        cyberwareStyle = style
                                        prefs.edit().putString("cyberware_style", style).apply()
                                        context.config.root.global.uiSettings.cyberwareStyle.set(style)
                                        context.config.writeConfig()
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f)
                                        else skin.textPrimary.copy(alpha = 0.05f),
                                border = if (isSelected) BorderStroke(1.dp, skin.glowPrimary) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (style == "SYNTHWAVE") "Synthwave" else "Night City",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Lumina sub-panel ────────────────────────────────────────────
            AnimatedVisibility(
                visible = currentSkinId == "LUMINA",
                enter = expandVertically() + fadeIn(tween(250)),
                exit = shrinkVertically() + fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = skin.textPrimary.copy(alpha = 0.08f))

                    // Mode row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                            val isSelected = luminaMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable {
                                        luminaMode = mode
                                        prefs.edit().putString("lumina_mode", mode).apply()
                                        context.config.root.global.uiSettings.luminaMode.set(mode)
                                        context.config.writeConfig()
                                    },
                                shape = if (skin.id == "AETHER") G2RoundedRectangle(10.dp) else RoundedCornerShape(10.dp),
                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f)
                                        else skin.textPrimary.copy(alpha = 0.05f),
                                border = if (isSelected) BorderStroke(
                                    1.dp,
                                    if (skin.isDark) skin.glowPrimary else Color.Black.copy(alpha = 0.6f)
                                ) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = mode,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected)
                                            (if (skin.isDark) skin.glowPrimary else Color.Black)
                                        else skin.textPrimary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Accent ribbon
                    AccentRibbon(
                        currentAccent = luminaAccent,
                        onAccentSelected = { name ->
                            luminaAccent = name
                            prefs.edit().putString("lumina_accent", name.uppercase()).apply()
                            context.config.root.global.uiSettings.luminaAccent.set(name.uppercase())
                            context.config.writeConfig()
                        },
                        skin = skin
                    )
                }
            }

            // ── Aether sub-panel ────────────────────────────────────────────
            AnimatedVisibility(
                visible = currentSkinId == "AETHER",
                enter = expandVertically() + fadeIn(tween(250)),
                exit = shrinkVertically() + fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = skin.textPrimary.copy(alpha = 0.08f))

                    // Mode + AMOLED row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                            val isSelected = aetherMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable {
                                        aetherMode = mode
                                        prefs.edit().putString("aether_mode", mode).apply()
                                        context.config.root.global.uiSettings.aetherMode.set(mode)
                                        context.config.writeConfig()
                                    },
                                shape = if (skin.id == "AETHER") G2RoundedRectangle(10.dp) else RoundedCornerShape(10.dp),
                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f)
                                        else skin.textPrimary.copy(alpha = 0.05f),
                                border = if (isSelected) BorderStroke(
                                    1.dp,
                                    if (skin.isDark) skin.glowPrimary else Color.Black.copy(alpha = 0.6f)
                                ) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = mode,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected)
                                            (if (skin.isDark) skin.glowPrimary else Color.Black)
                                        else skin.textPrimary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        // AMOLED toggle (only when not LIGHT mode)
                        if (aetherMode != "LIGHT") {
                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable {
                                        aetherAmoled = !aetherAmoled
                                        prefs.edit().putBoolean("aether_amoled", aetherAmoled).apply()
                                        context.config.root.global.uiSettings.aetherAmoled.set(aetherAmoled)
                                        context.config.writeConfig()
                                    },
                                shape = if (skin.id == "AETHER") G2RoundedRectangle(10.dp) else RoundedCornerShape(10.dp),
                                color = if (aetherAmoled) Color.Black else skin.textPrimary.copy(alpha = 0.05f),
                                border = if (aetherAmoled) BorderStroke(1.dp, skin.glowPrimary) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (aetherAmoled) Icons.Filled.BrightnessLow
                                                      else Icons.Filled.BrightnessHigh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (aetherAmoled) skin.glowPrimary
                                               else skin.textPrimary.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    // Accent ribbon
                    AccentRibbon(
                        currentAccent = aetherAccent,
                        onAccentSelected = { name ->
                            aetherAccent = name
                            prefs.edit().putString("aether_accent", name.uppercase()).apply()
                            context.config.root.global.uiSettings.aetherAccent.set(name.uppercase())
                            context.config.writeConfig()
                        },
                        skin = skin
                    )
                }
            }
        }
    }
}

// ── Theme choice button (Aurora / Aphelion) ──────────────────────────────────

@Composable
private fun ThemeChoiceButton(
    label: String,
    subtitle: String,
    gradient: Brush,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp)
    val contentColor = if (isSelected) Color.White else (if (skin.isDark) Color.White else Color.Black.copy(alpha = 0.85f))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scaleOnPress(interactionSource)
            .clip(shape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                brush = if (isSelected) gradient else SolidColor(skin.textPrimary.copy(alpha = 0.12f)),
                shape = shape
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = shape,
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) gradient else SolidColor(skin.glassSurface))
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color.White.copy(alpha = 0.20f)
                                else skin.textPrimary.copy(alpha = 0.08f)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) Color.White else skin.glowPrimary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(18.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = label,
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = subtitle,
                            color = contentColor.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                if (isSelected) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.22f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(5.dp)
                                .size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Compact skin picker (setup-local, no config dependency) ──────────────────

private data class SetupSkinOption(
    val id: String,
    val name: String,
    val description: String,
    val previewColors: List<Color>,
    val available: Boolean
)

@Composable
private fun SetupSkinPicker(
    currentSkinId: String,
    onSkinSelected: (String) -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val scrollState = rememberScrollState()

    val showCyberware = true

    val skins = remember {
        listOfNotNull(
            SetupSkinOption("LUMINA", "Lumina",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    "Adaptive Day/Night aesthetics. Automatically shifts from ivory white to charcoal black."
                else "Requires Android 12+",
                listOf(Color(0xFFF8F3EC), Color(0xFF08080A), Color(0xFFF1D7D2)),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S),
            SetupSkinOption("AETHER", "Aether", "Functional minimalism with high-contrast solid surfaces.",
                listOf(Color(0xFFF8F3EC), Color(0xFF1A1B26), Color(0xFF8F63D8)), true),
            SetupSkinOption("LUX",    "Lux",    "Warm Ivory on silk-smooth cards. Light theme.",
                listOf(Color(0xFFF8F3EC), Color(0xFFF3EEE7), Color(0xFF8F63D8)), true),
            SetupSkinOption("UMBRA",  "Umbra",  "Deep royal indigo with a velvety finish. Atmospheric and vibrantly dark.",
                listOf(Color(0xFF14142B), Color(0xFF1F1F41), Color(0xFF7B61FF)), true),
            SetupSkinOption("AMBER",  "Amber",  "A prestigious palette of warm cream and lustrous gold.",
                listOf(Color(0xFFFCF7E8), Color(0xFFF4E3BA), Color(0xFFD4AF37)), true),
            SetupSkinOption("NOX",    "Nox",    "AMOLED Black. Dark Theme.",
                listOf(Color(0xFF000000), Color(0xFF2D2D2D), Color(0xFFFFFFFF)), true),
            if (showCyberware) SetupSkinOption("CYBER", "Cyber", "Full Cyberpunk aesthetic. Choose between Synthwave and Night City styles. Dark theme.",
                listOf(Color(0xFF090713), Color(0xFF111111), Color(0xFF05D9E8)), true) else null
        )
    }

    val selectedSkin = remember(currentSkinId) { skins.find { it.id == currentSkinId } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            skins.forEach { option ->
                SetupSkinCard(
                    option = option,
                    isSelected = currentSkinId == option.id,
                    onSelect = { if (option.available) onSkinSelected(option.id) },
                    modifier = Modifier.width(120.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        AnimatedContent(
            targetState = selectedSkin,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "skin_description"
        ) { skinTarget ->
            if (skinTarget != null) {
                Text(
                    text = skinTarget.description,
                    fontSize = 12.sp,
                    color = skin.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupSkinCard(
    option: SetupSkinOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalPurrfectSkin.current
    val borderBrush = if (isSelected) {
        if (skin.isDark) Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))
        else Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.8f)))
    } else {
        Brush.linearGradient(listOf(skin.glassBorder, skin.glassBorder))
    }
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) skin.glowPrimary.copy(alpha = 0.12f) else skin.glassSurface,
        animationSpec = tween(220),
        label = "setup_skin_card_bg"
    )
    val unavailableAlpha = if (!option.available) 0.4f else 1f

    Surface(
        modifier = modifier
            .clickable(enabled = option.available) { onSelect() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.alpha(unavailableAlpha)
            ) {
                option.previewColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, skin.textPrimary.copy(alpha = 0.1f), CircleShape)
                    )
                }
            }
            Column(
                modifier = Modifier.alpha(unavailableAlpha),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = option.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (skin.isDark) Color.White else Color(0xFF1A1A2E)
                    )
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = skin.glowPrimary.copy(alpha = 0.20f)
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = skin.glowPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (!option.available) {
                    Text(
                        text = "Android 12+",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB347)
                    )
                }
            }
        }
    }
}

// ── Accent colour ribbon (shared by Lumina + Aether) ─────────────────────────

@Composable
private fun AccentRibbon(
    currentAccent: String,
    onAccentSelected: (String) -> Unit,
    skin: PurrfectColorSet
) {
    val accents = remember {
        Catppuccin.mocha.accents
            .filter { it.first != "Espresso" && it.first != "Forest" }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            accents.forEach { (name, color) ->
                val isSelected = currentAccent.equals(name, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) skin.textPrimary
                                    else skin.textPrimary.copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                        .clickable { onAccentSelected(name) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (color.luminance() > 0.5f) skin.cardOverlayColor else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = "Accent: $currentAccent",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (skin.isDark) skin.glowPrimary.copy(alpha = 0.85f) else skin.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
