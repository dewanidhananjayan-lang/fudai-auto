package com.fudai.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动参与直播间福袋的无障碍服务。
 *
 * 流程（与教程一一对应）：
 *  第一步：自动找到屏幕上悬浮的【福袋】挂件并点击
 *  第二步：在弹出的礼物弹窗中点击【发表评论后参与】
 *  第三步：在评论框填入评论（若为空）后点击【发送】
 *  第四步：自动上滑屏幕，切换到下一个直播间，循环往复
 *
 * 全部通过系统无障碍 API 实现，无需 Root。
 */
class FudaiService : AccessibilityService() {

    companion object {
        const val ACTION_START = "com.fudai.auto.START"
        const val ACTION_STOP = "com.fudai.auto.STOP"

        private const val TICK_MS = 1200L          // 主循环间隔
        private const val STEP_TIMEOUT = 12_000L   // 每个步骤的超时时间
        private const val SWIPE_DELAY = 2_500L     // 发送评论后等待多久再上滑

        // 评论内容候选（福袋一般要求发评论才能参与）
        private val COMMENT_TEXTS = arrayOf(
            "我要福袋", "参与福袋", "福袋走起", "支持主播", "好运福袋"
        )

        // 状态机
        private const val STATE_IDLE = 0          // 找福袋
        private const val STATE_BAG_CLICKED = 1   // 已点福袋，等"发表评论后参与"
        private const val STATE_JOIN_CLICKED = 2  // 已点参与，等"发送"
        private const val STATE_SENT = 3          // 已发送，准备上滑
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var state = STATE_IDLE
    private var stateTime = 0L
    private var commentIndex = 0

    /** 主界面"开始/停止"按钮通过广播通知服务 */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START -> start()
                ACTION_STOP -> stop()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        // 如果上次开启时是运行状态，则自动恢复运行
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("running", false)) {
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 不依赖事件驱动，使用定时轮询即可，逻辑更稳定
    }

    override fun onInterrupt() {}

    private fun start() {
        if (running) return
        running = true
        setState(STATE_IDLE)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        handler.postDelayed(tick, 500)
    }

    private fun stop() {
        running = false
        setState(STATE_IDLE)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        handler.removeCallbacks(tick)
    }

    /** 主循环：每 TICK_MS 毫秒根据当前状态执行一步 */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                step()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        when (state) {

            // ---------- 第一步：点击悬浮福袋挂件 ----------
            STATE_IDLE -> {
                val bag = findBagNode(root)
                if (bag != null) {
                    clickNode(bag)
                    setState(STATE_BAG_CLICKED)
                }
            }

            // ---------- 第二步：点击"发表评论后参与" ----------
            STATE_BAG_CLICKED -> {
                if (now - stateTime > STEP_TIMEOUT) {
                    setState(STATE_IDLE) // 超时重来（福袋可能已过期）
                    return
                }
                val join = findByText(root, "发表评论后参与")
                    ?: findExactClickable(root, "参与")
                if (join != null) {
                    clickNode(join)
                    setState(STATE_JOIN_CLICKED)
                }
            }

            // ---------- 第三步：填写评论并点击"发送" ----------
            STATE_JOIN_CLICKED -> {
                if (now - stateTime > STEP_TIMEOUT) {
                    setState(STATE_IDLE)
                    return
                }
                ensureCommentText(root)
                val send = findSendButton(root)
                if (send != null) {
                    clickNode(send)
                    commentIndex++
                    setState(STATE_SENT)
                }
            }

            // ---------- 第四步：上滑切换下一个直播间 ----------
            STATE_SENT -> {
                if (now - stateTime > SWIPE_DELAY) {
                    swipeToNextLive()
                    setState(STATE_IDLE)
                }
            }
        }
    }

    private fun setState(s: Int) {
        state = s
        stateTime = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------
    //                         控件查找相关
    // ------------------------------------------------------------------

    /** 第一步：找福袋挂件（文本或 content-desc 含"福袋"） */
    private fun findBagNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels
        var fallback: AccessibilityNodeInfo? = null
        for (n in root.findAccessibilityNodeInfosByText("福袋")) {
            if (!n.isVisibleToUser) continue
            val txt = (n.text ?: n.contentDescription)?.toString() ?: ""
            // 排除弹窗里的"福袋明细""福袋规则""福袋中奖"等文字节点
            if (txt.contains("明细") || txt.contains("规则") || txt.contains("中奖")
                || txt.contains("已参与") || txt.contains("待开奖")
            ) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            // 福袋挂件一般在屏幕上半部分（左上区域）
            if (r.top < screenH * 0.5f) return n
            if (fallback == null) fallback = n
        }
        return fallback
    }

    /** 模糊文本查找 */
    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            if (n.isVisibleToUser) return n
        }
        return null
    }

    /** 精确文本查找（要求本身或父级可点击） */
    private fun findExactClickable(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            if (n.isVisibleToUser && n.text?.toString() == text) return n
        }
        return null
    }

    /** 第三步：找"发送"按钮 */
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText("发送")) {
            if (n.isVisibleToUser && n.text?.toString() == "发送") return n
        }
        return null
    }

    /** 第三步：若评论输入框为空，自动填入评论内容 */
    private fun ensureCommentText(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 500) {
            guard++
            val n = queue.removeFirst() ?: continue
            if (n.className == android.widget.EditText::class.java.name) {
                val t = n.text?.toString()?.trim()
                if (t.isNullOrEmpty()) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        COMMENT_TEXTS[commentIndex % COMMENT_TEXTS.size]
                    )
                    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    //                         点击 / 滑动手势
    // ------------------------------------------------------------------

    /** 点击节点：优先走控件点击，失败则按坐标点击中心点 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** 按屏幕坐标模拟一次轻点 */
    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 第四步：从屏幕下方向上滑动，切换到下一个直播间 */
    private fun swipeToNextLive() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w / 2, h * 0.82f)
            lineTo(w / 2, h * 0.22f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
