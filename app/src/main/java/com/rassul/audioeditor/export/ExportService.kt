package com.rassul.audioeditor.export

import android.app.Service
import android.content.Intent
import android.os.IBinder

/* Зона MEDIA. Foreground service типа mediaProcessing (FR-29). */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
