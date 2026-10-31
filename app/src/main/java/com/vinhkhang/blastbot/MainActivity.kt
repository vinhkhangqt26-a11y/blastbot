package com.vinhkhang.blastbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28)
        }
        val title = TextView(this).apply {
            text = "BlastBot"
            textSize = 30f
            setTextColor(0xFF18243A.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Trợ lý Block Blast • bàn cờ 8×8"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
        }
        val enable = Button(this).apply {
            text = "Mở cài đặt Trợ năng"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val refresh = Button(this).apply {
            text = "Kiểm tra kết nối"
            setOnClickListener { refreshStatus() }
        }
        val instructions = TextView(this).apply {
            text = "Sau khi bật dịch vụ BlastBot trong Trợ năng, nút BOT sẽ nổi trên màn hình. Chạm nút để bật hoặc tắt chế độ. Bản này đã có mô hình tọa độ ô và mẫu khối A/B/C; nhận diện hình ảnh và tự kéo quân vẫn đang được phát triển."
            textSize = 14f
            setPadding(4, 24, 4, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(enable, LinearLayout.LayoutParams(-1, -2))
        root.addView(refresh, LinearLayout.LayoutParams(-1, -2))
        root.addView(instructions, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun refreshStatus() {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val connected = enabled.split(':').any {
            it.endsWith("BlastAccessibilityService", ignoreCase = true)
        }
        status.text = if (connected) "Trợ năng: ĐÃ BẬT" else "Trợ năng: CHƯA BẬT"
    }
}
