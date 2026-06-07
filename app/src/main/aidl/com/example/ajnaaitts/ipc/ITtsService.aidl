// ITtsService.aidl
package com.example.ajnaaitts.ipc;

import com.example.ajnaaitts.ipc.ITtsCallback;

interface ITtsService {
    boolean isReady();
    boolean loadVoice(int voiceId);
    void unloadModel();
    void speakChunk(String text, ITtsCallback callback);
    void cancelGeneration();
    int getSampleRate();
    boolean switchKokoroSpeaker(int voiceId);
}