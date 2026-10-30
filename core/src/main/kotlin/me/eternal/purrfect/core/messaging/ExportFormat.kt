package me.eternal.purrfect.core.messaging;

enum class ExportFormat(
    val extension: String,
){
    JSON("json"),
    TEXT("txt"),
    HTML("html");
}
