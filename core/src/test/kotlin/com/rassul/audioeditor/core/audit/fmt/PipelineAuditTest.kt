package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.int
import com.rassul.audioeditor.core.format.l
import com.rassul.audioeditor.core.format.long
import com.rassul.audioeditor.core.format.m
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.ExtractPlanner
import com.rassul.audioeditor.core.pipeline.FileNaming
import com.rassul.audioeditor.core.pipeline.GaplessTrimmer
import com.rassul.audioeditor.core.pipeline.PtsGapFiller
import com.rassul.audioeditor.core.pipeline.SpaceEstimator
import com.rassul.audioeditor.core.pipeline.TimeText
import com.rassul.audioeditor.core.time.TimeConv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит pipeline: FR-7 (PtsGapFiller), FR-26 (GaplessTrimmer), FR-10, FR-27, FR-24/Д-14, FR-6a, единый форматтер времени. */
class PipelineAuditTest {
    /** 300 случайных последовательностей против gap_fill эталона (порог строгий, позиция от pts₀, перекрытия). */
    @Test fun gapFillMatchesReference() {
        for (v0 in A.vec["gap_fill"].l()) {
            val v = v0.m()
            val g = PtsGapFiller(v["fs"].int())
            val ops = ArrayList<List<Any>>()
            for (f in v["frames"].l()) {
                val (pts, n) = f.l().map { it.long() }
                val a = g.onBlock(pts, n.toInt())
                if (a.insertSilenceFrames != 0L) { ops += listOf("zeros", a.insertSilenceFrames); ops += listOf("keep", n) }
                else if (a.dropFrames != 0) { ops += listOf("drop", a.dropFrames.toLong()); ops += listOf("keep", n - a.dropFrames) }
                else ops += listOf("keep", n)
            }
            // В эталоне «drop 0»/«zeros 0» возможны (граница), в коде — Adjust(0,0): нормализуем.
            fun norm(x: List<List<Any>>) = x.map { listOf(it[0] as String, (it[1] as Number).toLong()) }.filter { it[1] != 0L }
                .fold(ArrayList<List<Any>>()) { acc, e -> if (acc.isNotEmpty() && acc.last()[0] == "keep" && e[0] == "keep") acc[acc.size - 1] = listOf("keep", (acc.last()[1] as Long) + (e[1] as Long)) else acc += e; acc }
            assertEquals(v["frames"].toString().take(300), norm(v["ops"].l().map { it.l().map { x -> x!! } }), norm(ops))
            assertEquals(v["total"].long(), g.written)
        }
    }

    @Test fun gapFillNoDriftAndStrictThreshold() {
        // 100 000 блоков по 1024 @ 44,1 кГц, pts усечены до мкс (как у MediaCodec): накопления нет — ни одной вставки.
        val g = PtsGapFiller(44100)
        for (i in 0 until 100_000) { val pts = i * 1024L * 1_000_000 / 44100; val a = g.onBlock(pts, 1024); assertEquals(0L, a.insertSilenceFrames); assertEquals(0, a.dropFrames) }
        assertEquals(1024L * 100_000, g.written)
        // Ровно +1,000 мс — не разрыв; +1,001 мс — разрыв (48 кГц, блоки 960 = 20 мс).
        val e = PtsGapFiller(48000); e.onBlock(0, 960); assertEquals(PtsGapFiller.Adjust(0, 0), e.onBlock(21_000, 960))
        val g2 = PtsGapFiller(48000); g2.onBlock(0, 960); assertEquals(PtsGapFiller.Adjust(48, 0), g2.onBlock(21_001, 960))
        // Перекрытие ровно −1 мс — не перекрытие; −1,001 мс — отбросить 48.
        val o = PtsGapFiller(48000); o.onBlock(0, 960); assertEquals(PtsGapFiller.Adjust(0, 0), o.onBlock(19_000, 960))
        val o2 = PtsGapFiller(48000); o2.onBlock(0, 960); assertEquals(PtsGapFiller.Adjust(0, 48), o2.onBlock(18_999, 960))
        // Перекрытие больше блока: отбрасывается весь блок, не больше.
        val o3 = PtsGapFiller(48000); o3.onBlock(0, 960); assertEquals(PtsGapFiller.Adjust(0, 960), o3.onBlock(0, 960)); assertEquals(960L, o3.written)
    }

    /** FR-26: декодер уже отбросил часть задержки — полезная длина не уменьшается второй раз (была ошибка, исправлена). */
    @Test fun gaplessTrimmerNoDoubleTrim() {
        // MP3 fr26: 41·1152 = 47 232 полных; delay 1105 (576 + 529), padding 1027 → 45 100.
        for (already in longArrayOf(0, 529, 1105, 1500)) {
            val T0 = 47232L - already
            val t = GaplessTrimmer(1105, 1027, already, T0)
            var kept = 0L; var firstOut = -1L; var pos = 0L
            val r = A.Rng(already)
            while (pos < T0) {
                val n = minOf(T0 - pos, r.range(1, 3000).toLong()).toInt()
                val d = t.dropAtStart(n); val k = t.keepFrames(n)
                assertTrue(d + k <= n)
                if (k > 0 && firstOut < 0) firstOut = pos + d
                kept += k; pos += n
            }
            val expectHead = maxOf(0L, 1105 - already)
            assertEquals("already=$already", expectHead, firstOut)
            // При already > delay декодер съел и полезные сэмплы — восстановить нельзя; иначе ровно 45 100.
            assertEquals("already=$already", if (already <= 1105) 45100L else 45100L - (already - 1105), kept)
            assertEquals(kept, t.validLength)
        }
        // Без длины — хвост режет вызывающий.
        assertEquals(1027L, GaplessTrimmer(1105, 1027, 529, null).tailToTruncateAtFinish)
    }

    @Test fun spaceEstimator() {
        for (v0 in A.vec["space"].l()) { val v = v0.m(); assertEquals(v.toString(), v["required_bytes"].long(), SpaceEstimator.requiredBytes(v["duration_us"].long(), v["fs"].int(), v["ch"].int())) }
        val req = SpaceEstimator.requiredBytes(1_800_000_000L, 48000, 2)
        assertEquals(891_200_000L, req)                                                   // 891,2 МБ (timeline.md ф. 6)
        assertEquals("Недостаточно места: нужно 892 МБ, свободно 891 МБ.", SpaceEstimator.check(req, req - 1))
        assertNull(SpaceEstimator.check(req, req))
        assertEquals("Недостаточно места: нужно 892 МБ, свободно 0 МБ.", SpaceEstimator.check(req, -5))
        assertEquals(200_000_004L, SpaceEstimator.requiredBytes(1, 44100, 1))           // ⌈0,0441⌉ = 1 кадр
    }

    /** Дубликат форматтера: TimeText.format ≡ TimeConv.format (теперь делегирует), оба = эталон по решению главной сессии. */
    @Test fun timeFormattersIdentical() {
        for (v0 in A.vec["display"].l()) {
            val v = v0.m(); val n = v["n"].long(); val fs = v["fs"].int()
            assertEquals(v.toString(), v["text"], TimeConv.format(n, fs))
            assertEquals(v.toString(), TimeConv.format(n, fs), TimeText.format(n, fs))
            assertEquals((2 * n * 1000 + fs) / (2L * fs), TimeText.ms(n, fs))
        }
        // Локаль с другими цифрами не влияет (раньше String.format давал «٠:٠٠.٠٠٠» для ar).
        val def = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG")); assertEquals("1:23.456", TimeText.format(3_680_410, 44100)) }
        finally { java.util.Locale.setDefault(def) }
    }

    @Test fun fileNaming() {
        val ex = HashSet<String>()
        assertEquals(listOf("x_trim.mp3", "x_trim (2).mp3", "x_trim (3).mp3"), (1..3).map { FileNaming.next("x.mp3", "trim", "mp3", ex).also { n -> ex += n } })
        // Длинное кириллическое имя: ≤ 255 байт UTF-8 вместе с суффиксом и номером (ext4/FAT).
        val long = "Очень длинное название видео ".repeat(20) + ".mp4"
        val n = FileNaming.next(long, "audio", "m4a", setOf(FileNaming.next(long, "audio", "m4a", emptySet())))
        assertTrue(n, n.toByteArray(Charsets.UTF_8).size <= 255 && n.endsWith("_audio (2).m4a"))
        // Суррогатная пара не разрывается.
        val emo = "🎵".repeat(100)
        val e = FileNaming.next(emo, "edit", "wav", emptySet())
        assertTrue(e.toByteArray(Charsets.UTF_8).size <= 255); assertTrue(e.removeSuffix("_edit.wav").length % 2 == 0)
        assertEquals("audio_edit.wav", FileNaming.next("  ", "edit", "wav", emptySet()))
        assertEquals("a_b_c_edit.flac", FileNaming.next("a:b*c", "_edit", "flac", emptySet()))
    }

    /** FR-24, Д-14: AAC ≤ 6·fs·C бит/с при любой частоте/каналах; Opus 48 кГц; текст частот точный. */
    @Test fun exportPlanner() {
        for (fs in intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 96000, 7000, 37800))
            for (ch in 1..8) for (want in listOf(null, 64, 192, 320, 1000)) {
                val p = ExportPlanner.plan(PcmSpec(fs, ch), 16, ExportRequest(ExportFormat.M4A, bitrateKbps = want))
                val rate = p.outSpec.sampleRate
                assertTrue("$fs $ch $want → ${p.bitrateKbps}", p.bitrateKbps * 1000L <= 6L * rate * ch)
                assertTrue(rate in intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000) && rate >= minOf(fs, 48000).coerceAtLeast(8000))
                if (want == null && 6L * rate * ch >= 192_000) assertEquals(192, p.bitrateKbps)
            }
        assertEquals(ExportFormat.M4A, ExportRequest().format)
        val o = ExportPlanner.plan(PcmSpec(11025, 1), 16, ExportRequest(ExportFormat.OPUS))
        assertEquals(48000, o.outSpec.sampleRate); assertEquals("Opus всегда сохраняется с частотой 48 кГц (исходная 11,025 кГц).", o.notes.single())
        val a = ExportPlanner.plan(PcmSpec(22050, 1), 16, ExportRequest())
        assertEquals("Битрейт 192 кбит/с недоступен при 22,05 кГц, моно: будет 132 кбит/с.", a.notes.single())
        assertTrue(ExportPlanner.plan(PcmSpec(44100, 1), 16, ExportRequest()).notes.isEmpty())
        val b = ExportPlanner.plan(PcmSpec(8000, 1), 16, ExportRequest())
        assertEquals("Битрейт 192 кбит/с недоступен при 8 кГц, моно: будет 48 кбит/с.", b.notes.single())
        val c = ExportPlanner.plan(PcmSpec(88200, 2), 24, ExportRequest(ExportFormat.M4A))
        assertEquals("Частота понижена до 48 кГц: AAC сохраняется с частотой не выше 48 кГц (исходная 88,2 кГц).", c.notes.first())
        val w = ExportPlanner.plan(PcmSpec(192000, 2), 24, ExportRequest(ExportFormat.WAV))
        assertEquals(192000, w.outSpec.sampleRate); assertEquals(24, w.bits); assertTrue(!w.dither)
        assertEquals(16, ExportPlanner.plan(PcmSpec(48000, 2), 0, ExportRequest(ExportFormat.FLAC)).bits)
    }

    /** FR-6a: главная кнопка — M4A; AAC копируется даже без декодера; без декодера — «как есть» с пояснением FR-9. */
    @Test fun extractPlanner() {
        for (mime in listOf("audio/mp4a-latm", "audio/aac", "AUDIO/MP4A-LATM")) for (dec in listOf(true, false)) {
            val e = ExtractPlanner.plan(mime, dec)
            assertEquals(ExtractPlanner.Action.COPY_AAC, e.action); assertEquals("m4a", e.ext); assertEquals("Сохранить в M4A", e.buttonText)
            assertEquals("Без потерь: копирование AAC", e.note)
        }
        for (mime in listOf("audio/opus", "audio/vorbis", "audio/mpeg", "audio/flac", "audio/raw", "audio/3gpp", "audio/amr-wb", "audio/ac3", "audio/eac3", "audio/vnd.dts")) {
            val e = ExtractPlanner.plan(mime, true)
            assertEquals(ExtractPlanner.Action.TRANSCODE_AAC, e.action); assertEquals("m4a", e.ext); assertEquals("Сохранить в M4A", e.buttonText)
            assertEquals("Перекодирование: ${ExtractPlanner.codecName(mime)} → AAC 192 кбит/с", e.note)
            val n = ExtractPlanner.plan(mime, false)
            assertEquals(ExtractPlanner.Action.AS_IS, n.action); assertEquals("Сохранить как есть", n.buttonText); assertEquals(ExtractPlanner.asIsExt(mime), n.ext)
        }
        assertEquals("Перекодирование: Opus → AAC 160 кбит/с", ExtractPlanner.plan("audio/opus", true, 160).note)
        assertTrue(ExtractPlanner.plan("audio/raw", true).hint!!.startsWith("Исходник без потерь"))
        assertTrue(ExtractPlanner.plan("audio/opus", true).hint!!.startsWith("Повторное сжатие"))
    }
}
