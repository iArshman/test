package me.eternal.purrfect.core.features.impl.tweaks

import me.eternal.purrfect.common.data.MessagingRuleType
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class RequerySqlite : Feature("Requery Sqlite") {
    override fun init() {
        val hideQuickAddSuggestions = context.config.userInterface.hideQuickAddSuggestions.get()

        // 1. Database Integrity Restoration:
        // We have decommissioned the 'hideSuggestedStories' and 'hideFriendFeedEntry' SQL hooks.
        // These were causing global friend disappearance and database corruption.
        // Hiding is now handled at the safe UI layer (UITweaks.kt and HideFriendFeedEntry.kt).
        
        if (!hideQuickAddSuggestions) return

        findClass("io.requery.android.database.sqlite.SQLiteDatabase").hook("rawQueryWithFactory", HookStage.BEFORE) { param ->
            var sqlRequest = param.argNullable<String>(1) ?: return@hook
            val sqlUpper = sqlRequest.uppercase().trim()

            fun patchRequest(condition: String) {
                sqlRequest.lastIndexOf("WHERE").takeIf { it != -1 }?.let {
                    sqlRequest = sqlRequest.substring(0, it + 5) + " $condition AND " + sqlRequest.substring(it + 5)
                    param.setArg(1, sqlRequest)
                }
            }

            fun isSuggestionQuery() = sqlRequest.contains("SuggestedFriendPlacement") ||
                                     sqlRequest.contains("TopSuggestedFriend") ||
                                     sqlRequest.contains("TopSuggestedFriendV2") ||
                                     sqlRequest.contains("SuggestedFriend")

            if (hideQuickAddSuggestions && sqlUpper.startsWith("SELECT") && isSuggestionQuery()) {
                val isDisplayQuery = sqlRequest.contains("FriendWithUsername") ||
                                    sqlRequest.contains("FROM TopSuggestedFriend") ||
                                    (sqlRequest.contains("UNION") && sqlRequest.contains("FROM SuggestedFriend"))
                val isCountQuery = sqlUpper.contains("SELECT 0") || sqlUpper.contains("SELECT COUNT")
                
                if (isDisplayQuery || isCountQuery) {
                    patchRequest("0 = 1")
                }
            }
        }
    }
}
