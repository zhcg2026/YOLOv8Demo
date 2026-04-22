package com.example.citydetect

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

class VideoRecorder(private val context: android.content.Context) {

    // ====== 目标：触发上报时，输出「前2秒 + 后3秒」MP4(H.264) ======
    // 方案：持续编码 -> 将最近2秒的编码帧放入环形缓冲；触发时从最近关键帧起写入 muxer，
    //      继续写3秒再结束 muxer。这样才能真正拿到“触发前”的视频片段。

    private val targetWidth = 640
    private val targetHeight = 480
    private val targetFps = 15
    private val bufferPreMs = 2000L
    private val bufferPostMs = 3000L
    private val maxMp4Bytes = 5_000_000

    private val encodeIntervalUs = 1_000_000L / targetFps
    private var lastQueuedPtsUs = 0L

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var outputFormat: MediaFormat? = null
    private var lastMp4FailureReason: String = "unknown"

    private data class EncodedSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val isKeyFrame: Boolean,
    )

    // 最近2秒的编码帧缓存（含关键帧），用于“回放前2秒”
    private val encodedBuffer = ArrayDeque<EncodedSample>()

    // ====== 兼容降级：如果编码器不可用，则退回关键帧拼图 ======
    private val frameBuffer = mutableListOf<Bitmap>()
    private val bufferMaxFramesFallback = 60  // 约2秒@30fps（只是兜底）
    private val frameTimestampsFallback = mutableListOf<Long>()
    private var lastFallbackSnapshotMs = 0L

    fun recordFrame(bitmap: Bitmap) {
        val scaled = scaleBitmap(bitmap, targetWidth, targetHeight)

        // 先尝试走“真视频”编码路径
        val ok = tryQueueToEncoder(scaled)
        if (ok) {
            saveFallbackSnapshot(scaled, bitmap)
            // 编码路径里不需要维护 Bitmap 缓存；这里仅释放缩放出的临时图（避免内存涨）
            if (scaled != bitmap) {
                scaled.recycle()
            }
            return
        }

        // 编码不可用 -> 兜底：缓存 Bitmap 做关键帧拼图（与你现有逻辑兼容）
        synchronized(frameBuffer) {
            frameBuffer.add(scaled)
            frameTimestampsFallback.add(System.currentTimeMillis())
            val now = System.currentTimeMillis()
            while (
                frameBuffer.size > bufferMaxFramesFallback ||
                (frameTimestampsFallback.isNotEmpty() && now - frameTimestampsFallback.first() > bufferPreMs)
            ) {
                val oldFrame = frameBuffer.removeAt(0)
                if (oldFrame != scaled && oldFrame != bitmap) {
                    oldFrame.recycle()
                }
                frameTimestampsFallback.removeAt(0)
            }
        }
    }

    private fun scaleBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) {
            return source
        }
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun saveFallbackSnapshot(scaled: Bitmap, original: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastFallbackSnapshotMs < 500L) {
            return
        }
        lastFallbackSnapshotMs = now
        val snapshot = try {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w("VideoRecorder", "关键帧快照复制失败: ${e.message}")
            null
        } ?: return
        synchronized(frameBuffer) {
            frameBuffer.add(snapshot)
            frameTimestampsFallback.add(now)
            while (
                frameBuffer.size > 12 ||
                (frameTimestampsFallback.isNotEmpty() && now - frameTimestampsFallback.first() > bufferPreMs)
            ) {
                val oldFrame = frameBuffer.removeAt(0)
                if (oldFrame != scaled && oldFrame != original) {
                    oldFrame.recycle()
                }
                frameTimestampsFallback.removeAt(0)
            }
        }
    }

    fun captureAndFinish(currentFrame: Bitmap?): String? {
        // 触发时优先输出 MP4；若首次失败，短暂重试一次后再降级，减少偶发性降级
        var mp4 = captureMp4Base64OrNull()
        if (mp4 != null) return mp4
        Log.w("VideoRecorder", "首次MP4失败，原因: $lastMp4FailureReason，300ms后重试一次")
        Thread.sleep(300)
        mp4 = captureMp4Base64OrNull()
        if (mp4 != null) return mp4
        Log.w("VideoRecorder", "二次MP4失败，原因: $lastMp4FailureReason，降级关键帧拼图")
        return captureFallbackKeyframesBase64(currentFrame)
    }

    private fun captureMp4Base64OrNull(): String? {
        val outFile = File(context.cacheDir, "report_${System.currentTimeMillis()}.mp4")
        try {
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            var lastWrittenPtsUs = Long.MIN_VALUE

            val captureStartMs = System.currentTimeMillis()
            val preWindowStartUs = (captureStartMs - bufferPreMs) * 1000L
            val captureEndMs = captureStartMs + bufferPostMs

            // 先尽量 drain 一波，确保 outputFormat 与 buffer 都是新的
            synchronized(lock) {
                ensureEncoderLocked()
                drainEncoderLocked()

                val fmt = outputFormat
                if (fmt == null) {
                    lastMp4FailureReason = "output_format_not_ready"
                    muxer.release()
                    return null
                }
                trackIndex = muxer.addTrack(fmt)
                muxer.start()
                muxerStarted = true

                // 找到“最近2秒窗口内”的起始关键帧，保证播放器可解码
                val buffered = encodedBuffer.toList()
                if (buffered.isEmpty()) {
                    lastMp4FailureReason = "encoded_buffer_empty"
                    muxer.stop()
                    muxer.release()
                    return null
                }

                var startIdx = 0
                // 先找窗口内最早的样本
                while (startIdx < buffered.size && buffered[startIdx].info.presentationTimeUs < preWindowStartUs) {
                    startIdx++
                }
                // 再从 startIdx 往回回溯到最近的关键帧
                var keyIdx = startIdx
                while (keyIdx > 0 && !buffered[keyIdx].isKeyFrame) {
                    keyIdx--
                }
                // 如果窗口内没有关键帧，则从最后一个关键帧开始（至少可播放）
                if (!buffered[keyIdx].isKeyFrame) {
                    val lastKey = buffered.indexOfLast { it.isKeyFrame }
                    if (lastKey < 0) {
                        lastMp4FailureReason = "no_key_frame_in_buffer"
                        muxer.stop()
                        muxer.release()
                        return null
                    }
                    keyIdx = lastKey
                }

                // 写入“前2秒缓冲”（从关键帧开始）
                for (i in keyIdx until buffered.size) {
                    val s = buffered[i]
                    val bb = ByteBuffer.wrap(s.data)
                    muxer.writeSampleData(trackIndex, bb, s.info)
                    lastWrittenPtsUs = maxOf(lastWrittenPtsUs, s.info.presentationTimeUs)
                }
            }

            // 继续录制后3秒：这段不持锁等待，定期进锁 drain 并把新输出写入 muxer
            while (System.currentTimeMillis() < captureEndMs) {
                Thread.sleep(30)
                synchronized(lock) {
                    // 不管是谁 drain 的输出，都会进入 encodedBuffer；这里按 pts 增量写入，避免漏帧/重复
                    drainEncoderLocked()
                    if (muxerStarted && trackIndex >= 0 && encodedBuffer.isNotEmpty()) {
                        for (s in encodedBuffer) {
                            val pts = s.info.presentationTimeUs
                            if (pts > lastWrittenPtsUs) {
                                val bb = ByteBuffer.wrap(s.data)
                                muxer.writeSampleData(trackIndex, bb, s.info)
                                lastWrittenPtsUs = pts
                            }
                        }
                    }
                }
            }

            synchronized(lock) {
                // 最后再 drain 一下，避免尾巴丢失
                drainEncoderLocked()
                if (muxerStarted && trackIndex >= 0 && encodedBuffer.isNotEmpty()) {
                    for (s in encodedBuffer) {
                        val pts = s.info.presentationTimeUs
                        if (pts > lastWrittenPtsUs) {
                            val bb = ByteBuffer.wrap(s.data)
                            muxer.writeSampleData(trackIndex, bb, s.info)
                            lastWrittenPtsUs = pts
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()

            val bytes = FileInputStream(outFile).use { it.readBytes() }
            if (bytes.isEmpty()) {
                lastMp4FailureReason = "mp4_file_empty"
                return null
            }
            if (bytes.size > maxMp4Bytes) {
                lastMp4FailureReason = "mp4_too_large_${bytes.size}"
                Log.w("VideoRecorder", "MP4过大(${bytes.size} bytes)，改用关键帧拼图")
                return null
            }
            lastMp4FailureReason = "none"
            Log.d("VideoRecorder", "MP4录制完成，大小: ${bytes.size} bytes, path=${outFile.absolutePath}")
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            lastMp4FailureReason = "exception_${e.javaClass.simpleName}"
            Log.e("VideoRecorder", "MP4录制失败，降级为关键帧拼图: ${e.message}", e)
            return null
        } finally {
            try {
                if (outFile.exists()) outFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun captureFallbackKeyframesBase64(currentFrame: Bitmap?): String? {
        synchronized(frameBuffer) {
            if (frameBuffer.isEmpty() && currentFrame == null) {
                Log.w("VideoRecorder", "无缓存帧，无法生成关键帧拼图")
                return null
            }

            try {
                val keyFrames = mutableListOf<Bitmap>()
                if (frameBuffer.isNotEmpty()) {
                    val step = maxOf(1, frameBuffer.size / 3)
                    for (i in frameBuffer.indices step step) {
                        if (keyFrames.size < 3) {
                            keyFrames.add(frameBuffer[i])
                        }
                    }
                }

                if (currentFrame != null) {
                    val scaled = scaleBitmap(currentFrame, targetWidth, targetHeight)
                    keyFrames.add(scaled)
                }

                val combined = combineFrames(keyFrames)
                val outputStream = ByteArrayOutputStream()
                combined.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val bytes = outputStream.toByteArray()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.d("VideoRecorder", "关键帧拼图完成，大小: ${bytes.size} bytes")

                keyFrames.forEach { frame ->
                    if (frame != currentFrame && !frameBuffer.contains(frame)) {
                        frame.recycle()
                    }
                }
                if (combined != currentFrame) {
                    combined.recycle()
                }
                return base64
            } catch (e: Exception) {
                Log.e("VideoRecorder", "关键帧拼图失败", e)
            } finally {
                frameBuffer.forEach { it.recycle() }
                frameBuffer.clear()
                frameTimestampsFallback.clear()
            }
        }
        return null
    }

    private fun combineFrames(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) {
            return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }
        if (frames.size == 1) {
            return frames[0]
        }

        val frameWidth = targetWidth
        val frameHeight = targetHeight
        val totalWidth = frameWidth * frames.size

        val combined = Bitmap.createBitmap(totalWidth, frameHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(combined)

        for (i in frames.indices) {
            canvas.drawBitmap(frames[i], i * frameWidth.toFloat(), 0f, null)
        }

        return combined
    }

    fun stop() {
        synchronized(lock) {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            outputFormat = null
            encodedBuffer.clear()
            lastQueuedPtsUs = 0L
        }
        synchronized(frameBuffer) {
            frameBuffer.forEach { it.recycle() }
            frameBuffer.clear()
            frameTimestampsFallback.clear()
        }
    }

    private fun tryQueueToEncoder(bitmap: Bitmap): Boolean {
        return try {
            synchronized(lock) {
                ensureEncoderLocked()
                if (codec == null) return false

                val nowUs = System.currentTimeMillis() * 1000L
                // 简单节流，避免分析线程高帧率时把 CPU 打满
                if (lastQueuedPtsUs != 0L && nowUs - lastQueuedPtsUs < encodeIntervalUs / 2) {
                    // 仍然 drain，防止输出堆积
                    drainEncoderLocked()
                    return true
                }

                val ptsUs = if (lastQueuedPtsUs == 0L) nowUs else lastQueuedPtsUs + encodeIntervalUs
                val queued = queueBitmapAsI420Locked(bitmap, ptsUs)
                if (queued) {
                    lastQueuedPtsUs = ptsUs
                    // drain 一次，保持 encodedBuffer 新鲜
                    drainEncoderLocked()
                    trimEncodedBufferLocked()
                    return true
                }
                false
            }
        } catch (e: Exception) {
            Log.e("VideoRecorder", "编码入队失败: ${e.message}", e)
            false
        }
    }

    private fun ensureEncoderLocked() {
        if (codec != null) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1秒一个关键帧，方便回放前2秒
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            outputFormat = null
            encodedBuffer.clear()
            Log.d("VideoRecorder", "H264编码器已启动: ${targetWidth}x${targetHeight}@${targetFps}")
        } catch (e: Exception) {
            Log.e("VideoRecorder", "初始化编码器失败: ${e.message}", e)
            codec = null
            outputFormat = null
        }
    }

    private fun queueBitmapAsI420Locked(bitmap: Bitmap, ptsUs: Long): Boolean {
        val c = codec ?: return false
        val inIndex = c.dequeueInputBuffer(0)
        if (inIndex < 0) return false
        val inputBuffer = c.getInputBuffer(inIndex) ?: return false
        inputBuffer.clear()

        val yuv = argbToI420(bitmap)
        if (yuv.size > inputBuffer.remaining()) {
            // 极端情况：设备返回的输入 buffer 太小（不太可能）
            return false
        }
        inputBuffer.put(yuv)
        c.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
        return true
    }

    private fun drainEncoderLocked(collectForWrite: Boolean = false): List<EncodedSample> {
        val c = codec ?: return emptyList()
        val bufferInfo = MediaCodec.BufferInfo()
        val newly = if (collectForWrite) mutableListOf<EncodedSample>() else null

        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = c.outputFormat
                    Log.d("VideoRecorder", "编码输出格式已就绪")
                }
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.get(data)

                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val infoCopy = MediaCodec.BufferInfo().apply {
                            set(0, data.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        val sample = EncodedSample(data, infoCopy, isKey)
                        encodedBuffer.addLast(sample)
                        newly?.add(sample)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                }
                else -> break
            }
        }
        trimEncodedBufferLocked()
        return newly ?: emptyList()
    }

    private fun trimEncodedBufferLocked() {
        val windowUs = bufferPreMs * 1000L
        val newestPts = encodedBuffer.lastOrNull()?.info?.presentationTimeUs ?: return
        val minPts = newestPts - windowUs
        while (encodedBuffer.isNotEmpty() && encodedBuffer.first().info.presentationTimeUs < minPts) {
            encodedBuffer.removeFirst()
        }
        // 额外保险：避免极端情况下 buffer 无限制增长
        while (encodedBuffer.size > targetFps * 5) { // 最多保留约5秒
            encodedBuffer.removeFirst()
        }
    }

    /**
     * 将 ARGB Bitmap 转为 I420 (YUV420 planar)。
     * 输出大小：w*h*3/2
     */
    private fun argbToI420(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val ySize = w * h
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize

        var index = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[index++]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = (c) and 0xff

                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                y = y.coerceIn(0, 255)
                u = u.coerceIn(0, 255)
                v = v.coerceIn(0, 255)

                out[yIndex++] = y.toByte()
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    out[uIndex++] = u.toByte()
                    out[vIndex++] = v.toByte()
                }
            }
        }
        return out
    }
}