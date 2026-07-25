package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.RouteActivity

/**
 * 透明中转 Activity: 接收外部 ACTION_SEND/SEND_MULTIPLE/PROCESS_TEXT,
 * 然后将 Intent 原样转发给 RouteActivity (singleTask), 自身 finish。
 * 
 * 解决 WPS 等应用发送分享 Intent 时绕过 singleTask 创建多实例的问题。
 * RouteActivity 不直接注册 intent-filter, 由本 Activity 统一接收后转发。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finish(); return }
        
        // 克隆原始 Intent 并重定向到 RouteActivity
        val forward = Intent(this, RouteActivity::class.java).apply {
            action = intent.action
            type = intent.type
            // 复制所有 extras
            intent.extras?.let { putExtras(it) }
            // 复制 data/clipData
            data = intent.data
            clipData = intent.clipData
            // 关键 Flag: 清除当前任务栈中的 ShareReceiver, 确保 singleTask 复用
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        startActivity(forward)
        finish()
    }
}
