package me.eternal.purrfect

import me.eternal.purrfect.bridge.logger.TrackerInterface
import me.eternal.purrfect.common.data.ScopedTrackerRule
import me.eternal.purrfect.common.data.TrackerEventsResult
import me.eternal.purrfect.common.data.TrackerRule
import me.eternal.purrfect.common.data.TrackerRuleEvent
import me.eternal.purrfect.common.util.toSerialized
import me.eternal.purrfect.storage.getRuleTrackerScopes
import me.eternal.purrfect.storage.getTrackerEvents
import me.eternal.purrfect.storage.updateFriendScore


class RemoteTracker(
    private val context: RemoteSideContext
): TrackerInterface.Stub() {
    fun init() {}

    override fun getTrackedEvents(eventType: String): String? {
        val events = mutableMapOf<TrackerRule, MutableList<TrackerRuleEvent>>()

        context.database.getTrackerEvents(eventType).forEach { (event, rule) ->
            events.getOrPut(rule) { mutableListOf() }.add(event)
        }

        return TrackerEventsResult(events.mapKeys {
            ScopedTrackerRule(it.key, context.database.getRuleTrackerScopes(it.key.id))
        }).toSerialized()
    }

    override fun updateFriendScore(userId: String, score: Long): Long {
        return context.database.updateFriendScore(userId, score)
    }
}