package com.saulocosta.lsbiblia

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class CacheKind { VIDEOS, CATALOGS, ALL }

data class CacheEntry(
    val file: File,
    val title: String,
    val detail: String,
    val bytes: Long,
    val kind: CacheKind,
    val stale: Boolean = false,
    val partial: Boolean = false,
)

data class CacheReport(
    val videos: List<CacheEntry>,
    val catalogs: List<CacheEntry>,
) {
    val videoBytes: Long get() = videos.sumOf(CacheEntry::bytes)
    val catalogBytes: Long get() = catalogs.sumOf(CacheEntry::bytes)
    val totalBytes: Long get() = videoBytes + catalogBytes
}

data class CacheRemoval(val removed: Int, val freedBytes: Long, val errors: List<String>)

class CacheManager(private val context: Context) {
    private val videosDirectory = File(context.filesDir, "videos")
    private val catalogsDirectory = File(context.filesDir, "catalogs")

    fun report(): CacheReport = CacheReport(
        videos = scanVideos(),
        catalogs = scanCatalogs(),
    )

    fun remove(entry: CacheEntry): CacheRemoval = removeFiles(listOf(entry.file))

    fun clear(kind: CacheKind): CacheRemoval {
        val report = report()
        val files = buildList {
            if (kind != CacheKind.CATALOGS) addAll(report.videos.map(CacheEntry::file))
            if (kind != CacheKind.VIDEOS) addAll(report.catalogs.map(CacheEntry::file))
        }
        return removeFiles(files)
    }

    private fun scanVideos(): List<CacheEntry> {
        val pattern = Regex("nwt_(\\d{2})_(\\d{3})_LSB_(\\d{3,4}p)\\.mp4(\\.part)?")
        return videosDirectory.listFiles()?.filter(File::isFile)?.map { file ->
            val match = pattern.matchEntire(file.name)
            val partial = file.name.endsWith(".part")
            val title = if (match != null) {
                val book = match.groupValues[1].toIntOrNull()?.let { BOOK_NAMES.getOrNull(it - 1) }
                    ?: "Livro ${match.groupValues[1]}"
                "$book ${match.groupValues[2].toInt()} • ${match.groupValues[3]}"
            } else {
                file.name
            }
            CacheEntry(
                file = file,
                title = title,
                detail = if (partial) "Download incompleto" else "Vídeo disponível no editor",
                bytes = file.length(),
                kind = CacheKind.VIDEOS,
                partial = partial,
            )
        }?.sortedWith(compareBy(CacheEntry::partial).thenBy(CacheEntry::title)) ?: emptyList()
    }

    private fun scanCatalogs(): List<CacheEntry> {
        val now = System.currentTimeMillis()
        return catalogsDirectory.listFiles()?.filter(File::isFile)?.map { file ->
            val bookNumber = Regex("book-(\\d+)-LSB\\.json").matchEntire(file.name)
                ?.groupValues?.get(1)?.toIntOrNull()
            val parsedName = runCatching { JSONObject(file.readText()).optString("pubName") }.getOrNull()
            val title = parsedName?.takeIf(String::isNotBlank)
                ?: bookNumber?.let { BOOK_NAMES.getOrNull(it - 1) }
                ?: file.name
            val stale = now - file.lastModified() > CATALOG_TTL_MS
            CacheEntry(
                file = file,
                title = title,
                detail = if (stale) "Catálogo expirado" else "Catálogo válido",
                bytes = file.length(),
                kind = CacheKind.CATALOGS,
                stale = stale,
            )
        }?.sortedBy(CacheEntry::title) ?: emptyList()
    }

    private fun removeFiles(files: List<File>): CacheRemoval {
        var removed = 0
        var freed = 0L
        val errors = mutableListOf<String>()
        files.forEach { file ->
            if (!isInsideKnownDirectory(file)) {
                errors += "Arquivo fora do cache do app: ${file.name}"
                return@forEach
            }
            val bytes = file.length()
            if (!file.exists() || file.delete()) {
                removed++
                freed += bytes
            } else {
                errors += "Não foi possível apagar ${file.name}."
            }
        }
        return CacheRemoval(removed, freed, errors)
    }

    private fun isInsideKnownDirectory(file: File): Boolean {
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        val videos = runCatching { videosDirectory.canonicalFile }.getOrNull()
        val catalogs = runCatching { catalogsDirectory.canonicalFile }.getOrNull()
        return parent == videos || parent == catalogs
    }

    companion object {
        const val CATALOG_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private val BOOK_NAMES = listOf(
            "Gênesis", "Êxodo", "Levítico", "Números", "Deuteronômio", "Josué", "Juízes", "Rute",
            "1 Samuel", "2 Samuel", "1 Reis", "2 Reis", "1 Crônicas", "2 Crônicas", "Esdras", "Neemias",
            "Ester", "Jó", "Salmos", "Provérbios", "Eclesiastes", "Cântico de Salomão", "Isaías", "Jeremias",
            "Lamentações", "Ezequiel", "Daniel", "Oseias", "Joel", "Amós", "Obadias", "Jonas", "Miqueias",
            "Naum", "Habacuque", "Sofonias", "Ageu", "Zacarias", "Malaquias", "Mateus", "Marcos", "Lucas",
            "João", "Atos", "Romanos", "1 Coríntios", "2 Coríntios", "Gálatas", "Efésios", "Filipenses",
            "Colossenses", "1 Tessalonicenses", "2 Tessalonicenses", "1 Timóteo", "2 Timóteo", "Tito", "Filêmon",
            "Hebreus", "Tiago", "1 Pedro", "2 Pedro", "1 João", "2 João", "3 João", "Judas", "Apocalipse",
        )
    }
}
