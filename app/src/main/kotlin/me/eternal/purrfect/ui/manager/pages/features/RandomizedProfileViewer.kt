package me.eternal.purrfect.ui.manager.pages.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.core.features.impl.experiments.RandomizedDeviceProfile
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import androidx.compose.ui.platform.LocalContext
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

private object RandomizedProfileViewerSkinPalette {
    @Composable
    private fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else PurrfectPalette.textPrimary
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else PurrfectPalette.textSecondary
}

@Composable
fun RandomizedProfileViewer(
    profile: RandomizedDeviceProfile,
    onCopy: (String) -> Unit
) {
    val sections = remember(profile) {
        listOf(
            ProfileSection(
                title = "Phone Identity",
                icon = Icons.Default.Smartphone,
                items = listOf(
                    ProfileItem("Manufacturer", profile.deviceInfo.manufacturer),
                    ProfileItem("Model", profile.deviceInfo.model),
                    ProfileItem("Android ID", profile.androidId, isSensitive = true),
                    ProfileItem("GSF ID", profile.gsfId, isSensitive = true),
                    ProfileItem("Advertising ID", profile.advertisingId, isSensitive = true)
                )
            ),
            ProfileSection(
                title = "Build & System",
                icon = Icons.Default.SettingsSuggest,
                items = listOf(
                    ProfileItem("Android Release", profile.androidRelease),
                    ProfileItem("Display ID", profile.buildDisplayId),
                    ProfileItem("Incremental", profile.buildIncremental),
                    ProfileItem("Fingerprint", profile.buildFingerprint, isSensitive = true),
                    ProfileItem("Build Time", SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(profile.buildTime))),
                    ProfileItem("ABIs", profile.supportedAbis.joinToString(", "))
                )
            ),
            ProfileSection(
                title = "Network Armor",
                icon = Icons.Default.Shield,
                items = listOf(
                    ProfileItem("Spoofed IP", profile.ipAddress),
                    ProfileItem("WiFi SSID", profile.wifiSsid),
                    ProfileItem("WiFi MAC", profile.wifiMacAddress, isSensitive = true),
                    ProfileItem("Bluetooth MAC", profile.bluetoothMacAddress, isSensitive = true)
                )
            ),
            ProfileSection(
                title = "Regional Context",
                icon = Icons.Default.Public,
                items = listOf(
                    ProfileItem("Locale Tag", profile.localeTag),
                    ProfileItem("Country ISO", profile.countryIso),
                    ProfileItem("Time Zone", profile.timeZoneId),
                    ProfileItem("Carrier", profile.simOperatorName)
                )
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 450.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(sections) { section ->
            ProfileSectionCard(section, onCopy)
        }
    }
}

@Composable
private fun ProfileSectionCard(
    section: ProfileSection,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RandomizedProfileViewerSkinPalette.textPrimary.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = RandomizedProfileViewerSkinPalette.glowPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = section.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RandomizedProfileViewerSkinPalette.glowPrimary
            )
        }

        section.items.forEach { item ->
            ProfileRow(item, onCopy)
        }
    }
}

@Composable
private fun ProfileRow(
    item: ProfileItem,
    onCopy: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(item.value) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                fontSize = 11.sp,
                color = RandomizedProfileViewerSkinPalette.textSecondary.copy(alpha = 0.85f)
            )
            Text(
                text = item.value,
                fontSize = 13.sp,
                color = RandomizedProfileViewerSkinPalette.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
        }
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = RandomizedProfileViewerSkinPalette.textPrimary.copy(alpha = 0.3f),
            modifier = Modifier.size(14.dp)
        )
    }
}

private data class ProfileSection(
    val title: String,
    val icon: ImageVector,
    val items: List<ProfileItem>
)

private data class ProfileItem(
    val label: String,
    val value: String,
    val isSensitive: Boolean = false
)
