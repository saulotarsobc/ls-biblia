package com.saulocosta.lsbiblia

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.TreeMap

class JwCatalogClient {
    class CatalogException(
        message: String,
        val unavailable: Boolean = false,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    fun getBook(
        context: Context,
        bookNumber: Int,
        fallbackName: String,
        force: Boolean = false,
    ): BookDetail {
        val cacheDir = File(context.filesDir, "catalogs")
        val cacheFile = File(cacheDir, "book-$bookNumber-LSB.json")
        var raw = if (!force && cacheFile.isFresh()) runCatching { cacheFile.readText() }.getOrNull() else null

        if (raw == null) {
            raw = fetch(bookNumber)
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw CatalogException("Não foi possível criar o cache local.")
            }
            runCatching { cacheFile.writeText(raw) }.getOrElse { error ->
                throw CatalogException("O catálogo chegou, mas não pôde ser salvo.", cause = error)
            }
        }

        return try {
            parse(raw, bookNumber, fallbackName, cacheFile.lastModified())
        } catch (error: CatalogException) {
            throw error
        } catch (error: Exception) {
            if (!force && cacheFile.delete()) return getBook(context, bookNumber, fallbackName, force = true)
            throw CatalogException("A resposta do catálogo não pôde ser interpretada.", cause = error)
        }
    }

    private fun fetch(bookNumber: Int): String {
        val url = URL(
            "$API?pub=nwt&langwritten=LSB&txtCMSLang=LSB&booknum=$bookNumber&output=json",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LS-Biblia-Mobile/0.1")
        }
        return try {
            when (val status = connection.responseCode) {
                404 -> throw CatalogException("Este livro ainda não está disponível em LSB.", unavailable = true)
                !in 200..299 -> throw CatalogException("O catálogo respondeu com o código $status.")
            }
            connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    input.copyTo(output)
                    output.toString(StandardCharsets.UTF_8.name())
                }
            }
        } catch (error: CatalogException) {
            throw error
        } catch (error: Exception) {
            throw CatalogException("Não foi possível acessar o catálogo. Verifique sua conexão.", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String, bookNumber: Int, fallbackName: String, fetchedAt: Long): BookDetail {
        val root = JSONObject(raw)
        val languageFiles = root.optJSONObject("files")?.optJSONObject("LSB")
            ?: throw CatalogException("Este livro ainda não está disponível em LSB.", unavailable = true)

        val byTrack = TreeMap<Int, MutableList<JSONObject>>()
        FORMATS.forEach { format ->
            val items = languageFiles.optJSONArray(format) ?: return@forEach
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                val track = item.optInt("track")
                if (track > 0) byTrack.getOrPut(track) { mutableListOf() }.add(item)
            }
        }
        if (byTrack.isEmpty()) {
            throw CatalogException("Este livro ainda não está disponível em LSB.", unavailable = true)
        }

        val chapters = byTrack.map { (track, group) ->
            val first = group.first()
            val duration = first.optDouble("duration")
            val files = linkedMapOf<String, VideoFile>()
            group.forEach { item ->
                val quality = item.optString("label")
                val file = item.optJSONObject("file")
                if (quality in QUALITIES && quality !in files && file?.optString("url").isNullOrEmpty().not()) {
                    files[quality] = VideoFile(
                        quality = quality,
                        url = file!!.optString("url"),
                        fileSize = item.optLong("filesize"),
                        width = item.optInt("frameWidth"),
                        height = item.optInt("frameHeight"),
                        frameRate = item.optDouble("frameRate"),
                        duration = item.optDouble("duration"),
                        checksum = file.optString("checksum"),
                    )
                }
            }

            val markerSource = group.firstOrNull {
                it.optString("label") == "720p" && it.optJSONObject("markers") != null
            } ?: group.firstOrNull { it.optJSONObject("markers") != null }
            Chapter(
                track = track,
                title = first.optString("title", "Capítulo $track"),
                duration = duration,
                files = files,
                verses = parseVerses(markerSource, duration),
            )
        }

        return BookDetail(
            bookNumber = bookNumber,
            name = root.optString("pubName", fallbackName),
            chapters = chapters,
            fetchedAt = fetchedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    private fun parseVerses(source: JSONObject?, chapterDuration: Double): List<Verse> {
        val markers = source?.optJSONObject("markers")?.optJSONArray("markers") ?: return emptyList()
        val verses = buildList {
            repeat(markers.length()) { index ->
                val marker = markers.optJSONObject(index) ?: return@repeat
                val start = parseTimecode(marker.optString("startTime"))
                add(
                    Verse(
                        number = marker.optInt("verseNumber"),
                        start = start,
                        end = start + parseTimecode(marker.optString("duration")),
                        label = marker.optString("label"),
                    ),
                )
            }
        }.sortedBy { it.number }

        verses.forEachIndexed { index, verse ->
            verse.end = if (index < verses.lastIndex) {
                maxOf(verse.end, verses[index + 1].start)
            } else {
                minOf(chapterDuration, verse.end)
            }
        }
        return verses
    }

    private fun parseTimecode(value: String): Double {
        val parts = value.split(':')
        if (parts.size != 3) return 0.0
        return (parts[0].toDoubleOrNull() ?: 0.0) * 3600 +
            (parts[1].toDoubleOrNull() ?: 0.0) * 60 +
            (parts[2].toDoubleOrNull() ?: 0.0)
    }

    private fun File.isFresh(): Boolean =
        isFile && System.currentTimeMillis() - lastModified() <= CACHE_TTL_MS

    companion object {
        private const val API = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS"
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private val FORMATS = listOf("MP4", "M4V")
        private val QUALITIES = setOf("240p", "360p", "480p", "720p")
    }
}
