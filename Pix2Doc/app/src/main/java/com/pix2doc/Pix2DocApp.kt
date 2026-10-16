package com.pix2doc

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class Pix2DocApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Notifications.createChannels(this)
    }
}
