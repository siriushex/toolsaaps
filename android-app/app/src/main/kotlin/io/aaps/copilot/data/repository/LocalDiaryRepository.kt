package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.ZoneId

enum class DiaryKind { MEAL, INJECTION }
enum class DiaryInsulinKind { RAPID, LONG, OTHER }
enum class DiaryError { AMOUNT, TIME, INSULIN, NOTE, INVALID, CONFLICT, STORAGE }

data class DiaryDraft(
    val entryId: String,
    val expectedRevision: Int = 0,
    val kind: DiaryKind,
    val occurredAtMs: Long,
    val zoneId: String,
    val amount: String = "",
    val insulinName: String = "",
    val insulinKind: DiaryInsulinKind? = null,
    val note: String = "",
    val deleted: Boolean = false
)

sealed interface DiarySaveResult {
    data class Saved(val entry: LocalDiaryEntryEntity) : DiarySaveResult
    data class Rejected(val reason: DiaryError) : DiarySaveResult
}

interface LocalDiaryStore {
    fun observe(deleted: Boolean, limit: Int): Flow<List<LocalDiaryEntryEntity>>
    suspend fun save(draft: DiaryDraft): DiarySaveResult
    suspend fun history(id: String): List<LocalDiaryEntryEntity>
}

fun parseDiaryAmount(text: String): Long? {
    val normalized = text.trim().replace(',', '.')
    if (!Regex("[0-9]{1,4}(\\.[0-9]{1,3})?").matches(normalized)) return null
    val number = normalized.toBigDecimalOrNull() ?: return null
    if (number <= BigDecimal.ZERO || number > BigDecimal(1000)) return null
    return number.movePointRight(3).longValueExact()
}

fun diaryAmountText(milli: Long): String = BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString()

fun LocalDiaryEntryEntity.toDiaryDraft(): DiaryDraft = DiaryDraft(
    entryId, revision, DiaryKind.valueOf(kind), occurredAtMs, recordedZoneId,
    diaryAmountText(amountMilli), insulinName,
    insulinKind.takeIf { it.isNotEmpty() }?.let(DiaryInsulinKind::valueOf), note, deleted
)

/** A separate journal: no treatment import, network, scheduling or clinical consumer. */
class LocalDiaryRepository(
    private val db: CopilotDatabase,
    private val now: () -> Long = System::currentTimeMillis
) : LocalDiaryStore {
    override fun observe(deleted: Boolean, limit: Int) = db.localDiaryDao()
        .observeLatest(deleted, limit.coerceIn(1, 1000)).map(::checkedRows)
    override suspend fun history(id: String) = checkedRows(db.localDiaryDao().history(id))

    private fun checkedRows(rows: List<LocalDiaryEntryEntity>): List<LocalDiaryEntryEntity> {
        require(rows.all { row ->
            row.source == "MANUAL_LOCAL" && row.revision > 0 && row.occurredAtMs > 0 &&
                row.recordedAtMs > 0 && row.amountMilli in 1..1_000_000 && row.note.length <= 500 &&
                runCatching { ZoneId.of(row.recordedZoneId) }.isSuccess && when (row.kind) {
                    DiaryKind.MEAL.name -> row.insulinName.isEmpty() && row.insulinKind.isEmpty()
                    DiaryKind.INJECTION.name -> row.insulinName.isNotBlank() && row.insulinName.length <= 100 &&
                        DiaryInsulinKind.entries.any { it.name == row.insulinKind }
                    else -> false
                }
        }) { "Invalid local diary metadata" }
        return rows
    }

    override suspend fun save(draft: DiaryDraft): DiarySaveResult {
        val clock = now()
        val amount = parseDiaryAmount(draft.amount) ?: return DiarySaveResult.Rejected(DiaryError.AMOUNT)
        if (draft.occurredAtMs <= 0 || draft.occurredAtMs > clock) return DiarySaveResult.Rejected(DiaryError.TIME)
        if (!Regex("[a-zA-Z0-9-]{1,64}").matches(draft.entryId) ||
            draft.expectedRevision !in 0 until Int.MAX_VALUE ||
            runCatching { ZoneId.of(draft.zoneId) }.isFailure) return DiarySaveResult.Rejected(DiaryError.INVALID)
        if (draft.note.length > 500) return DiarySaveResult.Rejected(DiaryError.NOTE)
        if (draft.kind == DiaryKind.INJECTION && (draft.insulinName.isBlank() ||
                draft.insulinName.length > 100 || draft.insulinKind == null)) return DiarySaveResult.Rejected(DiaryError.INSULIN)
        if (draft.kind == DiaryKind.MEAL && (draft.insulinName.isNotEmpty() || draft.insulinKind != null)) {
            return DiarySaveResult.Rejected(DiaryError.INVALID)
        }
        val entry = LocalDiaryEntryEntity(draft.entryId, draft.expectedRevision + 1, draft.kind.name,
            draft.occurredAtMs, draft.zoneId, amount, draft.insulinName.trim(), draft.insulinKind?.name.orEmpty(),
            draft.note.trim(), draft.deleted, clock)
        return db.withTransaction {
            val latest = db.localDiaryDao().latest(draft.entryId)
            // A lost commit acknowledgement may be retried with the same identity and content.
            if (latest != null && latest == entry.copy(recordedAtMs = latest.recordedAtMs)) {
                return@withTransaction DiarySaveResult.Saved(latest)
            }
            if ((latest?.revision ?: 0) != draft.expectedRevision ||
                (latest == null && draft.deleted) || (latest != null && latest.kind != entry.kind)) {
                return@withTransaction DiarySaveResult.Rejected(DiaryError.CONFLICT)
            }
            db.localDiaryDao().insert(entry)
            DiarySaveResult.Saved(entry)
        }
    }
}
