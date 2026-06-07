package com.example.arjun_ai

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneAudioReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "ArjunAudioRx"
        private const val CHANNEL_PATH = "/arjun/audio"
        private const val SAMPLE_RATE = 16_000
        const val RECORDINGS_SUBDIR = "recordings"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != CHANNEL_PATH) return
        Log.d(TAG, "Channel opened from ${channel.nodeId} (liveCapture=${ConversationAudioBus.liveCapture})")

        // Live agent conversation: forward PCM frames to the VAD pipeline, no file.
        if (ConversationAudioBus.liveCapture) {
            scope.launch {
                val client = Wearable.getChannelClient(applicationContext)
                try {
                    val input: InputStream = client.getInputStream(channel).await()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val sink = ConversationAudioBus.onWatchPcm ?: break
                        sink(bytesToShorts(buf, n))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "live channel read failed", e)
                }
            }
            return
        }

        // Phase-1 audio-test: write a timestamped WAV.
        AudioSink.onConnected()
        scope.launch {
            val client = Wearable.getChannelClient(applicationContext)
            try {
                val input: InputStream = client.getInputStream(channel).await()
                val recordingsDir = File(filesDir, RECORDINGS_SUBDIR).apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val pcmFile = File(recordingsDir, "session_${timestamp}_watch.pcm")
                val wavFile = File(recordingsDir, "session_${timestamp}_watch.wav")

                val out = FileOutputStream(pcmFile)
                val buf = ByteArray(4096)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                    AudioSink.onBytes(total)
                }
                out.flush(); out.close()
                writeWav(pcmFile, wavFile, SAMPLE_RATE)
                pcmFile.delete()

                Log.d(TAG, "Saved WAV: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
                AudioSink.onFinished(wavFile)
            } catch (e: Exception) {
                Log.e(TAG, "channel read failed", e)
                AudioSink.onError(e.message ?: "unknown")
            } finally {
                InputSourceManager.onWatchStreamFinished()
            }
        }
    }

    private fun bytesToShorts(bytes: ByteArray, len: Int): ShortArray {
        val shorts = ShortArray(len / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return shorts
    }

    override fun onChannelClosed(
        channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int
    ) {
        Log.d(TAG, "Channel closed reason=$closeReason")
        AudioSink.onDisconnected()
        InputSourceManager.onWatchStreamFinished()
    }

    private fun writeWav(pcm: File, wav: File, sampleRate: Int) {
        val pcmBytes = pcm.readBytes()
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2
        FileOutputStream(wav).use { fos ->
            val h = ByteArray(44)
            "RIFF".toByteArray().copyInto(h, 0)
            writeInt(h, 4, totalDataLen)
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            writeInt(h, 16, 16); writeShort(h, 20, 1); writeShort(h, 22, 1)
            writeInt(h, 24, sampleRate); writeInt(h, 28, byteRate)
            writeShort(h, 32, 2); writeShort(h, 34, 16)
            "data".toByteArray().copyInto(h, 36)
            writeInt(h, 40, pcmBytes.size)
            fos.write(h); fos.write(pcmBytes)
        }
    }
    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte(); b[o+1] = ((v shr 8) and 0xff).toByte()
        b[o+2] = ((v shr 16) and 0xff).toByte(); b[o+3] = ((v shr 24) and 0xff).toByte()
    }
    private fun writeShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte(); b[o+1] = ((v shr 8) and 0xff).toByte()
    }
}