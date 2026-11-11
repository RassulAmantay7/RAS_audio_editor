package com.rassul.audioeditor.diag

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.raw.Ac3Scanner
import com.rassul.audioeditor.core.format.raw.DtsScanner
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPlan
import com.rassul.audioeditor.core.pipeline.ExportResult
import com.rassul.audioeditor.core.pipeline.TimeText
import com.rassul.audioeditor.core.verify.Verifier
import com.rassul.audioeditor.core.verify.VerifyExpect
import com.rassul.audioeditor.core.verify.VerifyReport
import com.rassul.audioeditor.media.CapabilityProbe
import com.rassul.audioeditor.media.CopyResult
import com.rassul.audioeditor.media.FdSource
import com.rassul.audioeditor.media.GaplessResolver
import com.rassul.audioeditor.media.GaplessSpec
import com.rassul.audioeditor.media.MediaDecoder
import com.rassul.audioeditor.media.RemuxCopier
import java.nio.ByteBuffer
import java.util.zip.CRC32

/*
 * Зона MEDIA, T27 (FR-33, часть :app). Повторное открытие сохранённого файла из MediaStore и проверка:
 *  1) Verifier ядра по метаданным (структура, длины — точно);
 *  2) полное декодирование системным MediaCodec тем же путём, что вход редактора (без KEY_ENCODER_DELAY/PADDING
 *     → GaplessTrimmer): WAV/FLAC — длина точно, FLAC — MD5 PCM = MD5 STREAMINFO (RFC 9639 §8.2);
 *     M4A/Opus — |N_dec − N| ≤ 1, запасной режим ± 1 кадр с предупреждением (timeline.md «FR-33»);
 *  3) «как есть»/«быстро» — сравнение сжатых пакетов (CRC-32 каждого + суммарный объём), длительность ± 1 кадр.
 * Вызывать НЕ из UI-потока. Отмена (Progress.isCancelled) → CancelledException («Пропустить проверку»).
 */
class DeviceVerifier(ctx: Context, private val profile: DeviceProfile = DeviceProfile.get(ctx)) {
    private val cr = ctx.applicationContext.contentResolver

    private inline fun <T> open(uri: Uri, block: (java.io.FileDescriptor, FdSource) -> T): T {
        val pfd = try { cr.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
            ?: throw CoreException("Сохранённый файл не открывается.")
        try { return block(pfd.fileDescriptor, FdSource(pfd.fileDescriptor)) } finally { try { pfd.close() } catch (_: Exception) {} }
    }

    private fun failText(problem: String, wav: Boolean) =
        "Файл записан с ошибкой: $problem. ${if (wav) "Попробуйте сохранить ещё раз." else "Попробуйте сохранить ещё раз или выберите WAV."}"

    /** Проверка результата экспорта/перекодирования (WAV/FLAC/M4A/Opus). */
    fun verifyExport(uri: Uri, plan: ExportPlan, result: ExportResult, encoderDelay: Int?, progress: Progress): VerifyReport = try {
        open(uri) { fd, src ->
            val expect = VerifyExpect.of(plan, result, encoderDelay)
            val base = Verifier.verify(src, expect)
            if (!base.ok || plan.format == ExportFormat.WAV) return@open base
            if (progress.isCancelled) throw CancelledException()
            when (plan.format) {
                ExportFormat.FLAC -> verifyFlacDecode(fd, src, expect, base, progress)
                else -> verifyLossyDecode(fd, src, expect, base, progress)
            }
        }
    } catch (e: CancelledException) { throw e
    } catch (e: CoreException) { VerifyReport(false, failText(e.message ?: "не читается", plan.format == ExportFormat.WAV))
    } catch (e: Exception) {
        profile.log("Проверка: ${e.javaClass.simpleName}: ${e.message}")
        VerifyReport(false, failText("файл не читается", plan.format == ExportFormat.WAV))
    }

    private class Track(val index: Int, val format: MediaFormat, val mime: String)

    private fun singleAudioTrack(fd: java.io.FileDescriptor): Track {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(fd)
            val audio = (0 until ex.trackCount).map { it to ex.getTrackFormat(it) }.filter { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (audio.size != 1) throw CoreException("звуковых дорожек ${audio.size} вместо одной")
            return Track(audio[0].first, MediaFormat(audio[0].second), audio[0].second.getString(MediaFormat.KEY_MIME)!!)
        } catch (e: CoreException) { throw e } catch (e: Exception) { throw CoreException("контейнер не читается системой", e) } finally { ex.release() }
    }

    private fun verifyFlacDecode(fd: java.io.FileDescriptor, src: RandomAccessSource, e: VerifyExpect, base: VerifyReport, progress: Progress): VerifyReport {
        val t = singleAudioTrack(fd)
        if (!CapabilityProbe.hasDecoder(t.format)) {
            profile.log("FR-33 FLAC: системного декодера нет — проверка своим декодером ядра")
            return Verifier.verify(src, e, flacFullDecode = true)
        }
        val si = FileFacts.flacStreamInfo(src)
        val bits = si.bitsPerSample
        val nb = (bits + 7) / 8
        val md5 = java.security.MessageDigest.getInstance("MD5")
        var bytes = ByteArray(0)
        var exact = true
        val scale = Math.scalb(1.0, bits - 1)
        val dec = MediaDecoder(fd, t.index, t.format, GaplessSpec(null, si.sampleRate), profile, requestFloat = bits > 16)
        var chOut = 0
        val stats = dec.run(progress, { f -> chOut = f.channels }) { buf, n ->
            val m = n * chOut
            if (bytes.size < m * nb) bytes = ByteArray(m * nb)
            var k = 0
            for (i in 0 until m) {
                // timeline.md FR-33: float → int обратимо точно: v = f·2^(bits−1) — целое (24 бита мантиссы float32).
                val d = buf[i] * scale
                var v = Math.rint(d).toLong()
                if (d != v.toDouble()) exact = false
                v = v.coerceIn(-(1L shl (bits - 1)), (1L shl (bits - 1)) - 1)
                var x = v
                repeat(nb) { bytes[k++] = x.toByte(); x = x shr 8 }
            }
            md5.update(bytes, 0, k)
        }
        if (chOut != e.channels) return VerifyReport(false, failText("каналов $chOut вместо ${e.channels}", false), actualFrames = stats.frames)
        if (stats.frames != e.frames) return VerifyReport(false, failText(lenText(stats.frames, e.frames, e.sampleRate), false), actualFrames = stats.frames)
        if (!exact) {
            profile.log("FR-33 FLAC: выход декодера не целочисленный — MD5 не сверялся, проверена только длина")
            return base.copy(warnings = base.warnings + "Декодер телефона неточен для ${bits} бит: MD5 не сверялся.")
        }
        val want = e.pcmMd5 ?: si.md5
        if (!md5.digest().contentEquals(want)) return VerifyReport(false, failText("MD5 декодированного звука не совпал с записанным", false), actualFrames = stats.frames)
        return base
    }

    private fun verifyLossyDecode(fd: java.io.FileDescriptor, src: RandomAccessSource, e: VerifyExpect, base: VerifyReport, progress: Progress): VerifyReport {
        val t = singleAudioTrack(fd)
        val wantMime = if (e.format == ExportFormat.M4A) "audio/mp4a-latm" else "audio/opus"
        if (!t.mime.equals(wantMime, true)) return VerifyReport(false, failText("кодек ${t.mime} вместо ${if (e.format == ExportFormat.M4A) "AAC" else "Opus"}", false))
        if (!CapabilityProbe.hasDecoder(t.format)) return base.copy(warnings = base.warnings + "Нет системного декодера — проверены только заголовки.")
        val rate = t.format.getInteger(MediaFormat.KEY_SAMPLE_RATE, e.sampleRate)
        val g = GaplessResolver.resolve(src, t.mime, t.format, rate, 1, profile::log)
        var ch = 0
        val stats = MediaDecoder(fd, t.index, t.format, g, profile).run(progress, { f -> ch = f.channels }) { _, _ -> }
        if (ch != e.channels) return VerifyReport(false, failText("каналов $ch вместо ${e.channels}", false), actualFrames = stats.frames)
        val n = stats.frames
        val diff = Math.abs(n - e.frames)
        // Допуск FR-33: ±1 сэмпл; запасной режим FR-26 — ±1 кадр (AAC 1024, Opus 960) с записью в журнал.
        val frame = if (e.format == ExportFormat.M4A) 1024 else 960
        return when (com.rassul.audioeditor.core.pipeline.LengthTolerance.lossy(n, e.frames, frame)) {
            com.rassul.audioeditor.core.pipeline.LengthTolerance.Verdict.OK -> base
            com.rassul.audioeditor.core.pipeline.LengthTolerance.Verdict.WARN -> {
                profile.log("FR-33: длина после декодирования $n, ожидалось ${e.frames} (запасной режим ±1 кадр)")
                base.copy(warnings = base.warnings + "Длина после декодирования отличается на $diff сэмпл. (в пределах одного кадра).")
            }
            com.rassul.audioeditor.core.pipeline.LengthTolerance.Verdict.FAIL -> VerifyReport(false, failText(lenText(n, e.frames, e.sampleRate), false), actualFrames = n)
        }
    }

    private fun lenText(n: Long, want: Long, fs: Int) = "длина ${TimeText.format(n, fs)} ($n сэмпл.) вместо ${TimeText.format(want, fs)} ($want сэмпл.)"

    // ------------------------------------------------------------------ «как есть», «быстро»

    private class Packets(val crcs: IntArray, val bytes: Long, val total: Long)

    /** Проверка копии «как есть» / быстрой обрезки: пакеты и байты полезных данных, длительность ± 1 кадр. */
    fun verifyCopy(uri: Uri, exp: CopyResult, progress: Progress): VerifyReport = try {
        open(uri) { fd, src ->
            if (exp.ext == "wav") {
                val spec = exp.wavSpec ?: throw CoreException("нет ожидаемых параметров WAV")
                return@open Verifier.verify(src, VerifyExpect(ExportFormat.WAV, spec.sampleRate, spec.channels, exp.wavBits, exp.durationFrames))
            }
            if (progress.isCancelled) throw CancelledException()
            val (p, dur) = readPackets(fd, src, exp.ext)
            val raw = exp.ext in setOf("mp3", "ac3", "eac3", "dts")
            when {
                p.bytes != exp.payloadBytes -> fail("объём данных ${p.bytes} байт вместо ${exp.payloadBytes}")
                p.total != exp.totalCrc -> fail("содержимое пакетов отличается от исходного")
                !raw && p.crcs.size != exp.packetCrcs.size -> fail("пакетов ${p.crcs.size} вместо ${exp.packetCrcs.size}")
                !raw && !p.crcs.contentEquals(exp.packetCrcs) -> fail("содержимое пакетов отличается от исходного")
                dur != null && exp.durationFrames > 0 && exp.packetFrames > 0 && Math.abs(dur - exp.durationFrames) > exp.packetFrames ->
                    fail(lenText(dur, exp.durationFrames, exp.sampleRate))
                else -> VerifyReport(true, buildString {
                    append("Проверено: пакеты совпали (${exp.packetCrcs.size} шт.)")
                    if (exp.durationFrames > 0 && exp.sampleRate > 0) append(", длина ${TimeText.format(exp.durationFrames, exp.sampleRate)}")
                }, actualFrames = dur)
            }
        }
    } catch (e: CancelledException) { throw e
    } catch (e: CoreException) { fail(e.message ?: "файл не читается")
    } catch (e: Exception) { profile.log("Проверка копии: ${e.javaClass.simpleName}: ${e.message}"); fail("файл не читается") }

    private fun fail(problem: String) = VerifyReport(false, "Файл записан с ошибкой: $problem. Попробуйте сохранить ещё раз.")

    private fun readPackets(fd: java.io.FileDescriptor, src: RandomAccessSource, ext: String): Pair<Packets, Long?> {
        val kind = when (ext) { "flac" -> RemuxCopier.Kind.FLAC; else -> RemuxCopier.Kind.AAC }
        val crcs = ArrayList<Int>(); var bytes = 0L; val total = CRC32(); val one = CRC32()
        var buf = ByteArray(1 shl 16)
        fun add(b: ByteArray, len: Int) {
            val r = RemuxCopier.payloadRange(kind, b, len); val l = r.last - r.first + 1
            one.reset(); one.update(b, r.first, l); total.update(b, r.first, l); crcs += one.value.toInt(); bytes += l
        }
        fun at(off: Long, size: Int) {
            if (buf.size < size) buf = ByteArray(size)
            var got = 0
            while (got < size) { val r = src.read(off + got, buf, got, size - got); if (r <= 0) break; got += r }
            add(buf, got)
        }
        var dur: Long? = null
        when (ext) {
            "m4a" -> {
                val t = Mp4AudioTrack.read(src) ?: throw CoreException("контейнер M4A не читается")
                for (i in 0 until t.sampleCount) at(t.sampleOffsets[i], t.sampleSizes[i])
                dur = FileFacts.mp4Audio(src).lengthFrames
            }
            "ogg" -> {
                val s = OggReader.read(src)
                for (k in 2 until s.packets.size) { val d = s.packets[k].data; add(d, d.size) }
                dur = FileFacts.oggOpus(src).length48
            }
            "mp3" -> { val s = Mp3Scanner.scan(src); for (f in s.frames) at(f.offset, f.size); dur = s.lameLength }
            "ac3", "eac3" -> for (f in Ac3Scanner.scan(src)) at(f.offset, f.size)
            "dts" -> for (f in DtsScanner.scan(src)) at(f.offset, f.size)
            "flac" -> { val ff = FlacFrames.scan(src); for (f in ff.frames) at(f.offset, f.size); dur = ff.streamInfo.totalSamples }
            else -> {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(fd)
                    val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        ?: throw CoreException("в файле нет звуковой дорожки")
                    ex.selectTrack(idx)
                    var bb = ByteBuffer.allocate(1 shl 16)
                    while (true) {
                        val sz = ex.sampleSize
                        if (sz < 0) break
                        if (bb.capacity() < sz) bb = ByteBuffer.allocate(sz.toInt())
                        bb.clear()
                        val n = ex.readSampleData(bb, 0)
                        if (n < 0) break
                        if (buf.size < n) buf = ByteArray(n)
                        bb.position(0); bb.get(buf, 0, n); add(buf, n)
                        ex.advance()
                    }
                } finally { ex.release() }
            }
        }
        return Packets(crcs.toIntArray(), bytes, total.value) to dur
    }
}
