package com.saulocosta.lsbiblia

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

@UnstableApi
class VideoExporter(private val context: Context) {
    data class Result(
        val uri: Uri,
        val displayName: String,
        val bytes: Long,
        val durationMs: Long,
    )

    interface Listener {
        fun onProgress(percent: Int)
        fun onCompleted(result: Result)
        fun onError(message: String)
        fun onCancelled()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var temporaryFile: File? = null
    private var listener: Listener? = null
    private var cancelled = false

    fun start(
        sourceFile: File,
        edit: EditState,
        displayName: String,
        listener: Listener,
    ) {
        check(transformer == null) { "Já existe uma exportação em andamento." }
        val atoms = TimelineMath.buildAtoms(edit.ranges, edit.speedRegions)
        require(atoms.isNotEmpty()) { "Não há nenhum trecho para exportar." }
        cancelled = false
        this.listener = listener

        val exportDirectory = File(context.cacheDir, "exports")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            listener.onError("Não foi possível preparar a exportação.")
            return
        }
        val temp = File(exportDirectory, "export-${System.currentTimeMillis()}.mp4")
        if (temp.exists()) temp.delete()
        temporaryFile = temp

        val items = atoms.map { atom -> createEditedItem(sourceFile, atom) }
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItems(items)
            .build()
        val compositionBuilder = Composition.Builder(sequence)
        val compositionEffects = mutableListOf<Effect>()
        if (atoms.any { it.speed < 1f }) {
            compositionEffects += ConstantFrameRateEffect(frameRate = 30)
        }
        if (edit.zoomRegions.isNotEmpty()) {
            val effect = MatrixTransformation { presentationTimeUs ->
                val outputTime = presentationTimeUs / 1_000_000.0
                val editTime = TimelineMath.outputToEdit(outputTime, atoms)
                zoomMatrix(TimelineMath.zoomAt(editTime, edit.zoomRegions))
            }
            compositionEffects += effect
        }
        if (compositionEffects.isNotEmpty()) {
            compositionBuilder.setEffects(Effects(emptyList(), compositionEffects))
        }
        val composition = compositionBuilder.build()
        val activeTransformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        finishToGallery(temp, displayName, exportResult.approximateDurationMs)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        transformer = null
                        temp.delete()
                        this@VideoExporter.listener?.onError(
                            exportException.message ?: "Não foi possível processar o vídeo.",
                        )
                        close()
                    }
                },
            )
            .build()
        transformer = activeTransformer
        activeTransformer.start(composition, temp.absolutePath)
        pollProgress(activeTransformer)
    }

    fun cancel() {
        if (transformer == null && temporaryFile == null) return
        cancelled = true
        transformer?.cancel()
        transformer = null
        temporaryFile?.delete()
        temporaryFile = null
        listener?.onCancelled()
        close()
    }

    private fun createEditedItem(
        sourceFile: File,
        atom: EditAtom,
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(sourceFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((atom.sourceStart * 1_000).toLong())
                    .setEndPositionMs((atom.sourceEnd * 1_000).toLong())
                    .build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setSpeed(ConstantSpeedProvider(atom.speed))
            .build()
    }

    private fun zoomMatrix(value: ZoomValue): Matrix {
        val centerX = value.centerX * 2f - 1f
        val centerY = 1f - value.centerY * 2f
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    value.zoom, 0f, -value.zoom * centerX,
                    0f, value.zoom, -value.zoom * centerY,
                    0f, 0f, 1f,
                ),
            )
        }
    }

    private fun pollProgress(activeTransformer: Transformer) {
        val progressHolder = ProgressHolder()
        mainHandler.post(
            object : Runnable {
                override fun run() {
                    if (transformer !== activeTransformer || cancelled) return
                    val state = activeTransformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        listener?.onProgress(progressHolder.progress.coerceIn(0, 100))
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 350)
                    }
                }
            },
        )
    }

    private fun finishToGallery(temp: File, requestedName: String, durationMs: Long) {
        fileExecutor.execute {
            if (cancelled) return@execute
            try {
                val name = requestedName.substringBeforeLast('.').ifBlank { "LS-Biblia" } + ".mp4"
                val uri = saveToMediaStore(temp, name)
                val result = Result(uri, name, temp.length(), durationMs)
                temp.delete()
                mainHandler.post {
                    if (!cancelled) listener?.onCompleted(result)
                    close()
                }
            } catch (error: Exception) {
                temp.delete()
                mainHandler.post {
                    if (!cancelled) {
                        listener?.onError(error.message ?: "O vídeo foi processado, mas não pôde ser salvo.")
                    }
                    close()
                }
            }
        }
    }

    private fun saveToMediaStore(source: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LS Bíblia")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("O Android não permitiu criar o arquivo na galeria.")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, 256 * 1024) }
            } ?: error("Não foi possível abrir o destino do vídeo.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun close() {
        temporaryFile = null
        transformer = null
        listener = null
        fileExecutor.shutdown()
    }

    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }
}
