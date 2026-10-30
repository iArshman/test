package me.eternal.purrfect.common.bridge.wrapper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import me.eternal.purrfect.bridge.logger.BridgeLoggedMessage
import me.eternal.purrfect.bridge.logger.LoggedChatEdit
import me.eternal.purrfect.bridge.logger.LoggerInterface
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.data.StoryData
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.util.SQLiteDatabaseHelper
import me.eternal.purrfect.common.util.ktx.getBlobOrNull
import me.eternal.purrfect.common.util.ktx.getIntOrNull
import me.eternal.purrfect.common.util.ktx.getLongOrNull
import me.eternal.purrfect.common.util.ktx.getStringOrNull
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import java.io.File
import java.io.InputStream
import java.util.UUID

class LoggedMessage(
    val messageId: Long,
    val conversationId: String,
    val userId: String,
    val username: String,
    val sendTimestamp: Long,
    val addedTimestamp: Long,
    val groupTitle: String?,
    val messageData: ByteArray,
)

class ConversationInfo(
    val conversationId: String,
    val participantSize: Int,
    val groupTitle: String?,
    val usernames: List<String>
)

data class TrackerLog(
    val id: Int,
    val timestamp: Long,
    val conversationId: String,
    val conversationTitle: String?,
    val isGroup: Boolean,
    val username: String,
    val userId: String,
    val eventType: String,
    val data: String
) {
    fun toJson(): JsonObject {
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("timestamp", timestamp)
            addProperty("conversationId", conversationId)
            addProperty("conversationTitle", conversationTitle)
            addProperty("isGroup", isGroup)
            addProperty("username", username)
            addProperty("userId", userId)
            addProperty("eventType", eventType)
            addProperty("data", data)
        }
    }

    fun toCsv(): String {
        return "$id,$timestamp,$conversationId,$conversationTitle,$isGroup,$username,$userId,$eventType,$data"
    }
}

data class LoggerConversationExportTarget(
    val conversationId: String,
    val groupTitle: String?,
    val usernames: List<String>,
    val userIds: List<String>,
    val messageCount: Int
)

data class ConversationExportResult(
    val messageCount: Int,
    val chatEditCount: Int,
    val trackerEventCount: Int
)

data class DatabaseImportResult(
    val messageCount: Int,
    val storyCount: Int
)

class LoggerWrapper(
    val databaseFile: File,
    private val readOnly: Boolean = false
): LoggerInterface.Stub() {
    companion object {
        private val MESSAGE_LOGGER_SCHEMA = mapOf(
            "messages" to listOf(
                "id INTEGER PRIMARY KEY",
                "message_id BIGINT",
                "conversation_id VARCHAR",
                "user_id CHAR(36)",
                "username VARCHAR",
                "send_timestamp BIGINT",
                "added_timestamp BIGINT",
                "group_title VARCHAR",
                "message_data BLOB"
            ),
            "chat_edits" to listOf(
                "id INTEGER PRIMARY KEY",
                "edit_number INTEGER",
                "added_timestamp BIGINT",
                "conversation_id VARCHAR",
                "message_id BIGINT",
                "message_text BLOB"
            ),
            "stories" to listOf(
                "id INTEGER PRIMARY KEY",
                "added_timestamp BIGINT",
                "user_id VARCHAR",
                "posted_timestamp BIGINT",
                "created_timestamp BIGINT",
                "url VARCHAR",
                "encryption_key BLOB",
                "encryption_iv BLOB"
            ),
            "tracker_events" to listOf(
                "id INTEGER PRIMARY KEY",
                "timestamp BIGINT",
                "conversation_id CHAR(36)",
                "conversation_title VARCHAR",
                "is_group BOOLEAN",
                "username VARCHAR",
                "user_id VARCHAR",
                "event_type VARCHAR",
                "data VARCHAR"
            )
        )
    }

    constructor(context: Context, uri: Uri? = null): this(
        uri?.path?.let { File(it) } ?: File(context.getDatabasePath(InternalFileHandleType.MESSAGE_LOGGER.fileName).absolutePath),
        uri != null
    )

    private var _database: SQLiteDatabase? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coroutineScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val gson by lazy { GsonBuilder().create() }

    private fun openDatabase(file: File, readOnly: Boolean): SQLiteDatabase {
        val dbFlags = if (readOnly) {
            SQLiteDatabase.OPEN_READONLY
        } else {
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
        }
        return SQLiteDatabase.openDatabase(file.absolutePath, null, dbFlags).also { openedDatabase ->
            if (!readOnly) {
                SQLiteDatabaseHelper.createTablesFromSchema(openedDatabase, MESSAGE_LOGGER_SCHEMA)
            }
        }
    }

    private fun closeDatabaseLocked() {
        _database?.takeIf { it.isOpen }?.close()
        _database = null
    }

    private val database get() = synchronized(this) {
        _database?.takeIf { it.isOpen } ?: run {
            closeDatabaseLocked()
            val openedDatabase = openDatabase(databaseFile, readOnly)
            _database = openedDatabase
            openedDatabase
        }
    }

    protected fun finalize() {
        synchronized(this) {
            closeDatabaseLocked()
        }
    }

    fun init() {

    }

    private fun resolveDatabaseSidecars(file: File): List<File> {
        return listOf(
            file,
            File("${file.absolutePath}-wal"),
            File("${file.absolutePath}-shm"),
            File("${file.absolutePath}-journal")
        )
    }

    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to delete ${file.name}")
        }
    }

    private fun replaceFile(source: File, target: File) {
        if (!source.exists()) {
            throw IllegalStateException("Missing source file ${source.name}")
        }
        if (source.renameTo(target)) return
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (!source.delete()) {
            throw IllegalStateException("Failed to delete temporary file ${source.name}")
        }
    }

    private fun requireCompatibleSchema(db: SQLiteDatabase) {
        MESSAGE_LOGGER_SCHEMA.forEach { (tableName, columns) ->
            val existingColumns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val columnName = cursor.getStringOrNull("name") ?: continue
                    val columnType = cursor.getStringOrNull("type") ?: ""
                    existingColumns.add("$columnName $columnType".trim())
                }
            }
            if (existingColumns.isEmpty()) {
                throw IllegalStateException("Selected database is missing required table $tableName")
            }

            val missingColumns = columns.filter { expectedColumn ->
                !expectedColumn.uppercase().startsWith("PRIMARY KEY") &&
                    existingColumns.none { existingColumn -> expectedColumn.startsWith(existingColumn) }
            }
            if (missingColumns.isNotEmpty()) {
                throw IllegalStateException(
                    "Selected database has incompatible schema for $tableName: ${missingColumns.joinToString()}"
                )
            }
        }
    }

    private fun requireIntegrity(db: SQLiteDatabase) {
        val integrityResult = db.rawQuery("PRAGMA integrity_check(1)", null).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
        if (integrityResult == null || !integrityResult.equals("ok", ignoreCase = true)) {
            throw IllegalStateException("Selected database failed integrity check: ${integrityResult ?: "unknown"}")
        }
    }

    private fun getTableCount(db: SQLiteDatabase, tableName: String): Int {
        return db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            if (!cursor.moveToFirst()) 0 else cursor.getInt(0)
        }
    }

    fun importDatabase(inputStream: InputStream): DatabaseImportResult {
        if (readOnly) {
            throw IllegalStateException("Cannot import into read-only logger")
        }

        val databaseDir = databaseFile.parentFile
            ?: throw IllegalStateException("Cannot resolve message logger database directory")
        if (!databaseDir.exists() && !databaseDir.mkdirs()) {
            throw IllegalStateException("Failed to create message logger directory")
        }

        val tempFile = File(
            databaseDir,
            "${databaseFile.name}.import-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        )
        val backupFile = File(
            databaseDir,
            "${databaseFile.name}.backup-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        )

        try {
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val copiedBytes = input.copyTo(output)
                    if (copiedBytes <= 0L) {
                        throw IllegalStateException("Selected backup is empty")
                    }
                }
            }

            openDatabase(tempFile, readOnly = true).use { importedDatabase ->
                requireIntegrity(importedDatabase)
                requireCompatibleSchema(importedDatabase)
            }

            synchronized(this) {
                closeDatabaseLocked()
                val hadExistingDatabase = databaseFile.exists()
                if (hadExistingDatabase) {
                    databaseFile.inputStream().use { input ->
                        backupFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                try {
                    resolveDatabaseSidecars(databaseFile).forEach(::deleteIfExists)
                    replaceFile(tempFile, databaseFile)
                    resolveDatabaseSidecars(databaseFile).filter { it != databaseFile }.forEach(::deleteIfExists)

                    val reopenedDatabase = openDatabase(databaseFile, readOnly = false)
                    val importResult = DatabaseImportResult(
                        messageCount = getTableCount(reopenedDatabase, "messages"),
                        storyCount = getTableCount(reopenedDatabase, "stories")
                    )
                    _database = reopenedDatabase
                    deleteIfExists(backupFile)
                    return importResult
                } catch (throwable: Throwable) {
                    runCatching {
                        resolveDatabaseSidecars(databaseFile).forEach(::deleteIfExists)
                        if (backupFile.exists()) {
                            replaceFile(backupFile, databaseFile)
                        }
                    }
                    closeDatabaseLocked()
                    throw throwable
                }
            }
        } finally {
            runCatching { deleteIfExists(tempFile) }
            runCatching { deleteIfExists(backupFile) }
        }
    }

    override fun getLoggedIds(conversationId: Array<String>, limit: Int): LongArray {
        if (conversationId.any {
            runCatching { UUID.fromString(it) }.isFailure
        }) return longArrayOf()

        return database.rawQuery("SELECT message_id FROM messages WHERE conversation_id IN (${
            conversationId.joinToString(",") { "'$it'" }
        }) ORDER BY message_id DESC LIMIT $limit", null).use {
            val ids = mutableListOf<Long>()
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
            ids.toLongArray()
        }
    }

    override fun getMessage(conversationId: String?, id: Long): ByteArray? {
        return database.rawQuery(
            "SELECT message_data FROM messages WHERE conversation_id = ? AND message_id = ?",
            arrayOf(conversationId, id.toString())
        ).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
    }

    override fun addMessage(bridgeLoggedMessage: BridgeLoggedMessage) {
        addMessages(listOf(bridgeLoggedMessage))
    }

    override fun addMessages(messages: List<BridgeLoggedMessage>) {
        if (messages.isEmpty()) return

        database.beginTransaction()
        try {
            messages.forEach { bridgeLoggedMessage ->
                val hasMessage = database.rawQuery("SELECT message_id FROM messages WHERE conversation_id = ? AND message_id = ?", arrayOf(bridgeLoggedMessage.conversationId, bridgeLoggedMessage.messageId.toString())).use {
                    it.moveToFirst()
                    it.count > 0
                }

                if (!hasMessage) {
                    database.insert("messages", null, ContentValues().apply {
                        put("message_id", bridgeLoggedMessage.messageId)
                        put("conversation_id", bridgeLoggedMessage.conversationId)
                        put("user_id", bridgeLoggedMessage.userId)
                        put("username", bridgeLoggedMessage.username)
                        put("send_timestamp", bridgeLoggedMessage.sendTimestamp)
                        put("added_timestamp", System.currentTimeMillis())
                        put("group_title", bridgeLoggedMessage.groupTitle)
                        put("message_data", bridgeLoggedMessage.messageData)
                    })
                }

                // handle message edits
                runCatching {
                    val messageObject = gson.fromJson(
                        bridgeLoggedMessage.messageData.toString(Charsets.UTF_8),
                        JsonObject::class.java
                    )
                    if (messageObject.getAsJsonObject("mMessageContent")
                            ?.getAsJsonPrimitive("mContentType")?.asString != "CHAT"
                    ) return@forEach

                    val metadata = messageObject.getAsJsonObject("mMetadata")
                    if (metadata.get("mIsEdited")?.asBoolean != true) return@forEach

                    val messageTextContent =
                        messageObject.getAsJsonObject("mMessageContent")?.getAsJsonArray("mContent")
                            ?.map { it.asByte }?.toByteArray()?.let {
                                ProtoReader(it).getString(2, 1)
                            } ?: return@forEach

                    database.rawQuery(
                        "SELECT MAX(edit_number), message_text FROM chat_edits WHERE conversation_id = ? AND message_id = ?",
                        arrayOf(bridgeLoggedMessage.conversationId, bridgeLoggedMessage.messageId.toString())
                    ).use {
                        it.moveToFirst()
                        val editNumber = it.getInt(0)
                        val lastEditedMessage = it.getString(1)

                        if (lastEditedMessage == messageTextContent) return@forEach

                        database.insert("chat_edits", null, ContentValues().apply {
                            put("edit_number", editNumber + 1)
                            put("added_timestamp", System.currentTimeMillis())
                            put("conversation_id", bridgeLoggedMessage.conversationId)
                            put("message_id", bridgeLoggedMessage.messageId)
                            put("message_text", messageTextContent)
                        })
                    }
                }.onFailure {
                    AbstractLogger.directDebug("Failed to handle message edit: ${it.message}")
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun purgeAll(maxAge: Long? = null) {
        coroutineScope.launch {
            maxAge?.let {
                val maxTime = System.currentTimeMillis() - it
                database.execSQL("DELETE FROM messages WHERE added_timestamp < ?", arrayOf(maxTime.toString()))
                database.execSQL("DELETE FROM chat_edits WHERE added_timestamp < ?", arrayOf(maxTime.toString()))
                database.execSQL("DELETE FROM stories WHERE added_timestamp < ?", arrayOf(maxTime.toString()))
            } ?: run {
                database.execSQL("DELETE FROM messages")
                database.execSQL("DELETE FROM chat_edits")
                database.execSQL("DELETE FROM stories")
            }
        }
    }

    fun getStoredMessageCount(): Int {
        return database.rawQuery("SELECT COUNT(*) FROM messages", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    fun getStoredStoriesCount(): Int {
        return database.rawQuery("SELECT COUNT(*) FROM stories", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    override fun deleteMessage(conversationId: String?, messageId: Long) {
        if (conversationId.isNullOrEmpty()) return
        coroutineScope.launch {
            database.execSQL("DELETE FROM messages WHERE conversation_id = ? AND message_id = ?", arrayOf(conversationId, messageId.toString()))
            database.execSQL("DELETE FROM chat_edits WHERE conversation_id = ? AND message_id = ?", arrayOf(conversationId, messageId.toString()))
        }
    }

    override fun addStory(userId: String, url: String, postedAt: Long, createdAt: Long, key: ByteArray?, iv: ByteArray?): Boolean {
        if (database.rawQuery("SELECT id FROM stories WHERE user_id = ? AND url = ?", arrayOf(userId, url)).use {
            it.moveToFirst()
        }) {
            return false
        }
        runBlocking(coroutineScope.coroutineContext) {
            database.insert("stories", null, ContentValues().apply {
                put("user_id", userId)
                put("added_timestamp", System.currentTimeMillis())
                put("url", url)
                put("posted_timestamp", postedAt)
                put("created_timestamp", createdAt)
                put("encryption_key", key)
                put("encryption_iv", iv)
            })
        }
        return true
    }

    override fun logTrackerEvent(
        conversationId: String?,
        conversationTitle: String?,
        isGroup: Boolean,
        username: String?,
        userId: String?,
        eventType: String?,
        data: String?
    ) {
        runBlocking(coroutineScope.coroutineContext) {
            database.insert("tracker_events", null, ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("conversation_id", conversationId ?: "")
                put("conversation_title", conversationTitle)
                put("is_group", isGroup)
                put("username", username ?: "unknown")
                put("user_id", userId ?: "")
                put("event_type", eventType ?: "")
                put("data", data ?: "")
            })
        }
    }

    fun deleteTrackerLog(id: Int) {
        coroutineScope.launch {
            database.execSQL("DELETE FROM tracker_events WHERE id = ?", arrayOf(id.toString()))
        }
    }

    fun getLogs(
        pageIndex: Int,
        pageSize: Int,
        reverseOrder: Boolean = true,
        timestamp: Long? = null,
        filter: ((TrackerLog) -> Boolean)? = null
    ): List<TrackerLog> {
        return database.rawQuery("SELECT * FROM tracker_events " +
                "WHERE timestamp ${if (reverseOrder) "<" else ">"} ? " +
                "ORDER BY timestamp ${if (reverseOrder) "DESC" else ""} " +
                "LIMIT $pageSize OFFSET ${pageIndex * pageSize}", arrayOf((timestamp ?: if (reverseOrder) Long.MAX_VALUE else 0).toString())).use {
            val logs = mutableListOf<TrackerLog>()
            while (it.moveToNext()) {
                val log = TrackerLog(
                    id = it.getIntOrNull("id") ?: continue,
                    timestamp = it.getLongOrNull("timestamp") ?: continue,
                    conversationId = it.getStringOrNull("conversation_id") ?: continue,
                    conversationTitle = it.getStringOrNull("conversation_title"),
                    isGroup = it.getIntOrNull("is_group") == 1,
                    username = it.getStringOrNull("username") ?: continue,
                    userId = it.getStringOrNull("user_id") ?: continue,
                    eventType = it.getStringOrNull("event_type") ?: continue,
                    data = it.getStringOrNull("data") ?: continue
                )
                if (filter != null && !filter(log)) continue
                logs.add(log)
            }
            logs
        }
    }

    fun purgeTrackerLogs(maxAge: Long) {
        coroutineScope.launch {
            val maxTime = System.currentTimeMillis() - maxAge
            database.execSQL("DELETE FROM tracker_events WHERE timestamp < ?", arrayOf(maxTime.toString()))
        }
    }

    fun findConversation(search: String): List<String> {
        return database.rawQuery("SELECT DISTINCT conversation_id FROM tracker_events WHERE is_group = 1 AND conversation_id LIKE ?", arrayOf("%$search%")).use {
            val conversations = mutableListOf<String>()
            while (it.moveToNext()) {
                conversations.add(it.getString(0))
            }
            conversations
        }
    }

    fun findUsername(search: String): List<String> {
        return database.rawQuery("SELECT DISTINCT username FROM tracker_events WHERE username LIKE ?", arrayOf("%$search%")).use {
            val usernames = mutableListOf<String>()
            while (it.moveToNext()) {
                usernames.add(it.getString(0))
            }
            usernames
        }
    }


    fun getStories(userId: String, from: Long, limit: Int = Int.MAX_VALUE): Map<Long, StoryData> {
        val stories = sortedMapOf<Long, StoryData>()
        database.rawQuery("SELECT * FROM stories WHERE user_id = ? AND posted_timestamp < ? ORDER BY posted_timestamp DESC LIMIT $limit", arrayOf(userId, from.toString())).use {
            while (it.moveToNext()) {
                stories[it.getLongOrNull("posted_timestamp") ?: continue] = StoryData(
                    url = it.getStringOrNull("url") ?: continue,
                    postedAt = it.getLongOrNull("posted_timestamp") ?: continue,
                    createdAt = it.getLongOrNull("created_timestamp") ?: continue,
                    key = it.getBlobOrNull("encryption_key"),
                    iv = it.getBlobOrNull("encryption_iv")
                )
            }
        }
        return stories
    }

    fun getAllConversations(): List<String> {
        return database.rawQuery("SELECT DISTINCT conversation_id FROM messages", null).use {
            val conversations = mutableListOf<String>()
            while (it.moveToNext()) {
                conversations.add(it.getString(0))
            }
            conversations
        }
    }

    fun getConversationInfo(conversationId: String): ConversationInfo? {
        val usernames = database.rawQuery("SELECT DISTINCT username FROM messages WHERE conversation_id = ?", arrayOf(conversationId)).use {
            val usernames = mutableListOf<String>()
            while (it.moveToNext()) {
                usernames.add(it.getString(0))
            }
            usernames
        }

        if (usernames.size > 2) { usernames.remove("myai") }

        val groupTitle = if (usernames.size > 2) database.rawQuery("SELECT group_title FROM messages WHERE conversation_id = ? AND group_title IS NOT NULL LIMIT 1", arrayOf(conversationId)).use {
            if (!it.moveToFirst()) return@use null
            it.getStringOrNull("group_title")
        } else null

        return ConversationInfo(conversationId, usernames.size, groupTitle, usernames)
    }

    fun getConversationExportTargets(): List<LoggerConversationExportTarget> {
        val groupedConversations = mutableListOf<Triple<String, String?, Int>>()
        database.rawQuery(
            "SELECT conversation_id, MAX(group_title) AS group_title, COUNT(*) AS message_count, MAX(send_timestamp) AS last_timestamp " +
                "FROM messages WHERE conversation_id IS NOT NULL AND TRIM(conversation_id) != '' " +
                "GROUP BY conversation_id ORDER BY last_timestamp DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.getStringOrNull("conversation_id")?.takeIf { it.isNotBlank() } ?: continue
                groupedConversations.add(
                    Triple(
                        conversationId,
                        cursor.getStringOrNull("group_title"),
                        cursor.getIntOrNull("message_count") ?: 0
                    )
                )
            }
        }

        return groupedConversations.map { (conversationId, groupTitle, messageCount) ->
            val userIds = linkedSetOf<String>()
            val usernames = linkedSetOf<String>()
            database.rawQuery(
                "SELECT DISTINCT user_id, username FROM messages WHERE conversation_id = ?",
                arrayOf(conversationId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getStringOrNull("user_id")?.takeIf { it.isNotBlank() }?.let { userIds.add(it) }
                    cursor.getStringOrNull("username")?.takeIf { it.isNotBlank() }?.let { usernames.add(it) }
                }
            }
            LoggerConversationExportTarget(
                conversationId = conversationId,
                groupTitle = groupTitle,
                usernames = usernames.toList(),
                userIds = userIds.toList(),
                messageCount = messageCount
            )
        }
    }

    fun exportConversationDatabase(
        outputFile: File,
        conversationId: String,
        userIds: Collection<String> = emptyList()
    ): ConversationExportResult {
        val normalizedConversationId = conversationId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Conversation ID cannot be empty")
        val normalizedUserIds = userIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
            .toMutableSet()
            .also { ids ->
                if (ids.isEmpty()) {
                    database.rawQuery(
                        "SELECT DISTINCT user_id FROM messages WHERE conversation_id = ? AND user_id IS NOT NULL AND TRIM(user_id) != ''",
                        arrayOf(normalizedConversationId)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            cursor.getStringOrNull("user_id")?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                        }
                    }
                }
            }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IllegalStateException("Failed to prepare export file")
        }

        val outputDatabase = SQLiteDatabase.openDatabase(
            outputFile.absolutePath,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
        )
        var transactionStarted = false
        try {
            SQLiteDatabaseHelper.createTablesFromSchema(outputDatabase, MESSAGE_LOGGER_SCHEMA)
            outputDatabase.beginTransaction()
            transactionStarted = true

            val messageWhereClause = buildString {
                append("conversation_id = ?")
                if (normalizedUserIds.isNotEmpty()) {
                    append(" AND user_id IN (${normalizedUserIds.joinToString(",") { "?" }})")
                }
            }
            val messageWhereArgs = mutableListOf(normalizedConversationId).apply {
                addAll(normalizedUserIds)
            }.toTypedArray()

            val messageCount = copyQueryRows(
                sourceQuery = "SELECT * FROM messages WHERE $messageWhereClause ORDER BY send_timestamp ASC",
                sourceArgs = messageWhereArgs,
                targetDatabase = outputDatabase,
                targetTable = "messages"
            )

            val chatEditCount = copyQueryRows(
                sourceQuery = "SELECT * FROM chat_edits WHERE conversation_id = ? AND message_id IN (SELECT message_id FROM messages WHERE $messageWhereClause) ORDER BY added_timestamp ASC",
                sourceArgs = arrayOf(normalizedConversationId, *messageWhereArgs),
                targetDatabase = outputDatabase,
                targetTable = "chat_edits"
            )

            val trackerWhereClause = buildString {
                append("conversation_id = ?")
                if (normalizedUserIds.isNotEmpty()) {
                    append(" AND user_id IN (${normalizedUserIds.joinToString(",") { "?" }})")
                }
            }
            val trackerArgs = mutableListOf(normalizedConversationId).apply {
                addAll(normalizedUserIds)
            }.toTypedArray()
            val trackerEventCount = copyQueryRows(
                sourceQuery = "SELECT * FROM tracker_events WHERE $trackerWhereClause ORDER BY timestamp ASC",
                sourceArgs = trackerArgs,
                targetDatabase = outputDatabase,
                targetTable = "tracker_events"
            )

            outputDatabase.setTransactionSuccessful()
            return ConversationExportResult(
                messageCount = messageCount,
                chatEditCount = chatEditCount,
                trackerEventCount = trackerEventCount
            )
        } finally {
            if (transactionStarted) {
                outputDatabase.endTransaction()
            }
            outputDatabase.close()
        }
    }

    private fun cursorToContentValues(cursor: Cursor): ContentValues {
        return ContentValues(cursor.columnCount).apply {
            for (columnIndex in 0 until cursor.columnCount) {
                val columnName = cursor.getColumnName(columnIndex)
                when (cursor.getType(columnIndex)) {
                    Cursor.FIELD_TYPE_NULL -> putNull(columnName)
                    Cursor.FIELD_TYPE_INTEGER -> put(columnName, cursor.getLong(columnIndex))
                    Cursor.FIELD_TYPE_FLOAT -> put(columnName, cursor.getDouble(columnIndex))
                    Cursor.FIELD_TYPE_STRING -> put(columnName, cursor.getString(columnIndex))
                    Cursor.FIELD_TYPE_BLOB -> put(columnName, cursor.getBlob(columnIndex))
                }
            }
        }
    }

    private fun copyQueryRows(
        sourceQuery: String,
        sourceArgs: Array<String>? = null,
        targetDatabase: SQLiteDatabase,
        targetTable: String
    ): Int {
        var rowCount = 0
        database.rawQuery(sourceQuery, sourceArgs).use { cursor ->
            while (cursor.moveToNext()) {
                targetDatabase.insert(targetTable, null, cursorToContentValues(cursor))
                rowCount++
            }
        }
        return rowCount
    }

    private fun cursorToLoggedMessage(cursor: Cursor): LoggedMessage? {
        return LoggedMessage(
            messageId = cursor.getLongOrNull("message_id") ?: return null,
            conversationId = cursor.getStringOrNull("conversation_id") ?: return null,
            userId = cursor.getStringOrNull("user_id") ?: return null,
            username = cursor.getStringOrNull("username") ?: return null,
            sendTimestamp = cursor.getLongOrNull("send_timestamp") ?: return null,
            addedTimestamp = cursor.getLongOrNull("added_timestamp") ?: return null,
            groupTitle = cursor.getStringOrNull("group_title"),
            messageData = cursor.getBlobOrNull("message_data") ?: return null
        )
    }

    fun forEachConversationMessage(
        conversationId: String,
        userIds: Collection<String> = emptyList(),
        orderAscending: Boolean = true,
        block: (LoggedMessage) -> Unit
    ): Int {
        val normalizedConversationId = conversationId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Conversation ID cannot be empty")
        val normalizedUserIds = userIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()

        val whereClause = buildString {
            append("conversation_id = ?")
            if (normalizedUserIds.isNotEmpty()) {
                append(" AND user_id IN (${normalizedUserIds.joinToString(",") { "?" }})")
            }
        }
        val whereArgs = mutableListOf(normalizedConversationId).apply {
            addAll(normalizedUserIds)
        }.toTypedArray()

        var total = 0
        database.rawQuery(
            "SELECT * FROM messages WHERE $whereClause ORDER BY send_timestamp ${if (orderAscending) "ASC" else "DESC"}",
            whereArgs
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursorToLoggedMessage(cursor)?.let { loggedMessage ->
                    block(loggedMessage)
                    total++
                }
            }
        }
        return total
    }

    override fun getChatEdits(conversationId: String?, messageId: Long): List<LoggedChatEdit> {
        if (conversationId.isNullOrEmpty()) return emptyList()
        val edits = mutableListOf<LoggedChatEdit>()
        database.rawQuery(
            "SELECT added_timestamp, message_text FROM chat_edits WHERE conversation_id = ? AND message_id = ? ORDER BY added_timestamp ASC",
            arrayOf(conversationId, messageId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                edits.add(LoggedChatEdit().apply {
                    timestamp = cursor.getLongOrNull("added_timestamp") ?: return@apply
                    message = cursor.getStringOrNull("message_text")
                }.takeIf { it.timestamp > 0L } ?: continue)
            }
        }

        if (edits.isNotEmpty()) {
            // append original message
            database.rawQuery("SELECT added_timestamp, message_data FROM messages WHERE conversation_id = ? AND message_id = ?", arrayOf(conversationId, messageId.toString())).use { cursor ->
                if (!cursor.moveToFirst()) return@use

                val originalMessage = cursor.getBlobOrNull("message_data") ?: return@use
                val addedTimestamp = cursor.getLongOrNull("added_timestamp") ?: return@use

                val messageObject = gson.fromJson(
                    originalMessage.toString(Charsets.UTF_8),
                    JsonObject::class.java
                )

                val messageTextContent =
                    messageObject.getAsJsonObject("mMessageContent")?.getAsJsonArray("mContent")
                        ?.map { it.asByte }?.toByteArray()?.let {
                            ProtoReader(it).getString(2, 1)
                        } ?: return@use

                if (edits.firstOrNull()?.message != messageTextContent) {
                    edits.add(0, LoggedChatEdit().apply {
                        timestamp = addedTimestamp
                        message = messageTextContent
                    })
                }
            }
        }

        return edits
    }

    fun fetchMessages(
        conversationId: String,
        fromTimestamp: Long,
        limit: Int,
        reverseOrder: Boolean = true,
        filter: ((LoggedMessage) -> Boolean)? = null
    ): List<LoggedMessage> {
        val messages = mutableListOf<LoggedMessage>()
        database.rawQuery(
            "SELECT * FROM messages WHERE conversation_id = ? AND send_timestamp ${if (reverseOrder) "<" else ">"} ? ORDER BY send_timestamp ${if (reverseOrder) "DESC" else "ASC"}",
            arrayOf(conversationId, fromTimestamp.toString())
        ).use {
            while (it.moveToNext() && messages.size < limit) {
                val message = cursorToLoggedMessage(it) ?: continue
                if (filter != null && !filter(message)) continue
                messages.add(message)
            }
        }
        return messages
    }
}
