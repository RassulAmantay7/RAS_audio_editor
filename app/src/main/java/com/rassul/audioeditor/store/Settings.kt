package com.rassul.audioeditor.store

import android.content.Context
import com.rassul.audioeditor.editor.AppSettings

/* Зона MEDIA, T18. Настройки (FR-19 сглаживание стыков, FR-31 одно касание — по умолчанию выкл.). */
class Settings(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        smoothJoints = sp.getBoolean(K_SMOOTH, true),
        jointMs = java.lang.Double.longBitsToDouble(sp.getLong(K_JOINT, java.lang.Double.doubleToRawLongBits(5.0))),
        oneTapExtract = sp.getBoolean(K_ONE_TAP, false),
    )

    fun save(s: AppSettings) {
        sp.edit().putBoolean(K_SMOOTH, s.smoothJoints)
            .putLong(K_JOINT, java.lang.Double.doubleToRawLongBits(s.jointMs))
            .putBoolean(K_ONE_TAP, s.oneTapExtract).apply()
    }

    private companion object {
        const val K_SMOOTH = "smooth_joints"
        const val K_JOINT = "joint_ms"
        const val K_ONE_TAP = "one_tap_extract"
    }
}
