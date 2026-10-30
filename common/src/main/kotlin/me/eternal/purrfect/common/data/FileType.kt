package me.eternal.purrfect.common.data

import java.io.File
import java.io.InputStream

enum class FileType(
    val fileExtension: String? = null,
    val mimeType: String,
    val isVideo: Boolean = false,
    val isImage: Boolean = false,
    val isAudio: Boolean = false
) {
    GIF("gif", "image/gif", false, false, false),
    PNG("png", "image/png", false, true, false),
    MP4("mp4", "video/mp4", true, false, false),
    MKV("mkv", "video/mkv", true, false, false),
    AVI("avi", "video/avi", true, false, false),
    MP3("mp3", "audio/mp3",false, false, true),
    OPUS("opus", "audio/opus", false, false, true),
    AAC("aac", "audio/aac", false, false, true),
    JPG("jpg", "image/jpg",false, true, false),
    ZIP("zip", "application/zip", false, false, false),
    WEBP("webp", "image/webp", false, true, false),
    HEIC("heic", "image/heic", false, true, false),
    HEIF("heif", "image/heif", false, true, false),
    MPD("mpd", "text/xml", false, false, false),
    UNKNOWN("dat", "application/octet-stream", false, false, false);

    companion object {
        private val fileSignatures = mapOf(
            "52494646" to WEBP,
            "504b0304" to ZIP,
            "89504e47" to PNG,
            "00000014" to MP4,
            "00000018" to MP4,
            "0000001c" to MP4,
            "00000020" to MP4,
            "00000024" to MP4,
            "00000028" to MP4,
            "494433" to MP3,
            "4f676753" to OPUS,
            "fff15" to AAC,
            "ffd8ff" to JPG,
            "47494638" to GIF,
            "1a45dfa3" to MKV,
        )

        fun fromString(string: String?): FileType {
            return entries.firstOrNull { it.fileExtension.equals(string, ignoreCase = true) } ?: UNKNOWN
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val result = StringBuilder()
            for (b in bytes) {
                result.append(String.format("%02x", b))
            }
            return result.toString()
        }

        private fun looksLikeIsoBmffVideo(array: ByteArray): Boolean {
            val hex = bytesToHex(array)
            // Search for 'ftyp' (66747970 in hex) within the header
            val ftypIndex = hex.indexOf("66747970")
            if (ftypIndex == -1 || ftypIndex % 2 != 0) return false

            val byteOffset = ftypIndex / 2
            if (array.size < byteOffset + 8) return false

            val majorBrand = String(array, byteOffset + 4, 4, Charsets.US_ASCII).trim('\u0000').lowercase()
            
            // Explicitly exclude known IMAGE-only brands to prevent false positives (HEIC/HEIF)
            val imageBrands = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
            if (majorBrand in imageBrands) return false

            return majorBrand in setOf(
                "mp41", "mp42", "isom", "iso2", "iso3", "iso4", "iso5", "iso6",
                "avc1", "dash", "cmfc", "msnv", "3gp4", "3gp5", "3gp6", "3g2a", "3g2b",
                "mp4v", "mp4a", "m4v ", "m4a ", "f4v ", "f4a "
            ) || majorBrand.isNotEmpty()
        }

        fun fromFile(file: File): FileType {
            file.inputStream().use { inputStream ->
                return fromInputStream(inputStream)
            }
        }

        fun fromByteArray(array: ByteArray): FileType {
            val headerBytes = ByteArray(16)
            val bytesToCopy = minOf(array.size, 16)
            System.arraycopy(array, 0, headerBytes, 0, bytesToCopy)
            val hex = bytesToHex(headerBytes)
            
            // 1. Check strict signatures
            fileSignatures.entries.firstOrNull { hex.startsWith(it.key) }?.value?.let { return it }

            // Check dynamic signatures for raw AAC (ADTS) / MP3
            if (hex.length >= 4) {
                val firstFour = hex.substring(0, 4)
                if (firstFour in setOf("fff0", "fff1", "fff8", "fff9")) {
                    return AAC
                }
                if (firstFour in setOf("fffa", "fffb", "fff2", "fff3") || hex.startsWith("ffe")) {
                    return MP3
                }
            }

            // 2. Check ISO BMFF container type
            if (looksLikeIsoBmffVideo(headerBytes)) return MP4
            
            // Re-check for HEIC/HEIF which use the same ftyp box
            val ftypIndex = hex.indexOf("66747970")
            if (ftypIndex != -1 && ftypIndex % 2 == 0) {
                val byteOffset = ftypIndex / 2
                if (headerBytes.size >= byteOffset + 8) {
                    val majorBrand = String(headerBytes, byteOffset + 4, 4, Charsets.US_ASCII).trim('\u0000').lowercase()
                    if (majorBrand in setOf("heic", "heix")) return HEIC
                    if (majorBrand in setOf("mif1", "msf1")) return HEIF
                }
            }

            return UNKNOWN
        }

        fun fromInputStream(inputStream: InputStream): FileType {
            val buffer = ByteArray(16)
            var offset = 0
            while (offset < buffer.size) {
                val read = inputStream.read(buffer, offset, buffer.size - offset)
                if (read == -1) break
                offset += read
            }
            return fromByteArray(buffer)
        }
    }
}
