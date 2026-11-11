package com.rassul.audioeditor

import android.app.Application
import com.rassul.audioeditor.export.ExportService
import com.rassul.audioeditor.media.PcmCache
import com.rassul.audioeditor.media.SourceOpener

/** Точка сборки зависимостей (дополняет интеграция). При запуске: очистка остатков кэшей (FR-10), канал уведомлений (FR-29). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread({
            PcmCache.cleanupStale(this)
            SourceOpener.cleanupStale(this)
        }, "cleanup").start()
        ExportService.ensureChannel(this)
    }
}
