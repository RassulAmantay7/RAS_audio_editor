package com.rassul.audioeditor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.rassul.audioeditor.diag.DeviceProfile
import com.rassul.audioeditor.editor.SaveJob
import com.rassul.audioeditor.editor.SaveJobState

/*
 * Зона MEDIA, T18. FR-29: foreground service типа mediaProcessing (Android 15). Работу выполняет ExportJobs;
 * сервис только удерживает процесс и показывает уведомление с процентом и «Отменить».
 * POST_NOTIFICATIONS может быть не выдан — тогда уведомление не видно, но сервис и работа идут.
 * onTimeout(int, int) (API 35, лимит mediaProcessing) — отменить задачи и остановиться корректно.
 */
class ExportService : Service() {
    private val listener = ExportJobs.Listener { jobs -> onJobs(jobs) }
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        ExportJobs.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra(EXTRA_JOB_ID, -1L)
            if (id >= 0) ExportJobs.cancel(id) else ExportJobs.cancelAll()
        }
        goForeground(ExportJobs.jobs)
        if (!ExportJobs.hasActive) stopNow()
        return START_NOT_STICKY
    }

    private fun goForeground(jobs: List<SaveJob>) {
        val n = build(jobs)
        try {
            if (!foreground) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                foreground = true
            } else getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            DeviceProfile.get(this).log("startForeground: ${e.message}")
        }
    }

    private fun onJobs(jobs: List<SaveJob>) {
        if (ExportJobs.hasActive) goForeground(jobs) else stopNow()
    }

    private fun stopNow() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    /** API 35: исчерпан лимит времени mediaProcessing — отменить работу и остановиться (иначе система завершит приложение). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DeviceProfile.get(this).log("ExportService.onTimeout: лимит фоновой обработки исчерпан")
        ExportJobs.cancelAll()
        stopNow()
    }

    @Deprecated("API 34")
    override fun onTimeout(startId: Int) = onTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)

    override fun onDestroy() {
        ExportJobs.removeListener(listener)
        super.onDestroy()
    }

    private fun build(jobs: List<SaveJob>): Notification {
        val active = jobs.firstOrNull { it.state == SaveJobState.RUNNING || it.state == SaveJobState.VERIFYING }
        val more = jobs.count { it.state == SaveJobState.RUNNING || it.state == SaveJobState.VERIFYING } - 1
        val title = active?.title ?: "Сохранение"
        val text = when {
            active == null -> "Готово"
            active.state == SaveJobState.VERIFYING -> "Проверка…"
            active.progress < 0 -> "Подготовка…"
            else -> "${(active.progress * 100).toInt()} %"
        } + if (more > 0) " · ещё $more в очереди" else ""
        val cancel = PendingIntent.getService(this, (active?.id ?: 0L).toInt(),
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB_ID, active?.id ?: -1L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (active != null) {
            if (active.state == SaveJobState.VERIFYING || active.progress < 0) b.setProgress(0, 0, true)
            else b.setProgress(1000, (active.progress * 1000).toInt(), false)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Отменить", cancel).build())
        }
        return b.build()
    }

    companion object {
        const val CHANNEL_ID = "export"
        const val NOTIF_ID = 4201
        const val ACTION_CANCEL = "com.rassul.audioeditor.export.CANCEL"
        const val EXTRA_JOB_ID = "job_id"

        /** Канал уведомлений (идемпотентно; вызывать при старте приложения). */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Сохранение файлов", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Прогресс извлечения и экспорта"
                })
        }
    }
}
