package com.rassul.audioeditor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.rassul.audioeditor.editor.AppController
import com.rassul.audioeditor.editor.RealAppController
import com.rassul.audioeditor.export.ExportService
import com.rassul.audioeditor.media.PcmCache
import com.rassul.audioeditor.media.SourceOpener
import com.rassul.audioeditor.ui.AppControllerHost

/**
 * Точка сборки зависимостей. При запуске: очистка остатков кэшей (FR-10), канал уведомлений (FR-29).
 * Контроллер живёт в процессе (переживает пересоздание Activity, поворот, выход на главный экран телефона).
 * FR-13: при уходе Activity в фон воспроизведение ставится на паузу.
 */
class App : Application(), AppControllerHost {
    private val controller by lazy { RealAppController(this) }
    override val appController: AppController get() = controller

    override fun onCreate() {
        super.onCreate()
        Thread({
            PcmCache.cleanupStale(this)
            SourceOpener.cleanupStale(this)
        }, "cleanup").start()
        ExportService.ensureChannel(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) { controller.onAppPause() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
