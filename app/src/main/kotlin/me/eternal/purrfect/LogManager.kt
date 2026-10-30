package me.eternal.purrfect

import android.util.Log
import com.google.gson.GsonBuilder
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.data.FileType
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.logger.LogChannel
import me.eternal.purrfect.common.logger.LogLevel
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.hours

class LogLine(
    val logLevel: LogLevel,
    val dateTime: String,
    val tag: String,
    val message: String
) {
    companion object {
        fun fromString(line: String) = runCatching {
            val parts = line.trimEnd().split("/")
            if (parts.size != 4) return@runCatching null
            LogLine(
                LogLevel.fromLetter(parts[0]) ?: return@runCatching null,
                parts[1],
                parts[2],
                parts[3]
            )
        }.getOrNull()
    }

    override fun toString(): String {
        return "${logLevel.letter}/$dateTime/$tag/$message"
    }
}


class LogReader(
    logFile: File
) {
    private val randomAccessFile = RandomAccessFile(logFile, "r")
    private var startLineIndexes = mutableListOf<Long>()
    var lineCount = queryLineCount()

    private fun readLogLine(): LogLine? {
        val lines = StringBuilder()
        val lastPointer = randomAccessFile.filePointer
        var lastChar: Int = -1
        var bufferLength = 0
        while (true) {
            val char = randomAccessFile.read()
            if (char == -1) {
                randomAccessFile.seek(lastPointer)
                return null
            }
            if ((char == '|'.code && lastChar == '\n'.code) || bufferLength > 4096) {
                break
            }
            lines.append(char.toChar())
            bufferLength++
            lastChar = char
        }

        return LogLine.fromString(lines.trimEnd().toString())
            ?: LogLine(LogLevel.ERROR, "1970-01-01 00:00:00", "LogReader", "Failed to parse log line: $lines")
    }

    fun incrementLineCount() {
        synchronized(randomAccessFile) {
            randomAccessFile.seek(randomAccessFile.length())
            startLineIndexes.add(randomAccessFile.filePointer + 1)
            lineCount++
        }
    }

    private fun queryLineCount(): Int {
        val buffer = ByteArray(1024 * 1024)

        synchronized(randomAccessFile) {
            randomAccessFile.seek(0)
            var lineCount = 0
            var read: Int
            var lastPointer: Long = 0
            var line: StringBuilder? = null

            while (randomAccessFile.read(buffer).also { read = it } != -1) {
                for (i in 0 until read) {
                    val char = buffer[i].toInt().toChar()
                    if (line == null) {
                        line = StringBuilder()
                        lastPointer = randomAccessFile.filePointer - read + i
                    }
                    line.append(char)
                    if (char == '\n') {
                        if (line.startsWith('|')) {
                            lineCount++
                            startLineIndexes.add(lastPointer + 1)
                        }
                        line = null
                    }
                }
            }

            return lineCount
        }
    }

    private fun getLine(index: Int): String? {
        if (index <= 0 || index > lineCount) return null
        synchronized(randomAccessFile) {
            randomAccessFile.seek(startLineIndexes.getOrNull(index) ?: return null)
            return readLogLine()?.toString()
        }
    }

    fun getLogLine(index: Int): LogLine? {
        return getLine(index)?.let { LogLine.fromString(it) }
    }
}


class LogManager(
    private val remoteSideContext: RemoteSideContext
): AbstractLogger(LogChannel.MANAGER) {
    companion object {
        private val LOG_LIFETIME = 24.hours
        private const val REDDIT_LOG_OFFSET_PREF = "reddit_xposed_log_offset"
        private const val WHATSAPP_LOG_OFFSET_PREF = "whatsapp_xposed_log_offset"
    }

    private val printLogLock = Any()
    private val anonymizeLogs by lazy { !remoteSideContext.config.root.scripting.disableLogAnonymization.get() }

    var lineAddListener = { _: LogLine -> }

    private val logFolder = File(remoteSideContext.androidContext.cacheDir, "logs")
    private var logFile: File? = null

    private val uuidRegex by lazy { Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.MULTILINE) }
    private val contentUriRegex by lazy { Regex("content://[a-zA-Z0-9_\\-./]+") }
    private val filePathRegex by lazy { Regex("([a-zA-Z0-9_\\-./]+)\\.(${FileType.entries.joinToString("|") { file -> file.fileExtension.toString() }})") }

    fun init() {
        if (!logFolder.exists()) {
            logFolder.mkdirs()
        }
        logFile = remoteSideContext.sharedPreferences.getString("log_file", null)?.let { File(it) }?.takeIf { it.exists() } ?: run {
            newLogFile()
            logFile
        }

        if (System.currentTimeMillis() - remoteSideContext.sharedPreferences.getLong("last_created", 0) > LOG_LIFETIME.inWholeMilliseconds) {
            newLogFile()
        }
        syncExternalTargetLogs()
    }

    fun internalLog(tag: String, logLevel: LogLevel, message: Any?) {
        synchronized(printLogLock) {
            runCatching {
                val originalMessage = message.toString()
                val anonymizedMessage = originalMessage.let {
                    if (remoteSideContext.config.isInitialized() && anonymizeLogs)
                        it.replace(uuidRegex, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
                            .replace(contentUriRegex, "content://xxx")
                            .replace(filePathRegex, "xxxxxxxx.$2")
                    else it
                }
                val line = LogLine(
                    logLevel = logLevel,
                    dateTime = getCurrentDateTime(),
                    tag = tag,
                    message = anonymizedMessage
                )
                logFile?.appendText("|$line\n", Charsets.UTF_8)
                lineAddListener(line)
                Log.println(logLevel.priority, tag, anonymizedMessage)
            }.onFailure {
                Log.println(Log.ERROR, tag, "Failed to log message: $message")
                Log.println(Log.ERROR, tag, it.stackTraceToString())
            }
        }
    }

    private fun getCurrentDateTime(pathSafe: Boolean = false): String {
        return DateTimeFormatter.ofPattern(if (pathSafe) "yyyy-MM-dd_HH-mm-ss" else "yyyy-MM-dd HH:mm:ss").format(
            java.time.LocalDateTime.now()
        )
    }

    private fun newLogFile() {
        val currentTime = System.currentTimeMillis()
        logFile = File(logFolder, "purrfect_${getCurrentDateTime(pathSafe = true)}.log").also {
            it.createNewFile()
            remoteSideContext.sharedPreferences.edit().putString("log_file", it.absolutePath).putLong("last_created", currentTime).apply()
        }
    }

    fun clearLogs() {
        logFolder.listFiles()?.forEach { it.delete() }
        remoteSideContext.sharedPreferences.edit()
            .remove(REDDIT_LOG_OFFSET_PREF)
            .remove(WHATSAPP_LOG_OFFSET_PREF)
            .apply()
        newLogFile()
    }

    fun exportLogsToZip(outputStream: OutputStream) {
        exportLogsToZip(outputStream, null)
    }

    fun exportLogsToZip(outputStream: OutputStream, targetApp: TargetApp?) {
        ZipOutputStream(outputStream).use { zipOutputStream ->
            fun putEntry(fileName: String, writer: ZipOutputStream.() -> Unit) {
                zipOutputStream.putNextEntry(ZipEntry(fileName))
                zipOutputStream.writer()
                zipOutputStream.closeEntry()
            }

            // add device info to zip
            putEntry("device_info.json") {
                val gson = GsonBuilder().setPrettyPrinting().create()
                write(gson.toJson(remoteSideContext.installationSummary).toByteArray())
            }

            // add config
            putEntry("config.json") {
                write(remoteSideContext.config.exportToString(exportSensitiveData = false).toByteArray())
            }

            // add log files to zip
            logFolder.walk().forEach {
                if (it.isFile) {
                    val content = if (targetApp == null) {
                        it.readText(Charsets.UTF_8)
                    } else {
                        it.readLines(Charsets.UTF_8)
                            .filter { line ->
                                val parsed = LogLine.fromString(line.removePrefix("|"))
                                parsed != null && isLogForTarget(parsed, targetApp)
                            }
                            .joinToString("\n")
                            .let { text -> if (text.isBlank()) text else "$text\n" }
                    }
                    if (targetApp == null || content.isNotBlank()) {
                        putEntry(it.name) {
                            write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    fun isLogForTarget(line: LogLine, targetApp: TargetApp): Boolean {
        val isReddit = isRedditLog(line)
        val isWhatsApp = isWhatsAppLog(line)
        val isInstagram = isInstagramLog(line)
        return when (targetApp) {
            TargetApp.REDDIT -> isReddit
            TargetApp.WHATSAPP -> isWhatsApp
            TargetApp.INSTAGRAM -> isInstagram
            TargetApp.SNAPCHAT -> !isReddit && !isWhatsApp && !isInstagram
        }
    }

    private fun isRedditLog(line: LogLine): Boolean {
        return line.tag.contains("reddit", ignoreCase = true) ||
                line.message.contains("[reddit]", ignoreCase = true) ||
                line.message.contains("reddit:", ignoreCase = true)
    }

    private fun isWhatsAppLog(line: LogLine): Boolean {
        return line.tag.contains("whatsapp", ignoreCase = true) ||
                line.tag.contains("purrfectwa", ignoreCase = true) ||
                line.message.contains("[whatsapp]", ignoreCase = true) ||
                line.message.contains("whatsapp:", ignoreCase = true) ||
                line.message.contains("purrfectwa", ignoreCase = true)
    }

    private fun isInstagramLog(line: LogLine): Boolean {
        return line.tag.contains("instagram", ignoreCase = true) ||
                line.tag.contains("purrfectinsta", ignoreCase = true) ||
                line.message.contains("[instagram]", ignoreCase = true) ||
                line.message.contains("instagram:", ignoreCase = true) ||
                line.message.contains("purrfectinsta", ignoreCase = true)
    }

    private fun markExternalTargetLogs(text: String, markerTag: String, isTargetLog: (LogLine) -> Boolean): String {
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("\n") { rawLine ->
                val lineBody = rawLine.removePrefix("|").trimEnd()
                val parsed = LogLine.fromString(lineBody)
                val marked = if (parsed == null || isTargetLog(parsed)) {
                    lineBody
                } else {
                    LogLine(parsed.logLevel, parsed.dateTime, markerTag, parsed.message).toString()
                }
                "|$marked"
            }
            .let { marked -> if (marked.isBlank()) marked else "$marked\n" }
    }

    private fun markExternalRedditLogs(text: String): String {
        return markExternalTargetLogs(text, "PurrfectReddit", ::isRedditLog)
    }

    private fun markExternalWhatsAppLogs(text: String): String {
        return markExternalTargetLogs(text, "PurrfectWA", ::isWhatsAppLog)
    }

    private fun syncExternalTargetLogs() {
        syncExternalRedditLogs()
        syncExternalWhatsAppLogs()
    }

    private fun syncExternalRedditLogs() {
        syncExternalLog("reddit_xposed.log", REDDIT_LOG_OFFSET_PREF, ::markExternalRedditLogs)
    }

    private fun syncExternalWhatsAppLogs() {
        syncExternalLog("whatsapp_xposed.log", WHATSAPP_LOG_OFFSET_PREF, ::markExternalWhatsAppLogs)
    }

    private fun syncExternalLog(
        fileName: String,
        offsetPreference: String,
        marker: (String) -> String
    ) {
        synchronized(printLogLock) {
            runCatching {
                val externalLog = File(
                    "/storage/emulated/0/Android/media/${remoteSideContext.androidContext.packageName}/logs",
                    fileName
                )
                if (!externalLog.exists()) return@runCatching
                val prefs = remoteSideContext.sharedPreferences
                val storedOffset = prefs.getLong(offsetPreference, 0L)
                val offset = storedOffset.takeIf { it in 0..externalLog.length() } ?: 0L
                if (offset >= externalLog.length()) return@runCatching
                RandomAccessFile(externalLog, "r").use { input ->
                    input.seek(offset)
                    val bytes = ByteArray((externalLog.length() - offset).coerceAtMost(256 * 1024L).toInt())
                    val read = input.read(bytes)
                    if (read > 0) {
                        val text = String(bytes, 0, read, Charsets.UTF_8)
                        val completeText = if (text.endsWith("\n")) text else text.substringBeforeLast("\n", "")
                        if (completeText.isNotBlank()) {
                            logFile?.appendText(marker(completeText), Charsets.UTF_8)
                        }
                        prefs.edit().putLong(offsetPreference, input.filePointer).apply()
                    }
                }
            }
        }
    }

    fun newReader(onAddLine: (LogLine) -> Unit): LogReader {
        syncExternalTargetLogs()
        return LogReader(logFile!!).also {
            lineAddListener = { line -> it.incrementLineCount(); onAddLine(line) }
        }
    }

    override fun debug(message: Any?, tag: String) {
        internalLog(tag, LogLevel.DEBUG, message)
    }

    override fun error(message: Any?, tag: String) {
        internalLog(tag, LogLevel.ERROR, message)
    }

    override fun error(message: Any?, throwable: Throwable, tag: String) {
        internalLog(tag, LogLevel.ERROR, message)
        internalLog(tag, LogLevel.ERROR, throwable.stackTraceToString())
    }

    override fun info(message: Any?, tag: String) {
        internalLog(tag, LogLevel.INFO, message)
    }

    override fun verbose(message: Any?, tag: String) {
        internalLog(tag, LogLevel.VERBOSE, message)
    }

    override fun warn(message: Any?, tag: String) {
        internalLog(tag, LogLevel.WARN, message)
    }

    override fun assert(message: Any?, tag: String) {
        internalLog(tag, LogLevel.ASSERT, message)
    }
}
