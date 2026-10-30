package me.eternal.purrfect.storage

import me.eternal.purrfect.common.data.ExportedTrackerData
import me.eternal.purrfect.common.data.TrackerDataManager
import me.eternal.purrfect.storage.AppDatabase

class TrackerDataManagerImpl(private val db: AppDatabase) : TrackerDataManager {
    override fun getExportedTrackerData(): ExportedTrackerData {
        return ExportedTrackerData(
            type = me.eternal.purrfect.common.data.ExportType.BULK,
            rules = db.getTrackerRulesDesc().map { rule ->
                rule.copy(
                    events = db.getTrackerEvents(rule.id),
                    scopes = db.getRuleTrackerScopes(rule.id)
                )
            }
        )
    }

    override fun getExportedTrackerData(ruleId: Int): ExportedTrackerData? {
        return db.getTrackerRule(ruleId)?.let {
            ExportedTrackerData(
                type = me.eternal.purrfect.common.data.ExportType.SINGLE,
                rules = listOf(it.copy(
                    events = db.getTrackerEvents(it.id),
                    scopes = db.getRuleTrackerScopes(it.id)
                ))
            )
        }
    }

    override fun importTrackerData(data: ExportedTrackerData) {
        if (data.type == me.eternal.purrfect.common.data.ExportType.BULK) {
            db.clearTrackerRules()
        }
        data.rules.forEach { rule ->
            if (db.getTrackerRuleByName(rule.name) != null) {
                return@forEach
            }
            val ruleId = db.newTrackerRule(rule.name, rule.author)
            db.setTrackerRuleState(ruleId, rule.enabled)
            rule.events?.forEach { event ->
                db.addOrUpdateTrackerRuleEvent(
                    ruleId = ruleId,
                    eventType = event.eventType,
                    params = event.params,
                    actions = event.actions
                )
            }
            rule.scopes?.let { scopes ->
                if (scopes.isNotEmpty()) {
                    val scopeType = scopes.values.first()
                    val scopeIds = scopes.keys.toList()
                    db.setRuleTrackerScopes(ruleId, scopeType, scopeIds)
                }
            }
        }
    }
}
