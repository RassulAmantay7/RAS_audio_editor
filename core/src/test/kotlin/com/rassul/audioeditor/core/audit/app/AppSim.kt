package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.pipeline.DecoderTrim
import com.rassul.audioeditor.core.pipeline.GaplessTrimmer

/**
 * Модель цепочки MediaDecoder (аудит T21) без Android: «сырой» поток кодека длиной delay + L + pad с импульсом в
 * delay + I → системный декодер (отбрасывает headDrop в начале, хвост меняет на −tailDrop) → DecoderTrim.plan →
 * GaplessTrimmer (блоками) → Holdback (последние tailToTruncateAtFinish кадров не выдаются), как в MediaDecoder.loop.
 */
object AppSim {
    class Out(val length: Long, val impulse: Long)

    fun decode(
        delay: Long, pad: Long, length: Long, impulse: Long,
        headDrop: Long, tailDrop: Long,
        calibrated: Long?, wholeDelayByDefault: Boolean,
        metaRate: Int = 48000, outRate: Int = 48000, block: Int = 1024,
        /** Хвост, переданный в GaplessTrimmer (по умолчанию = фактический [pad]). */
        metaPad: Long = pad,
    ): Out {
        val rawImpulse = delay + impulse
        val decLen = delay + length + pad - headDrop - tailDrop
        val decImpulse = rawImpulse - headDrop
        val p = DecoderTrim.plan(delay, metaPad, metaRate, outRate, calibrated, wholeDelayByDefault)
        val tr = GaplessTrimmer(p.delay, p.padding, p.decoderDrops, null)
        var pos = 0L
        var emitted = 0L
        var imp = -1L
        while (pos < decLen) {
            val n = minOf(block.toLong(), decLen - pos).toInt()
            val d = tr.dropAtStart(n)
            val k = tr.keepFrames(n)
            if (decImpulse in (pos + d) until (pos + d + k)) imp = emitted + (decImpulse - pos - d)
            emitted += k
            pos += n
        }
        val hold = tr.tailToTruncateAtFinish
        val len = maxOf(0L, emitted - hold)
        if (imp >= len) imp = -1
        return Out(len, imp)
    }
}
