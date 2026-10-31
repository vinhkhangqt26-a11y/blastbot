package com.vinhkhang.blastbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import java.util.concurrent.Executor

class BlastAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null
    private var botEnabled = false
    private var busy = false
    private var pendingBoard: Array<BooleanArray>? = null
    private var candidateBoard: Array<BooleanArray>? = null
    private var confirmationAttempts = 0
    private var screenshotInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
        val button = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                setBotEnabled(!botEnabled)
            }
        }
        floatingButton = button
        updateButton()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 220
        }
        try {
            windowManager?.addView(button, params)
        } catch (_: Exception) {
            floatingButton = null
        }
    }

    private fun setBotEnabled(enabled: Boolean) {
        botEnabled = enabled
        busy = false
        pendingBoard = null
        candidateBoard = null
        confirmationAttempts = 0
        updateButton()
        if (enabled) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                botEnabled = false
                updateButton()
                toast("Cần Android 11 trở lên để đọc màn hình an toàn.")
                return
            }
            toast("BOT bật: đang nhận diện bàn cờ.")
            handler.postDelayed({ captureAndProcess() }, 700L)
        } else {
            toast("BlastBot đã tắt.")
        }
    }

    private fun updateButton() {
        floatingButton?.apply {
            text = if (botEnabled) "BOT: BẬT" else "BOT: TẮT"
            alpha = if (botEnabled) 1.0f else 0.82f
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun captureAndProcess() {
        if (!botEnabled || busy || screenshotInFlight) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            stopForSafety("Thiết bị chưa hỗ trợ chụp màn hình.")
            return
        }
        screenshotInFlight = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        screenshotInFlight = false
                        val hardwareBuffer: HardwareBuffer = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        wrapped?.recycle()
                        if (bitmap == null) {
                            stopForSafety("Không đọc được ảnh màn hình.")
                            return
                        }
                        try {
                            processFrame(bitmap)
                        } catch (_: Exception) {
                            stopForSafety("Không phân tích được màn hình. BOT đã dừng.")
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotInFlight = false
                        stopForSafety("Không chụp được màn hình (mã $errorCode).")
                    }
                })
        } catch (_: Exception) {
            screenshotInFlight = false
            stopForSafety("Thiết bị không cho phép chụp màn hình.")
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!botEnabled) return
        val frame = ScreenAnalyzer.analyze(bitmap)
        if (frame.gameOver) {
            stopForSafety("Đã nhận diện màn hình kết thúc. BOT đã dừng.")
            return
        }
        val screenBoard = frame.board
        if (screenBoard == null || frame.pieces.isEmpty()) {
            stopForSafety("Chưa nhận diện chắc chắn bàn cờ hoặc 3 khối. BOT tạm dừng để tránh kéo sai.")
            return
        }

        val current = screenBoard.occupied
        val previous = pendingBoard
        if (previous != null) {
            val candidate = candidateBoard
            if (candidate == null) {
                if (boardsEqual(previous, current)) {
                    confirmationAttempts++
                    if (confirmationAttempts >= 4) {
                        stopForSafety("Quân chưa được xác nhận đã đặt. BOT dừng, không kéo quân tiếp.")
                        return
                    }
                    handler.postDelayed({ captureAndProcess() }, 500L)
                    return
                }
                candidateBoard = copyBoard(current)
                handler.postDelayed({ captureAndProcess() }, 500L)
                return
            } else {
                if (!boardsEqual(candidate, current)) {
                    candidateBoard = copyBoard(current)
                    confirmationAttempts++
                    if (confirmationAttempts >= 4) {
                        stopForSafety("Bàn cờ chưa ổn định sau khi thả quân. BOT đã dừng.")
                        return
                    }
                    handler.postDelayed({ captureAndProcess() }, 500L)
                    return
                }
                // Two consecutive screenshots show the same changed board: placement confirmed.
                pendingBoard = null
                candidateBoard = null
                confirmationAttempts = 0
            }
        }

        val pieces = frame.pieces.sortedBy { it.index }
        val plan = GridSolver.planTurn(current, pieces.map { it.piece })
        val nextMove = plan?.moves?.firstOrNull()
        if (nextMove == null) {
            stopForSafety("Không tìm thấy nước đi hợp lệ.")
            return
        }
        val trayPiece = pieces.getOrNull(nextMove.pieceIndex)
        if (trayPiece == null) {
            stopForSafety("Không xác định được khối cần kéo.")
            return
        }

        val targetX = screenBoard.left + (nextMove.col + trayPiece.piece.width / 2f) * screenBoard.cell
        val targetY = screenBoard.top + (nextMove.row + trayPiece.piece.height / 2f) * screenBoard.cell
        val path = Path().apply {
            moveTo(trayPiece.sourceX, trayPiece.sourceY)
            lineTo(targetX, targetY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 500L))
            .build()

        busy = true
        pendingBoard = copyBoard(current)
        candidateBoard = null
        confirmationAttempts = 0
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                busy = false
                if (botEnabled) handler.postDelayed({ captureAndProcess() }, 850L)
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                busy = false
                stopForSafety("Thao tác kéo bị hủy. BOT đã dừng.")
            }
        }, handler)
        if (!dispatched) {
            busy = false
            stopForSafety("Android từ chối thao tác kéo. BOT đã dừng.")
        }
    }

    private fun copyBoard(board: Array<BooleanArray>) =
        Array(board.size) { row -> board[row].copyOf() }

    private fun boardsEqual(a: Array<BooleanArray>, b: Array<BooleanArray>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { a[it].contentEquals(b[it]) }
    }

    private fun stopForSafety(message: String) {
        botEnabled = false
        busy = false
        pendingBoard = null
        candidateBoard = null
        screenshotInFlight = false
        updateButton()
        toast(message)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        setBotEnabled(false)
    }

    override fun onDestroy() {
        botEnabled = false
        handler.removeCallbacksAndMessages(null)
        floatingButton?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) { }
        }
        floatingButton = null
        super.onDestroy()
    }
}
