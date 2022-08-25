package com.example.samplefacedetection

import android.app.Application

lateinit var app :App

class App:Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
    }
}