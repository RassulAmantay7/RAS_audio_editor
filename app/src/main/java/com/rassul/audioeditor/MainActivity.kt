package com.rassul.audioeditor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.view.View
import android.widget.FrameLayout
import com.rassul.audioeditor.editor.AppController
import com.rassul.audioeditor.editor.Listener
import com.rassul.audioeditor.editor.Screen
import com.rassul.audioeditor.ui.AppControllerHost
import com.rassul.audioeditor.ui.FakeAppController
import com.rassul.audioeditor.ui.Host
import com.rassul.audioeditor.ui.JobsPanel
import com.rassul.audioeditor.ui.MainUi
import com.rassul.audioeditor.ui.Tokens
import com.rassul.audioeditor.ui.Ui
import com.rassul.audioeditor.ui.screenRoot

/**
 * Единственная Activity. Экраны — View, переключаемые по AppController.screen (Главная, Анализ, Извлечение,
 * Декодирование, Редактор, Ошибка); «Сохранённые», «Настройки», «Самопроверка» — вложенные экраны поверх Главной.
 * Вся логика — за AppController; здесь только навигация, intent-ы, системные запросы и разрешение на уведомления.
 */
class MainActivity : Activity() {
    companion object {
        /** true — интерфейс на фейках (FakeAppController); false — берём контроллер из Application (AppControllerHost). */
        const val USE_FAKE = true
        private const val REQ_NOTIF = 71
        private const val PREFS = "ui"
        private const val KEY_NOTIF_ASKED = "notif_asked"
    }

    private lateinit var controller: AppController
    private lateinit var ui: MainUi
    private var pendingSave: (() -> Unit)? = null

    private val listener = object : Listener {
        override fun onStateChanged() { ui.onStateChanged() }
        override fun onMessage(text: String, action: String?, onAction: (() -> Unit)?) {
            if (action != null && onAction != null) Ui.toast(this@MainActivity, text, action, onAction) else Ui.toast(this@MainActivity, text)
        }
    }

    private val host = object : Host {
        override val activity: Activity get() = this@MainActivity
        override val controller: AppController get() = this@MainActivity.controller
        override fun beforeSave(action: () -> Unit) = this@MainActivity.beforeSave(action)
        override fun toast(msg: String) = Ui.toast(this@MainActivity, msg)
        override fun showSaved() = ui.push("saved")
        override fun showSettings() = ui.push("settings")
        override fun showSelfTest() = ui.push("selftest")
        override fun back() = ui.pop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this, dark = true)
        controller = if (USE_FAKE) {
            (lastNonConfigurationInstance as? AppController) ?: FakeAppController()
        } else {
            (application as? AppControllerHost)?.appController
                ?: error("Application должен реализовать AppControllerHost, либо включите USE_FAKE")
        }
        val root = screenRoot()
        val container = FrameLayout(this)
        val jobs = JobsPanel(host)
        root.addView(container, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(jobs.view, android.widget.LinearLayout.LayoutParams(-1, -2))
        ui = MainUi(host, container, jobs)
        controller.addListener(listener)
        // предиктивное «назад» (targetSdk 35): onBackPressed в этом режиме не вызывается
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        ui.render()
        if (savedInstanceState == null) handleIntent(intent)
    }

    @Deprecated("Фейк-контроллер переживает пересоздание активити (поворот и т. п.)")
    override fun onRetainNonConfigurationInstance(): Any? = if (USE_FAKE) controller else null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** VIEW («Открыть с помощью») и SEND («Поделиться») — FR-1; fromShare = true (для FR-31). */
    private fun handleIntent(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            setIntent(Intent(Intent.ACTION_MAIN))
            ui.clearSub()
            controller.open(uri, true)
        }
    }

    @Deprecated("Результат системного выбора файла передаётся контроллеру")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        controller.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Оставлено по контракту; при предиктивном «назад» работает OnBackInvokedCallback")
    override fun onBackPressed() { handleBack() }

    private fun handleBack() {
        if (!ui.handleBack()) finish()
    }

    override fun onPause() {
        ui.onPause()   // FR-13: в фоне воспроизведение на паузу
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        ui.render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.rebuild()   // поворот: редактор перестраивается в альбомную/портретную раскладку
    }

    override fun onDestroy() {
        controller.removeListener(listener)
        ui.dispose()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ уведомления перед первым сохранением (FR-29)

    private fun beforeSave(action: () -> Unit) {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_NOTIF_ASKED, false)) { action(); return }
        Ui.showChoice(this, "Показывать ход сохранения?",
            "Пока файл сохраняется, приложение покажет уведомление с процентами и кнопкой «Отменить». " +
                "Без него сохранение тоже работает, но следить за ним можно только на экране приложения.",
            listOf(
                "Не сейчас" to { prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply(); action() },
                "Разрешить" to {
                    prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                    pendingSave = action
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                },
            ))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) Ui.toast(this, "Уведомления выключены. Ход сохранения виден внутри приложения.")
        pendingSave?.invoke()
        pendingSave = null
    }
}
