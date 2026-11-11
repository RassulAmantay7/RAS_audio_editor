# Контракты между модулями (главная сессия)

## Правила для всех агентов
- Kotlin 2.0.21, JVM 17. `:core` — только stdlib + java.*; `:app` — только публичный Android SDK 35, без androidx и библиотек, без XML-разметки и без `R.*` (UI в коде).
- Перед сдачей: `scripts/check.sh` → `CHECK: OK` (компилирует `:core`, гоняет его JUnit-тесты, компилирует весь Kotlin `:app` против android-35.jar).
- Сигнатуры в файлах-контрактах не менять (добавлять можно). Свои файлы — только в своей зоне.
- Тексты для пользователя — на русском, понятным языком; ошибки ввода — под полем.
- У каждой формулы — комментарий с источником (docs/math/dsp.md, docs/math/timeline.md, стандарт, раздел).
- ЕДИНИЦЫ: позиции/длины — кадры (сэмплов на канал), `Long`; частота — `Int` Гц; PCM — `Float` [−1,1], чередование каналов. Секунды/мс/дБ — только на входе/выходе.
- Тесты, которым нужен ffmpeg/ffprobe/flac: если инструмента нет и `REQUIRE_TOOLS != "1"` — пропуск (Assume), если `REQUIRE_TOOLS=1` (CI) — падение.

## Файлы-контракты
| Файл | Что |
|---|---|
| `core/.../model/Model.kt` | PcmSpec, PcmSource, Progress, CoreException (реализовано) |
| `core/.../io/Io.kt` | RandomAccessSource, SeekableSink и простые реализации (реализовано) |
| `core/.../time/Time.kt` | TimeConv (FR-C1, FR-15) |
| `core/.../dsp/Dsp.kt` | Gain, FadeCurves, JointSmoother, Downmix, Resampler, Quantizer |
| `core/.../loudness/Loudness.kt` | LoudnessMeter, Normalizer |
| `core/.../peaks/Peaks.kt` | PeakPyramid, WaveViewport |
| `core/.../edit/Edit.kt` | EditOp, EditList, UndoStack, Renderer |
| `core/.../format/Formats.kt` | WavWriter/Reader, FlacWriter, Mp4AacWriter, OggOpusWriter, GaplessProbe, ContainerSniffer |
| `core/.../pipeline/Pipeline.kt` | ExportFormat/Request/Plan/Planner, FileNaming, SpaceEstimator, PtsGapFiller, GaplessTrimmer, FastTrimPlanner |
| `app/.../editor/Controllers.kt` | AppController, EditorController и модели экранов (UI ↔ логика) |

## Зоны
| Агент | Файлы |
|---|---|
| DSP | `core/.../{time,dsp,loudness,peaks}/**` + тесты |
| FORMATS | `core/.../{format,pipeline}/**` (кроме `pipeline/ExportPipeline.kt`) + тесты, `scripts/gen_fixtures.py`, `core/src/test/resources/fixtures/**` |
| EDIT | `core/.../edit/**`, `core/.../pipeline/ExportPipeline.kt`, `core/.../verify/**`, `core/.../cli/**` + тесты |
| ui-designer | `app/.../ui/**`, `MainActivity.kt`, `docs/design.md`, `res/**` |
| MEDIA | `app/.../{media,export,store,diag,editor}/**` (кроме Controllers.kt), `App.kt` |
| главная сессия | Gradle, манифест, CI, `model/`, `io/`, контракты, docs |

## Заметки аудита для следующих агентов
- **MEDIA (декодер):** любые NaN/Inf из MediaCodec заменять на 0 до записи в кэш PCM — LoudnessMeter не устойчив к NaN (аудит T13).
- **EDIT (ExportPipeline):** для 24 бит `Quantizer(24, dither = false)`; дизеринг только для 16 бит (FR-C7).
