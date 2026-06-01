package com.example.arjun_ai

import android.app.Application

/**
 * App-level singleton. Empty for now; later we'll initialize Gemma engine,
 * Whisper, VAD, etc. here just like GemmaApplication in the POC.
 */
class ArjunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: model preload, logging, etc.
    }
}