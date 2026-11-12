package com.kejing.puzzlebox.domain

enum class Language { SYSTEM, ZH, EN }

/** App settings (design dataModel.settings) + the first-run flag and the last box played. */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    /** Volume stop 0 / 1 / 2 = 低 / 中 / 高. */
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val firstRunDone: Boolean = false,
)

/** Per-box record (design dataModel.records). Best time / moves only count games without hints or help. */
data class Record(val done: Boolean = false, val bestMs: Long? = null, val bestMoves: Int? = null)

data class Records(val boxes: Map<String, Record> = emptyMap(), val somaWays: Set<String> = emptySet()) {
    fun of(id: String) = boxes[id] ?: Record()
}

enum class Where { TRAY, STAND, BOX }

/** One block of the current box: where it is, its orientation (index into Orient.ROTS) and, in the box, its cells. */
data class PieceState(val type: PieceType, val where: Where, val rot: Int, val cells: List<Cell> = emptyList())

/** One undoable step (design gameSpec.steps): a drop or a take-out. */
sealed interface UndoEntry {
    val piece: Int
    data class Drop(override val piece: Int, val cells: List<Cell>, val rot: Int) : UndoEntry
    data class TakeOut(override val piece: Int, val cells: List<Cell>, val rot: Int) : UndoEntry
}

/** What a game looks like on disk (files/game.txt, design dataModel.save). */
data class SavedGame(
    val levelId: String,
    val pieces: List<PieceState>,
    val selected: Int?,
    val moves: Int,
    val elapsedMs: Long,
    val usedHint: Boolean,
    val usedHelp: Boolean,
    val hintOn: Boolean,
    val undo: List<UndoEntry>,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/** `pb1|level|t:w:r:cells,...|selected|moves|elapsed|hint|help|hintOn|undo` (cells "x.y.z/x.y.z"). */
object SaveCodec {
    private fun cells(cs: List<Cell>) = cs.joinToString("/") { "${it.x}.${it.y}.${it.z}" }
    private fun cellsOf(s: String): List<Cell> = if (s.isBlank()) emptyList() else s.split("/").map { val f = it.split("."); Cell(f[0].toInt(), f[1].toInt(), f[2].toInt()) }

    fun encode(g: SavedGame): String = listOf(
        "pb1", g.levelId,
        g.pieces.joinToString(",") { "${it.type.name}:${it.where.name}:${it.rot}:${cells(it.cells)}" },
        g.selected?.toString() ?: "-", g.moves.toString(), g.elapsedMs.toString(),
        if (g.usedHint) "1" else "0", if (g.usedHelp) "1" else "0", if (g.hintOn) "1" else "0",
        g.undo.joinToString(",") { e ->
            when (e) {
                is UndoEntry.Drop -> "D:${e.piece}:${e.rot}:${cells(e.cells)}"
                is UndoEntry.TakeOut -> "T:${e.piece}:${e.rot}:${cells(e.cells)}"
            }
        },
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size != 10 || f[0] != "pb1") return null
        val level = Levels.byId(f[1]) ?: return null
        val pieces = f[2].split(",").map { p ->
            val q = p.split(":")
            PieceState(PieceType.valueOf(q[0]), Where.valueOf(q[1]), q[2].toInt().also { require(it in 0..23) }, cellsOf(q.getOrElse(3) { "" }))
        }
        require(pieces.map { it.type } == level.pieces)
        val undo = if (f[9].isBlank()) emptyList() else f[9].split(",").map { e ->
            val q = e.split(":")
            when (q[0]) {
                "D" -> UndoEntry.Drop(q[1].toInt(), cellsOf(q[3]), q[2].toInt())
                else -> UndoEntry.TakeOut(q[1].toInt(), cellsOf(q[3]), q[2].toInt())
            }
        }
        val sel = f[3].toIntOrNull()
        // the box cells must be a legal arrangement of this box
        val grid = BoxGrid(level.dims)
        pieces.forEachIndexed { i, p ->
            if (p.where == Where.BOX) { require(Orient.normalize(p.cells) == Orient.cells(p.type, p.rot)); grid.put(i, p.cells) }
        }
        require(pieces.count { it.where == Where.STAND } <= 1)
        require(sel == null || pieces[sel].where == Where.STAND)
        SavedGame(level.id, pieces, sel, f[4].toInt(), f[5].toLong(), f[6] == "1", f[7] == "1", f[8] == "1", undo)
    }.getOrNull()
}
