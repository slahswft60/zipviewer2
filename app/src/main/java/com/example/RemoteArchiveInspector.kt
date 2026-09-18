package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

data class RemoteArchiveAnalysisResult(
    val entries: List<ZipEntryItem>,
    val totalArchiveSize: Long,
    val isRangeSupported: Boolean,
    val archiveType: String,
    val bytesDownloadedForAnalysis: Long
)

object RemoteArchiveInspector {

    private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) ZipInspector/1.0"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 25000

    /**
     * Inspects a remote archive (ZIP, TAR, TAR.MD5) without downloading the entire file.
     * Uses HTTP Range requests to fetch only metadata structures.
     */
    suspend fun inspectRemoteArchive(urlStr: String): RemoteArchiveAnalysisResult = withContext(Dispatchers.IO) {
        val trimmedUrl = urlStr.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("يرجى إدخال رابط يبدأ بـ http:// أو https://")
        }

        val url = URL(trimmedUrl)
        val cleanPath = url.path.lowercase()
        val isTar = cleanPath.endsWith(".tar") || cleanPath.endsWith(".tar.md5")

        // Step 1: Probe server with Range: bytes=0-0 to check size and Range support
        var totalLength = -1L
        var isRangeSupported = false
        var probeConn: HttpURLConnection? = null

        try {
            probeConn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
            }

            val responseCode = probeConn.responseCode
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) { // 206 Partial Content
                isRangeSupported = true
                val contentRange = probeConn.getHeaderField("Content-Range")
                if (contentRange != null && contentRange.contains("/")) {
                    val totalStr = contentRange.substringAfterLast("/").trim()
                    totalLength = totalStr.toLongOrNull() ?: -1L
                }
            } else if (responseCode == HttpURLConnection.HTTP_OK) { // 200 OK
                isRangeSupported = false
                totalLength = probeConn.contentLengthLong
            } else {
                throw IllegalStateException("فشل الاتصال بالخادم، رمز الاستجابة: $responseCode")
            }
        } finally {
            try {
                probeConn?.inputStream?.close()
                probeConn?.disconnect()
            } catch (_: Exception) {}
        }

        if (isTar) {
            inspectTarArchive(trimmedUrl, totalLength, isRangeSupported)
        } else {
            if (isRangeSupported && totalLength > 22) {
                try {
                    inspectZipViaRangeRequests(trimmedUrl, totalLength)
                } catch (e: Exception) {
                    // Fallback to stream probing if EOCD range parse encounters unexpected format
                    inspectZipViaStreaming(trimmedUrl, totalLength)
                }
            } else {
                inspectZipViaStreaming(trimmedUrl, totalLength)
            }
        }
    }

    /**
     * Parses a ZIP file by reading only the End of Central Directory (EOCD)
     * and the Central Directory table using HTTP Range requests.
     */
    private fun inspectZipViaRangeRequests(urlStr: String, totalLength: Long): RemoteArchiveAnalysisResult {
        // Read up to last 65536 bytes (max standard EOCD comment length + 22 bytes header)
        val rangeSize = minOf(totalLength, 65536L).toInt()
        val startByte = totalLength - rangeSize
        val endByte = totalLength - 1

        val eocdBytes = fetchByteRange(urlStr, startByte, endByte)
        var bytesDownloaded = eocdBytes.size.toLong()

        // Scan backwards for EOCD signature: 0x06054b50 (PK\005\006)
        var eocdOffsetInBuf = -1
        for (i in (eocdBytes.size - 22) downTo 0) {
            if (eocdBytes[i] == 0x50.toByte() &&
                eocdBytes[i + 1] == 0x4B.toByte() &&
                eocdBytes[i + 2] == 0x05.toByte() &&
                eocdBytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffsetInBuf = i
                break
            }
        }

        if (eocdOffsetInBuf == -1) {
            throw IllegalStateException("لم يتم العثور على سجل نهاية الدليل المركزي (EOCD) في ملف ZIP")
        }

        val eocdBuf = ByteBuffer.wrap(eocdBytes, eocdOffsetInBuf, eocdBytes.size - eocdOffsetInBuf).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }

        eocdBuf.position(eocdBuf.position() + 10)
        val totalEntriesInDisk = eocdBuf.short.toInt() and 0xFFFF
        val cdSizeBytes = eocdBuf.int.toLong() and 0xFFFFFFFFL
        val cdOffset = eocdBuf.int.toLong() and 0xFFFFFFFFL

        if (cdSizeBytes <= 0 || cdOffset < 0 || cdOffset + cdSizeBytes > totalLength) {
            throw IllegalStateException("قيم الدليل المركزي غير صالحة")
        }

        // Fetch Central Directory range
        val cdBytes = fetchByteRange(urlStr, cdOffset, cdOffset + cdSizeBytes - 1)
        bytesDownloaded += cdBytes.size

        val entries = mutableListOf<ZipEntryItem>()
        val cdBuffer = ByteBuffer.wrap(cdBytes).apply { order(ByteOrder.LITTLE_ENDIAN) }

        while (cdBuffer.remaining() >= 46) {
            val signature = cdBuffer.int
            if (signature != 0x02014b50) { // Central Directory Record magic: PK\001\002
                break
            }

            cdBuffer.position(cdBuffer.position() + 6) // Skip version made by & version needed & flags
            val compressionMethod = cdBuffer.short.toInt() and 0xFFFF
            cdBuffer.position(cdBuffer.position() + 4) // Skip time and date
            val crc = cdBuffer.int.toLong() and 0xFFFFFFFFL
            val compressedSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
            val uncompressedSize = cdBuffer.int.toLong() and 0xFFFFFFFFL
            val fileNameLength = cdBuffer.short.toInt() and 0xFFFF
            val extraFieldLength = cdBuffer.short.toInt() and 0xFFFF
            val fileCommentLength = cdBuffer.short.toInt() and 0xFFFF
            cdBuffer.position(cdBuffer.position() + 8) // Skip disk start, internal/external attr
            val localHeaderOffset = cdBuffer.int.toLong() and 0xFFFFFFFFL

            if (cdBuffer.remaining() < fileNameLength) break
            val fileNameBytes = ByteArray(fileNameLength)
            cdBuffer.get(fileNameBytes)
            val fileName = String(fileNameBytes, Charsets.UTF_8)

            // Skip extra field and file comment
            if (cdBuffer.remaining() < extraFieldLength + fileCommentLength) break
            cdBuffer.position(cdBuffer.position() + extraFieldLength + fileCommentLength)

            val isDir = fileName.endsWith("/") || (uncompressedSize == 0L && fileName.endsWith("\\"))
            entries.add(
                ZipEntryItem(
                    fileName,
                    uncompressedSize,
                    compressedSize,
                    isDir,
                    urlStr,
                    localHeaderOffset,
                    compressionMethod,
                    "ZIP"
                )
            )
        }

        return RemoteArchiveAnalysisResult(
            entries = entries,
            totalArchiveSize = totalLength,
            isRangeSupported = true,
            archiveType = "ZIP",
            bytesDownloadedForAnalysis = bytesDownloaded
        )
    }

    /**
     * Fallback for servers without Range support: streams headers using ZipInputStream.
     */
    private fun inspectZipViaStreaming(urlStr: String, totalLength: Long): RemoteArchiveAnalysisResult {
        val entries = mutableListOf<ZipEntryItem>()
        var bytesReadCount = 0L

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            val countedStream = object : InputStream() {
                private val base = conn.inputStream
                override fun read(): Int {
                    val b = base.read()
                    if (b != -1) bytesReadCount++
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val r = base.read(b, off, len)
                    if (r > 0) bytesReadCount += r
                    return r
                }
                override fun close() = base.close()
            }

            ZipInputStream(BufferedInputStream(countedStream, 65536)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val item = ZipEntryItem(
                        entry.name,
                        if (entry.size >= 0) entry.size else 0L,
                        if (entry.compressedSize >= 0) entry.compressedSize else 0L,
                        entry.isDirectory,
                        urlStr,
                        0L,
                        entry.method,
                        "ZIP"
                    )
                    entries.add(item)
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }

        return RemoteArchiveAnalysisResult(
            entries = entries,
            totalArchiveSize = if (totalLength > 0) totalLength else bytesReadCount,
            isRangeSupported = false,
            archiveType = "ZIP",
            bytesDownloadedForAnalysis = bytesReadCount
        )
    }

    /**
     * Inspects a TAR or TAR.MD5 archive headers sequentially.
     */
    private fun inspectTarArchive(urlStr: String, totalLength: Long, isRangeSupported: Boolean): RemoteArchiveAnalysisResult {
        val entries = mutableListOf<ZipEntryItem>()
        var bytesDownloaded = 0L
        var currentOffset = 0L

        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            val bis = BufferedInputStream(conn.inputStream, 65536)
            val headerBlock = ByteArray(512)

            while (true) {
                var read = 0
                while (read < 512) {
                    val r = bis.read(headerBlock, read, 512 - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < 512) break
                bytesDownloaded += 512

                // If all zeroes, end of archive
                if (headerBlock.all { it == 0.toByte() }) {
                    break
                }

                // File name at 0..99 (null terminated)
                var nameLen = 0
                while (nameLen < 100 && headerBlock[nameLen] != 0.toByte()) {
                    nameLen++
                }
                if (nameLen == 0) break
                val fileName = String(headerBlock, 0, nameLen, Charsets.UTF_8)

                // Size at 124..135 (octal string)
                var sizeLen = 0
                while (sizeLen < 12 && headerBlock[124 + sizeLen] != 0.toByte() && headerBlock[124 + sizeLen] != ' '.code.toByte()) {
                    sizeLen++
                }
                val octalSizeStr = String(headerBlock, 124, sizeLen, Charsets.US_ASCII).trim()
                val fileSize = try {
                    if (octalSizeStr.isNotEmpty()) java.lang.Long.parseLong(octalSizeStr, 8) else 0L
                } catch (_: Exception) {
                    0L
                }

                val typeFlag = headerBlock[156].toInt().toChar()
                val isDir = typeFlag == '5' || fileName.endsWith("/")

                entries.add(
                    ZipEntryItem(
                        fileName,
                        fileSize,
                        fileSize,
                        isDir,
                        urlStr,
                        currentOffset,
                        0,
                        "TAR"
                    )
                )

                // Content takes ((fileSize + 511) / 512) * 512 bytes
                val paddedSize = if (fileSize > 0) ((fileSize + 511) / 512) * 512 else 0L
                currentOffset += 512 + paddedSize

                // Skip the file content blocks
                var toSkip = paddedSize
                while (toSkip > 0) {
                    val skipped = bis.skip(toSkip)
                    if (skipped <= 0) {
                        if (bis.read() == -1) break
                        toSkip--
                    } else {
                        toSkip -= skipped
                    }
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }

        return RemoteArchiveAnalysisResult(
            entries = entries,
            totalArchiveSize = totalLength,
            isRangeSupported = isRangeSupported,
            archiveType = "TAR",
            bytesDownloadedForAnalysis = bytesDownloaded
        )
    }

    /**
     * Downloads and extracts ONLY the specific single file from the remote archive directly to the OutputStream.
     */
    suspend fun downloadSingleEntry(
        item: ZipEntryItem,
        destination: OutputStream,
        onProgress: (bytesWritten: Long, totalExpected: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val urlStr = item.remoteUrl ?: throw IllegalArgumentException("هذا الملف ليس من رابط بعيد")

        if (item.archiveType == "TAR") {
            downloadSingleTarEntry(item, urlStr, destination, onProgress)
        } else {
            downloadSingleZipEntry(item, urlStr, destination, onProgress)
        }
    }

    private fun downloadSingleZipEntry(
        item: ZipEntryItem,
        urlStr: String,
        destination: OutputStream,
        onProgress: (bytesWritten: Long, totalExpected: Long) -> Unit
    ) {
        // Read local file header to determine exact data start
        val lfhOffset = item.localHeaderOffset
        // Read first 30 bytes + safety margin for filename and extra length
        val initialRange = fetchByteRange(urlStr, lfhOffset, lfhOffset + 30 + 1024)
        val lfhBuf = ByteBuffer.wrap(initialRange).apply { order(ByteOrder.LITTLE_ENDIAN) }

        val signature = lfhBuf.int
        if (signature != 0x04034b50) { // Local File Header magic: PK\003\004
            throw IllegalStateException("رأس الملف المحلي غير صالح")
        }

        lfhBuf.position(lfhBuf.position() + 22) // Skip to fileNameLength at offset 26
        val nameLen = lfhBuf.short.toInt() and 0xFFFF
        val extraLen = lfhBuf.short.toInt() and 0xFFFF
        val dataStart = lfhOffset + 30 + nameLen + extraLen
        val compressedSize = item.compressedSize

        val dataEnd = dataStart + compressedSize - 1
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=$dataStart-$dataEnd")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            val inputStream = conn.inputStream
            val streamToRead: InputStream = if (item.compressionMethod == 8) {
                InflaterInputStream(inputStream, Inflater(true))
            } else {
                inputStream
            }

            val buffer = ByteArray(32768)
            var bytesWritten = 0L
            var read: Int
            while (streamToRead.read(buffer).also { read = it } != -1) {
                destination.write(buffer, 0, read)
                bytesWritten += read
                onProgress(bytesWritten, item.size)
            }
            destination.flush()
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun downloadSingleTarEntry(
        item: ZipEntryItem,
        urlStr: String,
        destination: OutputStream,
        onProgress: (bytesWritten: Long, totalExpected: Long) -> Unit
    ) {
        val dataStart = item.localHeaderOffset + 512
        val dataEnd = dataStart + item.size - 1
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=$dataStart-$dataEnd")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            val inputStream = conn.inputStream
            val buffer = ByteArray(32768)
            var bytesWritten = 0L
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                destination.write(buffer, 0, read)
                bytesWritten += read
                onProgress(bytesWritten, item.size)
            }
            destination.flush()
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fetchByteRange(urlStr: String, startByte: Long, endByte: Long): ByteArray {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        return try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
