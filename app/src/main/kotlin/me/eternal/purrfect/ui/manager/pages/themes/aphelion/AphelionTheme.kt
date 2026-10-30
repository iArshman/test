package me.eternal.purrfect.ui.manager.pages.themes.aphelion

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfect.ui.manager.ThemeContract
import me.eternal.purrfect.ui.manager.pages.home.HomeRootSection
import me.eternal.purrfect.ui.manager.pages.home.HomeSettings
import me.eternal.purrfect.ui.manager.pages.home.HomeAbout
import me.eternal.purrfect.ui.manager.pages.home.HomeLogs
import me.eternal.purrfect.ui.manager.pages.TasksRootSection
import me.eternal.purrfect.ui.manager.pages.features.FeaturesRootSection
import me.eternal.purrfect.ui.manager.pages.social.SocialRootSection
import me.eternal.purrfect.ui.manager.pages.scripting.ScriptingRootSection
import me.eternal.purrfect.ui.manager.pages.tracker.FriendTrackerManagerRoot
import me.eternal.purrfect.ui.manager.pages.social.themes.AphelionSocialContent
import me.eternal.purrfect.ui.manager.pages.tasks.themes.AphelionTasksContent
import me.eternal.purrfect.ui.manager.pages.features.themes.AphelionFeaturesContent
import me.eternal.purrfect.ui.manager.pages.home.themes.AphelionSettingsContent
import me.eternal.purrfect.ui.manager.pages.home.themes.AphelionAboutContent
import me.eternal.purrfect.ui.manager.pages.home.themes.AphelionLogsContent
import me.eternal.purrfect.ui.manager.pages.scripting.themes.AphelionScriptingContent
import me.eternal.purrfect.ui.manager.pages.tracker.themes.AphelionFriendTrackerContent
import me.eternal.purrfect.common.ui.theme.*

object AphelionTheme : ThemeContract {
    @Composable override fun HomeRootSection.HomeScreen(nav: NavBackStackEntry) = AphelionHomeView(nav, routes)
    @Composable override fun HomeSettings.SettingsScreen(nav: NavBackStackEntry) = AphelionSettingsContent(nav)
    @Composable override fun HomeAbout.AboutScreen(nav: NavBackStackEntry) = AphelionAboutContent(nav)
    @Composable override fun HomeLogs.LogsScreen(nav: NavBackStackEntry) = AphelionLogsContent(nav)
    @Composable override fun SocialRootSection.SocialScreen(nav: NavBackStackEntry) = AphelionSocialContent(nav)
    @Composable override fun TasksRootSection.TasksScreen(nav: NavBackStackEntry) = AphelionTasksContent(nav)
    @Composable override fun FeaturesRootSection.FeaturesScreen(nav: NavBackStackEntry) = AphelionFeaturesContent(nav)
    @Composable override fun ScriptingRootSection.ScriptingScreen(nav: NavBackStackEntry) = AphelionScriptingContent(nav)
    @Composable override fun FriendTrackerManagerRoot.FriendTrackerScreen(nav: NavBackStackEntry) = AphelionFriendTrackerContent(nav)
}
