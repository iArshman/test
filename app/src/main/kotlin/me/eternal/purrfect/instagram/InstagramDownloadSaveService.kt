package me.eternal.purrfect.instagram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale

class InstagramDownloadSaveService : Service() {
    private lateinit var notificationManager: NotificationManager
    private var lastNotificationMs = 0L
    private var lastPercent = -1

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, progressNotification("Starting...", 0, 0, true))
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val url = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_URL_EXTRA)
        val audioUrl = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_AUDIO_URL_EXTRA)
        val filename = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_FILENAME_EXTRA)
        val mimeType = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_MIME_TYPE_EXTRA) ?: "application/octet-stream"
        val username = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_USERNAME_EXTRA)
        val requestedTreeUri = intent.getStringExtra(Constants.INSTAGRAM_DOWNLOAD_TREE_URI_EXTRA)
        if (url.isNullOrBlank() || filename.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        runCatching { SharedContextHolder.remote(this).mirrorInstagramFeaturePrefs() }
        val prefs = getSharedPreferences(RemoteSideContext.INSTAGRAM_FEATURE_PREFS, Context.MODE_PRIVATE)
        val treeUri = requestedTreeUri
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString("downloaderCustomUri", "").orEmpty()
                .ifBlank { prefs.getString("downloaderCustomPath", "").orEmpty().takeIf { it.startsWith("content://") }.orEmpty() }
        val usernameFolder = prefs.getBoolean("downloaderUsernameFolder", false)
        if (treeUri.isBlank()) {
            toast("No Instagram download folder selected")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread({
            try {
                val savedUri = if (audioUrl.isNullOrBlank()) {
                    downloadAndSave(url, filename, mimeType, treeUri, username, usernameFolder)
                } else {
                    downloadMergeAndSave(url, audioUrl, filename, mimeType, treeUri, username, usernameFolder)
                }
                doneNotification(startId, "Saved: $filename", mimeType, savedUri)
                toast("Saved: $filename")
            } catch (throwable: Throwable) {
                doneNotification(startId, "Download failed: ${throwable.message}", null, null)
                toast("Download failed: ${throwable.message}")
            } finally {
                stopSelf(startId)
            }
        }, "PurrfectInstaDownload-$startId").start()

        return START_NOT_STICKY
    }

    private fun downloadAndSave(
        url: String,
        filename: String,
        mimeType: String,
        treeUri: String,
        username: String?,
        usernameFolder: Boolean
    ): Uri {
        val temp = File.createTempFile("purrfect_insta_", extensionForMime(mimeType), cacheDir)
        try {
            pushProgress("Downloading...", 0, 100, false)
            downloadToFile(url, temp) { done, total ->
                if (total > 0L) maybeProgress("Downloading...", (done * 95L / total).toInt(), 100, false)
                else maybeProgress("Downloading...", 0, 0, true)
            }
            pushProgress("Saving...", 97, 100, false)
            return writeOutput(temp, filename, mimeType, treeUri, username, usernameFolder)
        } finally {
            temp.delete()
        }
    }

    private fun downloadMergeAndSave(
        videoUrl: String,
        audioUrl: String,
        filename: String,
        mimeType: String,
        treeUri: String,
        username: String?,
        usernameFolder: Boolean
    ): Uri {
        val stamp = System.currentTimeMillis()
        val video = File(cacheDir, "purrfect_insta_video_$stamp.mp4")
        val audio = File(cacheDir, "purrfect_insta_audio_$stamp.m4a")
        val merged = File(cacheDir, "purrfect_insta_merged_$stamp.mp4")
        try {
            pushProgress("Downloading video...", 0, 100, false)
            downloadToFile(videoUrl, video) { done, total ->
                if (total > 0L) maybeProgress("Downloading video...", (done * 60L / total).toInt(), 100, false)
            }
            pushProgress("Downloading audio...", 60, 100, false)
            downloadToFile(audioUrl, audio) { done, total ->
                if (total > 0L) maybeProgress("Downloading audio...", 60 + (done * 20L / total).toInt(), 100, false)
            }
            pushProgress("Merging...", 80, 100, true)
            mergeVideoAudio(video.absolutePath, audio.absolutePath, merged.absolutePath)
            pushProgress("Saving...", 97, 100, false)
            return writeOutput(merged, filename, mimeType, treeUri, username, usernameFolder)
        } finally {
            video.delete()
            audio.delete()
            merged.delete()
        }
    }

    private fun writeOutput(
        source: File,
        filename: String,
        mimeType: String,
        treeUri: String,
        username: String?,
        usernameFolder: Boolean
    ): Uri {
        return runCatching {
            writeViaSaf(source, filename, mimeType, treeUri, username, usernameFolder)
        }.getOrElse {
            writeDefaultDownload(source, filename, mimeType, username, usernameFolder)
        }
    }

    private fun writeViaSaf(
        source: File,
        filename: String,
        mimeType: String,
        treeUri: String,
        username: String?,
        usernameFolder: Boolean
    ): Uri {
        var directory = DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            ?: error("SAF folder unavailable")
        if (!directory.canWrite()) error("SAF folder is not writable")
        if (usernameFolder && !username.isNullOrBlank()) {
            directory = directory.findFile(username)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(username)
                ?: error("Cannot create username folder")
        }
        val document = directory.createFile(mimeType, filename) ?: error("Cannot create output file")
        FileInputStream(source).use { input ->
            contentResolver.openOutputStream(document.uri)?.use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            } ?: error("Cannot open output stream")
        }
        return document.uri
    }

    private fun writeDefaultDownload(
        source: File,
        filename: String,
        mimeType: String,
        username: String?,
        usernameFolder: Boolean
    ): Uri {
        val safeUsername = sanitizePathSegment(username)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, buildDefaultRelativePath(safeUsername, usernameFolder))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                } ?: error("MediaStore output failed")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return uri
            } catch (throwable: Throwable) {
                contentResolver.delete(uri, null, null)
                throw throwable
            }
        }

        var directory = File(Environment.getExternalStorageDirectory(), "PurrfectInsta")
        if (usernameFolder && safeUsername.isNotBlank()) directory = File(directory, safeUsername)
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create dir: ${directory.absolutePath}")
        val output = File(directory, filename)
        FileInputStream(source).use { input ->
            FileOutputStream(output).use { fileOutput -> input.copyTo(fileOutput, DEFAULT_BUFFER_SIZE) }
        }
        return Uri.fromFile(output)
    }

    private fun buildDefaultRelativePath(username: String, usernameFolder: Boolean): String {
        var path = "Download/PurrfectInsta"
        if (usernameFolder && username.isNotBlank()) path += "/$username"
        return path
    }

    private fun sanitizePathSegment(value: String?): String {
        return value?.trim()
            ?.removePrefix("@")
            ?.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            ?.trim('_')
            ?.take(80)
            .orEmpty()
    }

    private fun downloadToFile(url: String, destination: File, progress: (Long, Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.connect()
        val total = connection.contentLengthLong
        try {
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    copyWithProgress(input, output, total, progress)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyWithProgress(input: InputStream, output: FileOutputStream, total: Long, progress: (Long, Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloaded = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            downloaded += read
            progress(downloaded, total)
        }
    }

    private fun mergeVideoAudio(videoPath: String, audioPath: String, outputPath: String) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            videoExtractor.setDataSource(videoPath)
            audioExtractor.setDataSource(audioPath)
            val videoTrack = selectTrack(videoExtractor, "video/")
            val audioTrack = selectTrack(audioExtractor, "audio/")
            if (videoTrack < 0 || audioTrack < 0) error("Missing video or audio track")
            val outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            copyTrack(videoExtractor, muxer, outVideoTrack, buffer)
            copyTrack(audioExtractor, muxer, outAudioTrack, buffer)
            muxer.stop()
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            muxer.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(prefix) == true) {
                extractor.selectTrack(i)
                return i
            }
        }
        return -1
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int, buffer: ByteBuffer) {
        val info = MediaCodec.BufferInfo()
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun pushProgress(text: String, progress: Int, max: Int, indeterminate: Boolean) {
        lastPercent = progress
        lastNotificationMs = System.currentTimeMillis()
        notificationManager.notify(NOTIFICATION_ID, progressNotification(text, progress, max, indeterminate))
    }

    private fun maybeProgress(text: String, progress: Int, max: Int, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(progress - lastPercent) < 2 && now - lastNotificationMs < 250L) return
        pushProgress(text, progress, max, indeterminate)
    }

    private fun progressNotification(text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("PurrfectInsta")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .build()
    }

    private fun doneNotification(id: Int, text: String, mimeType: String?, fileUri: Uri?) {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        if (mimeType != null && fileUri != null) {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            builder.setContentIntent(PendingIntent.getActivity(this, DONE_NOTIFICATION_BASE + id, viewIntent, flags))
        }
        notificationManager.notify(
            DONE_NOTIFICATION_BASE + id,
            builder
                .setContentTitle("PurrfectInsta")
                .setContentText(text)
                .setSmallIcon(if (fileUri != null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PurrfectInsta Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
        )
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "purrfect_insta_downloads"
        private const val NOTIFICATION_ID = 0x5049444C
        private const val DONE_NOTIFICATION_BASE = 0x50494450
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

        private fun extensionForMime(mimeType: String): String {
            val lower = mimeType.lowercase(Locale.US)
            return when {
                "video" in lower -> ".mp4"
                "audio" in lower -> ".m4a"
                "png" in lower -> ".png"
                "webp" in lower -> ".webp"
                "image" in lower -> ".jpg"
                else -> ".bin"
            }
        }
    }
}
