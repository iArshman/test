package me.eternal.purrfect.ui.manager.pages.features.themes

import androidx.compose.runtime.*
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.currentBackStackEntryAsState
import me.eternal.purrfect.ui.manager.pages.features.FeaturesRootSection
import me.eternal.purrfect.ui.manager.pages.features.FeaturesRootSection.Companion.FEATURE_CONTAINER_ROUTE
import me.eternal.purrfect.ui.manager.pages.features.FeaturesRootSection.Companion.SEARCH_FEATURE_ROUTE
import me.eternal.purrfect.common.config.ConfigContainer
import me.eternal.purrfect.common.config.PropertyPair
import me.eternal.purrfect.common.config.toPropertyPair

@Composable
fun FeaturesRootSection.AphelionFeaturesContent(nav: NavBackStackEntry) {
    val navBackStackEntry by routes.navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    when (currentDestination?.route) {
        FEATURE_CONTAINER_ROUTE -> {
            val containerName = navBackStackEntry?.arguments?.getString("name")!!
            val propertyPair = allContainers[containerName]!!
            Container(
                configContainer = propertyPair.value.get() as ConfigContainer,
                stateKey = "${routeInfo.id}:container:$containerName",
                sectionTitle = context.translation[propertyPair.key.propertyName()],
                sectionSubtitle = context.translation[propertyPair.key.propertyDescription()],
                onBack = { routes.navController.popBackStack() }
            )
        }
        SEARCH_FEATURE_ROUTE -> {
            val keyword = navBackStackEntry?.arguments?.getString("keyword").orEmpty()
            val properties = allProperties.filter {
                isSearchVisibleProperty(it.key) && isVisibleForCurrentTarget(featureRootContainer(), it.key) && (
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
        else -> {
            Container(
                configContainer = featureRootContainer(),
                stateKey = "${routeInfo.id}:container:root"
            )
        }
    }
}

