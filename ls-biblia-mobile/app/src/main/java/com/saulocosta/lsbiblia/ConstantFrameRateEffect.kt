package com.saulocosta.lsbiblia

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.roundToLong

/**
 * Fills timestamp gaps created by slow motion before time-based visual effects run.
 *
 * Media3 changes the presentation timestamps when slowing a clip, but intentionally
 * does not create extra frames. Reusing the nearest decoded texture at a stable rate
 * lets the following zoom transformation advance every 1/30 s instead of jumping at
 * the lower effective frame rate of the slowed source.
 */
@UnstableApi
class ConstantFrameRateEffect(private val frameRate: Int = 30) : GlEffect {
    init {
        require(frameRate > 0) { "A taxa de quadros deve ser positiva." }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ConstantFrameRateShaderProgram(FrameTimestampSampler(frameRate))
}

internal class FrameTimestampSampler(frameRate: Int) {
    private val frameIntervalUs = 1_000_000.0 / frameRate
    private var nextOutputTimeUs = Double.NaN

    fun outputTimesFor(inputTimeUs: Long): LongArray {
        if (nextOutputTimeUs.isNaN()) nextOutputTimeUs = inputTimeUs.toDouble()

        val times = mutableListOf<Long>()
        val nearestFrameLimit = inputTimeUs + frameIntervalUs / 2.0
        while (nextOutputTimeUs <= nearestFrameLimit) {
            times += nextOutputTimeUs.roundToLong()
            nextOutputTimeUs += frameIntervalUs
        }
        return times.toLongArray()
    }

    fun reset() {
        nextOutputTimeUs = Double.NaN
    }
}

@UnstableApi
private class ConstantFrameRateShaderProgram(
    private val timestampSampler: FrameTimestampSampler,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var inputTexture: GlTextureInfo? = null
    private var outputTimes = LongArray(0)
    private var outputIndex = 0
    private var endPending = false

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(
        executor: Executor,
        errorListener: GlShaderProgram.ErrorListener,
    ) = Unit

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        check(this.inputTexture == null) { "Um quadro anterior ainda está sendo processado." }
        this.inputTexture = inputTexture
        outputTimes = timestampSampler.outputTimesFor(presentationTimeUs)
        outputIndex = 0

        if (outputTimes.isEmpty()) {
            finishInputFrame()
        } else {
            outputListener.onOutputFrameAvailable(inputTexture, outputTimes[outputIndex])
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        check(outputTexture === inputTexture) { "A textura liberada não corresponde à entrada atual." }
        outputIndex++
        if (outputIndex < outputTimes.size) {
            outputListener.onOutputFrameAvailable(outputTexture, outputTimes[outputIndex])
        } else {
            finishInputFrame()
        }
    }

    override fun signalEndOfCurrentInputStream() {
        if (inputTexture == null) {
            outputListener.onCurrentOutputStreamEnded()
        } else {
            endPending = true
        }
    }

    override fun flush() {
        inputTexture?.let(inputListener::onInputFrameProcessed)
        inputTexture = null
        outputTimes = LongArray(0)
        outputIndex = 0
        endPending = false
        timestampSampler.reset()
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        inputTexture = null
        outputTimes = LongArray(0)
    }

    private fun finishInputFrame() {
        val completedTexture = inputTexture ?: return
        inputTexture = null
        outputTimes = LongArray(0)
        outputIndex = 0
        inputListener.onInputFrameProcessed(completedTexture)
        if (endPending) {
            endPending = false
            outputListener.onCurrentOutputStreamEnded()
        } else {
            inputListener.onReadyToAcceptInputFrame()
        }
    }
}
