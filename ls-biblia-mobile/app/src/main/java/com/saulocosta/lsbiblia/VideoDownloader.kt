package com.saulocosta.lsbiblia

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloader {
    data class Progress(
        val receivedBytes: Long,
        val totalBytes: Long,
    ) {
        val percent: Int
            get() = if (totalBytes > 0) ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    class DownloadCancelledException : Exception("Download cancelado.")

    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    fun download(
        context: Context,
        bookNumber: Int,
        chapter: Chapter,
        video: VideoFile,
        onProgress: (Progress) -> Unit,
    ): File {
        cancelled = false
        val directory = File(context.filesDir, "videos")
        if (!directory.exists() && !directory.mkdirs()) error("Não foi possível criar a pasta de vídeos.")

        val name = "nwt_${bookNumber.toString().padStart(2, '0')}_${chapter.track.toString().padStart(3, '0')}_LSB_${video.quality}.mp4"
        val target = File(directory, name)
        val partial = File(directory, "$name.part")
        if (target.isFile && target.length() == video.fileSize) {
            onProgress(Progress(target.length(), target.length()))
            return target
        }

        partial.delete()
        val activeConnection = (URL(video.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LS-Biblia-Mobile/0.1")
        }
        connection = activeConnection
        try {
            val status = activeConnection.responseCode
            if (status !in 200..299) error("Falha ao baixar o vídeo (HTTP $status).")
            val total = activeConnection.contentLengthLong.takeIf { it > 0 } ?: video.fileSize
            var received = 0L
            var lastUpdate = 0L
            activeConnection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw DownloadCancelledException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 120 || received == total) {
                            lastUpdate = now
                            onProgress(Progress(received, total))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (cancelled) throw DownloadCancelledException()
            if (video.fileSize > 0 && partial.length() != video.fileSize) {
                error("O download terminou incompleto. Tente novamente.")
            }
            if (target.exists() && !target.delete()) error("Não foi possível substituir o vídeo antigo.")
            if (!partial.renameTo(target)) error("Não foi possível finalizar o arquivo baixado.")
            onProgress(Progress(target.length(), target.length()))
            return target
        } catch (error: Exception) {
            partial.delete()
            if (cancelled && error !is DownloadCancelledException) throw DownloadCancelledException()
            throw error
        } finally {
            connection = null
            activeConnection.disconnect()
        }
    }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
}
