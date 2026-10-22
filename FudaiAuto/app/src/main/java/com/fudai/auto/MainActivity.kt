package com.fudai.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：
 *  1. 引导用户去系统设置开启无障碍服务（免 Root 权限来源）
 *  2. 开始 / 停止自动参与
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先在无障碍设置中启用【自动福袋助手】", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            sendBroadcast(Intent(FudaiService.ACTION_START).setPackage(packageName))
            status.text = "当前状态：运行中 ✅\n请打开任意直播间，将自动循环参与福袋"
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendBroadcast(Intent(FudaiService.ACTION_STOP).setPackage(packageName))
            status.text = "当前状态：已停止 ⏸"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s?.contains(packageName) == true
    }
}
