package com.vinhkhang.blastbot

/** Logical 8x8 board. Coordinates are zero-based: (row, column). */
data class Cell(val row: Int, val col: Int)

/** Shape cells are offsets from the shape's top-left occupied cell. */
data class Piece(val id: String, val cells: List<Cell>) {
    val height: Int get() = (cells.maxOfOrNull { it.row } ?: -1) + 1
    val width: Int get() = (cells.maxOfOrNull { it.col } ?: -1) + 1
}

data class Placement(val piece: Piece, val row: Int, val col: Int, val score: Int)

object GridSolver {
    const val SIZE = 8

    // A: 2x2 square; B: L made of three cells; C: two vertical cells.
    val standardPieces = listOf(
        Piece("A", listOf(Cell(0,0), Cell(0,1), Cell(1,0), Cell(1,1))),
        Piece("B", listOf(Cell(0,0), Cell(0,1), Cell(1,0))),
        Piece("C", listOf(Cell(0,0), Cell(1,0)))
    )

    fun canPlace(board: Array<BooleanArray>, piece: Piece, row: Int, col: Int): Boolean {
        if (board.size != SIZE || board.any { it.size != SIZE }) return false
        if (piece.cells.isEmpty() || row < 0 || col < 0 ||
            row + piece.height > SIZE || col + piece.width > SIZE) return false
        return piece.cells.all { cell -> !board[row + cell.row][col + cell.col] }
    }

    /** Places a piece and clears every completed row and column. */
    fun place(board: Array<BooleanArray>, piece: Piece, row: Int, col: Int): Array<BooleanArray>? {
        if (!canPlace(board, piece, row, col)) return null
        val next = Array(SIZE) { r -> board[r].copyOf() }
        piece.cells.forEach { next[row + it.row][col + it.col] = true }

        val fullRows = (0 until SIZE).filter { r -> next[r].all { it } }.toSet()
        val fullCols = (0 until SIZE).filter { c -> (0 until SIZE).all { r -> next[r][c] } }.toSet()
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            if (r in fullRows || c in fullCols) next[r][c] = false
        }
        return next
    }

    /** Fast exhaustive search over the small 8x8 placement space. */
    fun bestPlacement(board: Array<BooleanArray>, pieces: List<Piece>): Placement? {
        var best: Placement? = null
        for (piece in pieces) {
            if (piece.cells.isEmpty() || piece.height > SIZE || piece.width > SIZE) continue
            for (r in 0..(SIZE - piece.height)) {
                for (c in 0..(SIZE - piece.width)) {
                    if (!canPlace(board, piece, r, c)) continue
                    val placed = Array(SIZE) { row -> board[row].copyOf() }
                    piece.cells.forEach { placed[r + it.row][c + it.col] = true }
                    val rows = (0 until SIZE).count { row -> placed[row].all { it } }
                    val cols = (0 until SIZE).count { col -> (0 until SIZE).all { row -> placed[row][col] } }
                    val cleared = rows + cols
                    val remaining = place(board, piece, r, c)!!.sumOf { line -> line.count { it } }
                    val score = cleared * 100 - remaining
                    if (best == null || score > best.score) best = Placement(piece, r, c, score)
                }
            }
        }
        return best
    }
}
