package com.vinhkhang.blastbot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

data class ScreenBoard(
    val occupied: Array<BooleanArray>,
    val left: Float,
    val top: Float,
    val cell: Float
)

data class TrayPiece(
    val index: Int,
    val piece: Piece,
    val sourceX: Float,
    val sourceY: Float
)

data class GameFrame(
    val board: ScreenBoard?,
    val pieces: List<TrayPiece>,
    val gameOver: Boolean
)

/**
 * Lightweight color/grid reader for the Block Blast layout shown in the project screenshots.
 * Coordinates are normalized against the full screenshot, so different screen resolutions work.
 * It deliberately returns no move when the board/tray cannot be recognized confidently.
 */
object ScreenAnalyzer {
    fun analyze(bitmap: Bitmap): GameFrame {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w < 300f || h < 600f) return GameFrame(null, emptyList(), false)

        if (looksLikeGameOver(bitmap)) return GameFrame(null, emptyList(), true)

        // Reference layout: board occupies about 5.2%-91.7% width and 22.9%-61.8% height.
        val left = w * 0.052f
        val right = w * 0.917f
        val top = h * 0.229f
        val bottom = h * 0.618f
        val cell = min((right - left) / 8f, (bottom - top) / 8f)
        if (cell < 12f) return GameFrame(null, emptyList(), false)

        val boardCells = Array(8) { BooleanArray(8) }
        var sampled = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val x = (left + (c + 0.5f) * cell).toInt().coerceIn(0, bitmap.width - 1)
            val y = (top + (r + 0.5f) * cell).toInt().coerceIn(0, bitmap.height - 1)
            boardCells[r][c] = isBlockPixel(bitmap.getPixel(x, y))
            sampled++
        }
        if (sampled != 64) return GameFrame(null, emptyList(), false)

        val tray = ArrayList<TrayPiece>()
        val trayCell = cell * 0.46f
        val centers = floatArrayOf(w * 0.20f, w * 0.48f, w * 0.765f)
        for (index in centers.indices) {
            data class Candidate(val cells: List<Cell>, val points: List<Pair<Float, Float>>)
            var best: Candidate? = null
            // Even-sized pieces have half-cell offsets from their center; odd-sized pieces use whole cells.
            for (phaseY in listOf(-0.5f, 0f)) for (phaseX in listOf(-0.5f, 0f)) {
                val cells = ArrayList<Cell>()
                val points = ArrayList<Pair<Float, Float>>()
                for (rr in 0..4) for (cc in 0..4) {
                    val x = centers[index] + (cc - 2 + phaseX) * trayCell
                    val y = h * 0.728f + (rr - 2 + phaseY) * trayCell
                    if (x < 0 || y < 0 || x >= w || y >= h) continue
                    if (isBlockPixel(bitmap.getPixel(x.toInt(), y.toInt()))) {
                        cells.add(Cell(rr, cc))
                        points.add(x to y)
                    }
                }
                if (cells.size !in 1..9 || !isConnected(cells)) continue
                if (best == null || cells.size > best!!.cells.size) {
                    best = Candidate(cells, points)
                }
            }
            val candidate = best ?: continue
            val minR = candidate.cells.minOf { it.row }
            val minC = candidate.cells.minOf { it.col }
            val normalized = candidate.cells.map { Cell(it.row - minR, it.col - minC) }
            val piece = Piece("P$index", normalized)
            val sx = candidate.points.map { it.first }.average().toFloat()
            val sy = candidate.points.map { it.second }.average().toFloat()
            tray.add(TrayPiece(index, piece, sx, sy))
        }
        return GameFrame(ScreenBoard(boardCells, left, top, cell), tray, false)
    }

    private fun isBlockPixel(pixel: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        // Empty board tiles are dark navy; colored blocks are brighter and saturated.
        return hsv[2] > 0.48f && hsv[1] > 0.42f
    }

    private fun looksLikeGameOver(bitmap: Bitmap): Boolean {
        val hsv = FloatArray(3)
        var purple = 0
        var count = 0
        // Sample the central field, avoiding status/navigation bars.
        for (yi in 0..8) for (xi in 0..6) {
            val x = (bitmap.width * (0.12f + xi * 0.12f)).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * (0.16f + yi * 0.075f)).toInt().coerceIn(0, bitmap.height - 1)
            Color.colorToHSV(bitmap.getPixel(x, y), hsv)
            if (hsv[0] in 255f..310f && hsv[1] > 0.35f && hsv[2] > 0.25f) purple++
            count++
        }
        return count > 0 && purple.toFloat() / count > 0.55f
    }

    private fun isConnected(cells: List<Cell>): Boolean {
        if (cells.isEmpty()) return false
        val remaining = cells.toMutableSet()
        val queue = ArrayDeque<Cell>()
        queue.add(remaining.first())
        remaining.remove(queue.first())
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val neighbors = listOf(
                Cell(current.row - 1, current.col), Cell(current.row + 1, current.col),
                Cell(current.row, current.col - 1), Cell(current.row, current.col + 1)
            )
            for (next in neighbors) if (remaining.remove(next)) queue.add(next)
        }
        return remaining.isEmpty()
    }
}
