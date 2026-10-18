package com.mapineda48.phonotrainer.core.study

import com.mapineda48.phonotrainer.core.domain.model.Advice
import com.mapineda48.phonotrainer.core.domain.model.ChangeLabel
import com.mapineda48.phonotrainer.core.domain.model.ClipSpan
import com.mapineda48.phonotrainer.core.domain.model.Family
import com.mapineda48.phonotrainer.core.domain.model.FeedMode
import com.mapineda48.phonotrainer.core.domain.model.JobId
import com.mapineda48.phonotrainer.core.domain.model.Occurrence
import com.mapineda48.phonotrainer.core.domain.model.PhraseWord
import com.mapineda48.phonotrainer.core.domain.model.Question
import com.mapineda48.phonotrainer.core.domain.model.QuestionKind
import com.mapineda48.phonotrainer.core.domain.model.QuestionOption
import com.mapineda48.phonotrainer.core.domain.model.WordRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How `learning.db` keeps what has no columns of its own (architecture 4.3): a stored question (`question_json`,
 * `questions_json`), a session's answers (`answers_json`) and a feed mode (`mode_json`). Reading never throws: a value
 * that does not decode reads as null, so a damaged row never crashes a screen.
 */
internal object StudyJson {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
  private val questions = ListSerializer(QuestionDto.serializer())
  private val answers = ListSerializer(String.serializer().nullable)

  fun question(question: Question): String = json.encodeToString(QuestionDto.serializer(), QuestionDto.of(question))

  fun question(text: String?): Question? = decode(text) { json.decodeFromString(QuestionDto.serializer(), it).toQuestion() }

  fun questions(list: List<Question>): String = json.encodeToString(questions, list.map(QuestionDto::of))

  fun questions(text: String?): List<Question>? = decode(text) { json.decodeFromString(questions, it).map(QuestionDto::toQuestion) }

  fun answers(list: List<String?>): String = json.encodeToString(answers, list)

  fun answers(text: String?): List<String?>? = decode(text) { json.decodeFromString(answers, it) }

  fun mode(mode: FeedMode): String = json.encodeToString(ModeDto.serializer(), ModeDto.of(mode))

  fun mode(text: String?): FeedMode? = decode(text) { json.decodeFromString(ModeDto.serializer(), it).toMode() }

  private inline fun <T> decode(text: String?, read: (String) -> T?): T? {
    if (text.isNullOrEmpty()) return null
    return try {
      read(text)
    } catch (damaged: SerializationException) {
      null
    } catch (damaged: IllegalArgumentException) {
      null
    }
  }
}

@Serializable
internal data class ModeDto(
  val type: String,
  @SerialName("set_id") val setId: Long? = null,
  @SerialName("job_id") val jobId: String? = null,
  val label: String? = null,
  @SerialName("only_clip") val onlyClip: String? = null,
) {
  fun toMode(): FeedMode? = when (type) {
    MIX -> setId?.let { FeedMode.Mix(it) }
    IN_ORDER -> jobId?.let { FeedMode.InOrder(JobId(it)) }
    CHANGE -> label?.let { FeedMode.Change(ChangeLabel(it), onlyClip?.let(::JobId)) }
    SAVED -> FeedMode.Saved
    else -> null
  }

  companion object {
    const val MIX = "mix"
    const val IN_ORDER = "in_order"
    const val CHANGE = "change"
    const val SAVED = "saved"

    fun of(mode: FeedMode): ModeDto = when (mode) {
      is FeedMode.Mix -> ModeDto(MIX, setId = mode.setId)
      is FeedMode.InOrder -> ModeDto(IN_ORDER, jobId = mode.jobId.value)
      is FeedMode.Change -> ModeDto(CHANGE, label = mode.label.value, onlyClip = mode.onlyClip?.value)
      FeedMode.Saved -> ModeDto(SAVED)
    }
  }
}

@Serializable
internal data class WordDto(val job: String, val segment: Int, val word: Int) {
  fun toRef() = WordRef(JobId(job), segment, word)

  companion object {
    fun of(ref: WordRef) = WordDto(ref.jobId.value, ref.segment, ref.word)
  }
}

@Serializable
internal data class SpanDto(val job: String, val start: Long, val end: Long, val word: WordDto? = null) {
  fun toSpan() = ClipSpan(JobId(job), start, end, word?.toRef())

  companion object {
    fun of(span: ClipSpan) = SpanDto(span.jobId.value, span.startMs, span.endMs, span.word?.let(WordDto::of))
  }
}

@Serializable
internal data class OptionDto(val id: String, val label: String, val ipa: String? = null, val change: String? = null) {
  fun toOption() = QuestionOption(id, label, ipa, change?.let(::ChangeLabel))

  companion object {
    fun of(option: QuestionOption) = OptionDto(option.id, option.label, option.ipa, option.change?.value)
  }
}

@Serializable
internal data class OccurrenceDto(
  val word: WordDto,
  @SerialName("short_first_segment") val shortFirstSegment: Int,
  @SerialName("word_text") val wordText: String,
  @SerialName("dict_ipa") val dictIpa: String? = null,
  @SerialName("realized_ipa") val realizedIpa: String? = null,
  @SerialName("start_ms") val startMs: Long,
  @SerialName("end_ms") val endMs: Long,
  val change: String,
  @SerialName("diff_cost") val diffCost: Double,
  val advice: String,
  val family: String,
  @SerialName("crosses_boundary") val crossesBoundary: Boolean,
  @SerialName("next_word_text") val nextWordText: String? = null,
  @SerialName("clip_title") val clipTitle: String,
  @SerialName("other_changes") val otherChanges: List<String> = emptyList(),
) {
  fun toOccurrence() = Occurrence(
    word = word.toRef(),
    shortFirstSegment = shortFirstSegment,
    wordText = wordText,
    dictIpa = dictIpa,
    realizedIpa = realizedIpa,
    startMs = startMs,
    endMs = endMs,
    change = ChangeLabel(change),
    diffCost = diffCost,
    advice = Advice.entries.firstOrNull { it.name == advice } ?: Advice.UNDERSTAND,
    family = Family.entries.firstOrNull { it.name == family } ?: Family.OTHER,
    crossesBoundary = crossesBoundary,
    nextWordText = nextWordText,
    clipTitle = clipTitle,
    otherChanges = otherChanges.map(::ChangeLabel),
  )

  companion object {
    fun of(o: Occurrence) = OccurrenceDto(
      WordDto.of(o.word), o.shortFirstSegment, o.wordText, o.dictIpa, o.realizedIpa, o.startMs, o.endMs, o.change.value,
      o.diffCost, o.advice.name, o.family.name, o.crossesBoundary, o.nextWordText, o.clipTitle, o.otherChanges.map { it.value },
    )
  }
}

@Serializable
internal data class PhraseWordDto(val text: String, val changed: Boolean)

@Serializable
internal data class QuestionDto(
  val kind: String,
  val key: String,
  val prompt: String,
  val clip: SpanDto,
  val context: SpanDto? = null,
  val options: List<OptionDto>,
  val answer: String,
  val change: String,
  val occurrence: OccurrenceDto,
  val phrase: List<PhraseWordDto>? = null,
) {
  fun toQuestion() = Question(
    kind = QuestionKind.valueOf(kind),
    key = key,
    prompt = prompt,
    clip = clip.toSpan(),
    context = context?.toSpan(),
    options = options.map(OptionDto::toOption),
    answer = answer,
    change = ChangeLabel(change),
    occurrence = occurrence.toOccurrence(),
    phrase = phrase?.map { PhraseWord(it.text, it.changed) },
  )

  companion object {
    fun of(q: Question) = QuestionDto(
      q.kind.name, q.key, q.prompt, SpanDto.of(q.clip), q.context?.let(SpanDto::of), q.options.map(OptionDto::of), q.answer,
      q.change.value, OccurrenceDto.of(q.occurrence), q.phrase?.map { PhraseWordDto(it.text, it.changed) },
    )
  }
}
