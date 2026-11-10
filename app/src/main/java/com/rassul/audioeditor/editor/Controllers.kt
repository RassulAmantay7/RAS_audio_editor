package com.rassul.audioeditor.editor

import android.app.Activity
import android.net.Uri
import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.peaks.PeakPyramid
import com.rassul.audioeditor.core.pipeline.ExportRequest

/*
 * КОНТРАКТ UI ↔ логика (главная сессия). UI (агент ui-designer) работает ТОЛЬКО через эти интерфейсы
 * и пишет FakeAppController для разработки без логики. Реализация — агент MEDIA/интеграция.
 * Все методы — из UI-потока; колбэки Listener приходят в UI-потоке.
 * Все тексты (message, title, subtitle, reason) — готовые русские строки для показа.
 */

interface Listener {
    /** Что-то в состоянии изменилось — перечитать state и перерисовать. */
    fun onStateChanged()
    /** Короткое сообщение пользователю (snackbar). action — текст кнопки (или null). */
    fun onMessage(text: String, action: String? = null, onAction: (() -> Unit)? = null) {}
}

// ------------------------------------------------------------------ приложение и источник

/** Описание звуковой дорожки для экрана выбора (FR-5). */
data class TrackItem(
    val index: Int,
    /** «Дорожка 1 · рус · AAC · стерео · 48 кГц · 192 кбит/с». */
    val title: String,
    val isDefault: Boolean,
    /** Можно ли раскодировать на этом телефоне (FR-9). */
    val decodable: Boolean,
    /** Пояснение, если нельзя (null — можно). */
    val reason: String?,
)

/** Главное действие экрана извлечения (FR-6a). */
data class PrimaryExtract(
    val title: String,        // «Сохранить в M4A»
    val subtitle: String,     // «Без потерь — копирование AAC» / «Перекодирование в AAC 192 кбит/с»
    val enabled: Boolean,
    val hint: String?,        // «Для архива выберите FLAC/WAV» и т.п.
)

/** Варианты «Другой формат…» при извлечении. */
enum class ExtractOption { AS_IS, M4A, OPUS, FLAC, WAV }

sealed class Screen {
    /** Главная: открыть файл, сохранённые. */
    object Home : Screen()
    /** Анализ файла. */
    data class Probing(val sourceName: String) : Screen()
    /** Видео/аудио открыто: дорожки, сохранить звук или открыть в редакторе. */
    data class Extract(val sourceName: String, val isVideo: Boolean, val tracks: List<TrackItem>, val selected: Int, val primary: PrimaryExtract) : Screen()
    /** Декодирование в редактор, progress 0..1. */
    data class Decoding(val sourceName: String, val progress: Double) : Screen()
    object Editor : Screen()
    data class Error(val title: String, val message: String) : Screen()
}

/** Фоновая задача сохранения (извлечение / экспорт / быстрая обрезка) и её проверка (FR-33). */
data class SaveJob(
    val id: Long,
    val title: String,            // «Сохранение: lecture_edit.m4a»
    val progress: Double,         // 0..1, −1 — неопределённо
    val state: SaveJobState,
    val fileName: String?,
    val uri: Uri?,
    val message: String?,         // «Проверено: длина совпала» / текст ошибки
)
enum class SaveJobState { RUNNING, VERIFYING, DONE, VERIFY_FAILED, FAILED, CANCELLED }

/** Сохранённый файл (FR-28). */
data class SavedFile(val uri: Uri, val name: String, val sizeBytes: Long, val durationMs: Long, val dateMs: Long, val verifyFailed: Boolean)

/** Настройки (FR-19, FR-31). */
data class AppSettings(val smoothJoints: Boolean = true, val jointMs: Double = 5.0, val oneTapExtract: Boolean = false)

interface AppController {
    val screen: Screen
    val jobs: List<SaveJob>
    val settings: AppSettings
    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** Открыть файл (FR-1). fromShare — пришёл через «Поделиться»/«Открыть с помощью» (для FR-31). */
    fun open(uri: Uri, fromShare: Boolean)
    /** Запросы выбора файла: Activity запускает системный выбор и потом зовёт open(). */
    fun pickVideo(activity: Activity)
    fun pickAudioOrVideoFile(activity: Activity)
    /** Результат выбора (из Activity.onActivityResult). */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean

    fun selectTrack(index: Int)
    fun extractPrimary()
    fun extractAs(option: ExtractOption, request: ExportRequest? = null)
    fun openInEditor()
    fun goHome()

    fun cancelJob(id: Long)
    fun skipVerify(id: Long)

    fun savedFiles(): List<SavedFile>
    fun share(activity: Activity, file: SavedFile)
    fun openSaved(file: SavedFile)
    fun delete(file: SavedFile)

    fun updateSettings(s: AppSettings)

    /** Редактор (не null, когда screen == Editor). */
    val editor: EditorController?

    /** Самопроверка (FR-9/25/26, ОВ-2…4): запуск и текстовый отчёт. */
    fun runSelfTest(onProgress: (String) -> Unit, onDone: (report: String) -> Unit)
}

// ------------------------------------------------------------------ редактор

data class Selection(val start: Long, val end: Long)

data class EditorState(
    val fileName: String,
    val sampleRate: Int,
    val channels: Int,
    /** Длина результата правок, кадры. */
    val length: Long,
    val selection: Selection?,
    val playing: Boolean,
    val looping: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** Есть ли участки с |x| > 1 (FR-14). */
    val hasOverload: Boolean,
    /** Идёт пересчёт (нормализация и т.п.): показывать индикатор, действия временно недоступны. */
    val busy: String?,
    /** Можно ли «Быстро, без потерь» (FR-23). */
    val fastTrimAvailable: Boolean,
    /** Сообщения нормализации (FR-22) для постоянного показа. */
    val notices: List<String>,
)

/** Результат проверки полей времени: ошибки для показа ПОД полями (null — без ошибки). */
data class TimeFieldErrors(val start: String?, val end: String?) { val ok get() = start == null && end == null }

interface EditorController {
    val state: EditorState
    /** Пирамида пиков РЕЗУЛЬТАТА правок (для рисования волны). Может обновляться — ориентироваться на onStateChanged. */
    val peaks: PeakPyramid
    /** Сырые сэмплы результата для максимального масштаба (каналы чередуются). */
    fun readSamples(from: Long, dst: FloatArray, frames: Int): Int
    /** Позиция воспроизведения (кадры) — UI опрашивает каждый кадр анимации. */
    val playheadFrame: Long

    fun play()
    fun pause()
    fun seekTo(frame: Long)
    fun setLooping(loop: Boolean)            // FR-32

    fun setSelection(sel: Selection?)
    /** Ввести выделение текстом «ч:мм:сс.ммм» (FR-15); при ошибке выделение не меняется. */
    fun setSelectionText(startText: String, endText: String): TimeFieldErrors
    fun formatTime(frame: Long): String

    fun keepSelection()                      // FR-17
    fun deleteSelection()                    // FR-18
    fun volume(db: Double)                   // FR-20 (выделение или весь файл)
    /** FR-21: без выделения — durationSec от начала/до конца. */
    fun fade(fadeIn: Boolean, curve: FadeCurve, durationSec: Double)
    fun normalize(target: NormalizeTarget)   // FR-22
    fun undo()
    fun redo()

    /** Экспорт результата (FR-24): запускает SaveJob в AppController.jobs. */
    fun export(request: ExportRequest)
    /** Описание того, что сделает экспорт с этими параметрами (пометки ExportPlan.notes, подсказки). */
    fun describeExport(request: ExportRequest): List<String>
    /** FR-23: предварительный расчёт фактических границ (текст), null — недоступно. */
    fun fastTrimPreview(): String?
    fun fastTrim()

    fun close()
}
