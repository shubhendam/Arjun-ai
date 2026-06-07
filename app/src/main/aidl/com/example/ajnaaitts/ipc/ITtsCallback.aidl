// ITtsCallback.aidl
package com.example.ajnaaitts.ipc;

oneway interface ITtsCallback {
    void onAudioChunk(in byte[] pcmData, int sampleRate, long audioDurationMs);
    void onComplete();
    void onError(String message);
}