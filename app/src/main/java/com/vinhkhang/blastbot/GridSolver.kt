package com.vinhkhang.blastbot

/** Logical 8x8 board. Coordinates are zero-based: (row, column). */
data class Cell(val row: Int, val col: Int)

/** Shape cells are offsets from the shape's top-left occupied cell. */
data class Piece(val id: String, val cells: List<Cell>) {
    val height: Int get() = (cells.maxOfOrNull { it.row } ?: -1) + 1
    val width: Int get() = (cells.maxOfOrNull { it.col } ?: -1) + 1
}

/** One planned move. pieceIndex refers to the piece's position in the input tray list. */
data class PlannedMove(
    val pieceIndex: Int,
    val piece: Piece,
    val row: Int,
    val col: Int,
    val linesCleared: Int
)

/** Complete best sequence for the currently visible tray. */
data class TurnPlan(
    val moves: List<PlannedMove>,
    val piecesPlaced: Int,
    val totalLinesCleared: Int,
    val score: Long
)

object GridSolver {
    const val SIZE = 8

    // A: 2x2 square; B: L made of three cells; C: two vertical cells.
    val standardPieces = listOf(
        Piece("A", listOf(Cell(0,0), Cell(0,1), Cell(1,0), Cell(1,1))),
        Piece("B", listOf(Cell(0,0), Cell(0,1), Cell(1,0))),
        Piece("C", listOf(Cell(0,0), Cell(1,0)))
    )

    fun canPlace(board: Array<BooleanArray>, piece: Piece, row: Int, col: Int): Boolean {
        if (!validBoard(board) || piece.cells.isEmpty() || row < 0 || col < 0 ||
            row + piece.height > SIZE || col + piece.width > SIZE) return false
        return piece.cells.all { cell -> !board[row + cell.row][col + cell.col] }
    }

    private fun validBoard(board: Array<BooleanArray>) =
        board.size == SIZE && board.all { it.size == SIZE }

    private data class Applied(
        val board: Array<BooleanArray>,
        val cleared: Int
    )

    /** Places a piece, then clears all completed rows and columns simultaneously. */
    private fun apply(board: Array<BooleanArray>, piece: Piece, row: Int, col: Int): Applied? {
        if (!canPlace(board, piece, row, col)) return null
        val next = Array(SIZE) { r -> board[r].copyOf() }
        piece.cells.forEach { next[row + it.row][col + it.col] = true }

        val fullRows = (0 until SIZE).filter { r -> next[r].all { it } }.toSet()
        val fullCols = (0 until SIZE).filter { c -> (0 until SIZE).all { r -> next[r][c] } }.toSet()
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            if (r in fullRows || c in fullCols) next[r][c] = false
        }
        return Applied(next, fullRows.size + fullCols.size)
    }

    /** Public board transition helper, useful for previews and tests. */
    fun place(board: Array<BooleanArray>, piece: Piece, row: Int, col: Int): Array<BooleanArray>? =
        apply(board, piece, row, col)?.board

    /**
     * Looks ahead through every order and legal position for the current tray (normally 3 pieces).
     * It first maximizes how many pieces can be placed, then line clears, then leaves a board
     * with fewer occupied cells and fewer isolated one-cell gaps.
     */
    fun planTurn(board: Array<BooleanArray>, pieces: List<Piece>): TurnPlan? {
        if (!validBoard(board) || pieces.isEmpty()) return null

        var best: TurnPlan? = null
        val used = BooleanArray(pieces.size)
        val path = ArrayList<PlannedMove>(pieces.size)

        fun boardQuality(state: Array<BooleanArray>): Long {
            val occupied = state.sumOf { row -> row.count { it } }
            var isolatedGaps = 0
            for (r in 0 until SIZE) for (c in 0 until SIZE) {
                if (state[r][c]) continue
                val horizontalTrap = c in 1..(SIZE - 2) && state[r][c - 1] && state[r][c + 1]
                val verticalTrap = r in 1..(SIZE - 2) && state[r - 1][c] && state[r + 1][c]
                if (horizontalTrap || verticalTrap) isolatedGaps++
            }
            return occupied.toLong() * 10L + isolatedGaps.toLong() * 35L
        }

        fun visit(state: Array<BooleanArray>, clearedSoFar: Int) {
            val candidateScore = path.size.toLong() * 1_000_000L +
                clearedSoFar.toLong() * 10_000L - boardQuality(state)
            val candidate = TurnPlan(path.toList(), path.size, clearedSoFar, candidateScore)
            if (best == null || candidate.score > best!!.score) best = candidate

            if (path.size == pieces.size) return

            for (index in pieces.indices) {
                if (used[index]) continue
                val piece = pieces[index]
                if (piece.cells.isEmpty() || piece.height > SIZE || piece.width > SIZE) continue

                for (r in 0..(SIZE - piece.height)) {
                    for (c in 0..(SIZE - piece.width)) {
                        val result = apply(state, piece, r, c) ?: continue
                        used[index] = true
                        path.add(PlannedMove(index, piece, r, c, result.cleared))
                        visit(result.board, clearedSoFar + result.cleared)
                        path.removeAt(path.lastIndex)
                        used[index] = false
                    }
                }
            }
        }

        visit(Array(SIZE) { r -> board[r].copyOf() }, 0)
        return best
    }

    /** Compatibility helper: returns the first move from the full-tray look-ahead plan. */
    fun bestPlacement(board: Array<BooleanArray>, pieces: List<Piece>): PlannedMove? =
        planTurn(board, pieces)?.moves?.firstOrNull()
}
