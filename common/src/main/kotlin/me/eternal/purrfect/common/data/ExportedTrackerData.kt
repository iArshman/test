package me.eternal.purrfect.common.data

data class ExportedTrackerData(
    val type: ExportType,
    val rules: List<TrackerRule>
)