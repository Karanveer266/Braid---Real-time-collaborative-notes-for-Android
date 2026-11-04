package dev.braid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dev.braid.crdt.Op
import dev.braid.crdt.OpCodec
import dev.braid.crdt.VersionVector

/**
 * One row per operation.
 *
 * - Primary key `(author, seq)` makes inserts naturally idempotent (`IGNORE` on conflict) and
 *   gives an index for the sync query "ops of author X with seq > n".
 * - `(ts_millis, ts_counter, author)` index serves the HLC-ordered replay.
 * - The payload is an opaque protobuf BLOB: the schema of *ops* evolves through protobuf field
 *   numbers, so adding an op type never requires a Room migration.
 */
@Entity(
    tableName = "ops",
    primaryKeys = ["author", "seq"],
    indices = [Index(value = ["ts_millis", "ts_counter", "author"])],
)
data class OpEntity(
    val author: Long,
    val seq: Long,
    @ColumnInfo(name = "ts_millis") val tsMillis: Long,
    @ColumnInfo(name = "ts_counter") val tsCounter: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    fun toOp(): Op = OpCodec.decode(payload)

    override fun equals(other: Any?) = other is OpEntity && author == other.author && seq == other.seq
    override fun hashCode() = (author * 31 + seq).hashCode()

    companion object {
        fun from(op: Op) = OpEntity(op.author.raw, op.seq, op.id.millis, op.id.counter, OpCodec.encode(op))
    }
}

data class AuthorMax(val author: Long, val maxSeq: Long)

@Dao
interface OpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ops: List<OpEntity>)

    @Query("SELECT * FROM ops ORDER BY ts_millis, ts_counter, author LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<OpEntity>

    @Query("SELECT * FROM ops WHERE author = :author AND seq > :afterSeq ORDER BY seq")
    suspend fun byAuthorAfter(author: Long, afterSeq: Long): List<OpEntity>

    @Query("SELECT author, MAX(seq) AS maxSeq FROM ops GROUP BY author")
    suspend fun authors(): List<AuthorMax>

    @Query("SELECT COUNT(*) FROM ops")
    suspend fun count(): Long

    @Query("DELETE FROM ops")
    suspend fun clear()
}

@Database(entities = [OpEntity::class], version = 1, exportSchema = true)
abstract class BraidDatabase : RoomDatabase() {
    abstract fun ops(): OpDao
}

/** Room-backed [OpLog]. */
class RoomOpLog(private val db: BraidDatabase) : OpLog {
    private val dao = db.ops()

    override suspend fun append(ops: List<Op>) = dao.insertAll(ops.map(OpEntity::from))

    override suspend fun loadAll(): List<Op> {
        // Paged to bound peak memory of CursorWindow-backed reads on large logs.
        val out = ArrayList<Op>()
        var offset = 0
        while (true) {
            val page = dao.page(PAGE, offset)
            page.mapTo(out) { it.toOp() }
            if (page.size < PAGE) return out
            offset += PAGE
        }
    }

    /**
     * One read transaction so the per-author queries see a single consistent snapshot (WAL
     * readers never block the writer). Uses the (author, seq) primary key index: cost is
     * O(replicas + delta), independent of the total log size.
     */
    override suspend fun opsMissingFrom(remote: VersionVector): List<Op> = db.withTransaction {
        val out = ArrayList<Op>()
        for (a in dao.authors()) {
            val known = remote[dev.braid.crdt.ReplicaId(a.author)]
            if (a.maxSeq > known) dao.byAuthorAfter(a.author, known).mapTo(out) { it.toOp() }
        }
        out.sortBy { it.id }
        out
    }

    override suspend fun count(): Long = dao.count()
    override suspend fun clear() = dao.clear()

    private companion object {
        const val PAGE = 5_000
    }
}

/** One SQLite file per workspace: leaving a workspace is a file delete, and logs never mix. */
fun openWorkspaceDatabase(context: android.content.Context, workspaceId: String): BraidDatabase =
    androidx.room.Room.databaseBuilder(context.applicationContext, BraidDatabase::class.java, "ws-$workspaceId.db")
        // WAL: the sync reader (opsMissingFrom) never blocks the group-commit writer and vice versa.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

fun deleteWorkspaceDatabase(context: android.content.Context, workspaceId: String) {
    context.applicationContext.deleteDatabase("ws-$workspaceId.db")
}
