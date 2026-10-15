package dev.hkgill.gillspeak

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class WatchSetupActivity : Activity() {
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1000) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "Watch companion"
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String, size: Float = 17f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 20)
        }.also { col.addView(it) }
        text("Gemma on your phone. Answers on your watch.", 24f)
        text("Install the matching GillSpeak APK on your paired Galaxy Watch6. Keep it nearby, then enable a 30-minute session here. Typed watch questions run through this phone's local Gemma model. No cloud inference, microphone, phone actions, or saved question history.")
        status = text("")
        col.addView(Button(this).apply {
            text = "Enable for 30 minutes"
            setOnClickListener {
                if (LocalLlm.isReady(this@WatchSetupActivity)) {
                    runCatching { startForegroundService(Intent(this@WatchSetupActivity, WatchSessionService::class.java)) }
                        .onFailure { status.text = "Couldn't start the session. Keep this screen open and try again." }
                } else status.text = "Download Gemma first in Speech and understanding."
            }
        })
        col.addView(Button(this).apply {
            text = "Stop session"
            setOnClickListener { stopService(Intent(this@WatchSetupActivity, WatchSessionService::class.java)); render() }
        })
        text("You may lock the phone after enabling. A foreground service and a short wake lock keep an answer running; Samsung battery controls or a stopped app can still interrupt it. The watch will time out and let you retry. Sessions end automatically, on Stop, or if Android stops the app.")
        text("Type using the watch keyboard. This preview has no voice recording or system speech recognition. Keyboard dictation, if you choose it, is controlled by your keyboard provider and may use its network services.", 15f)
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
    }
    override fun onResume() { super.onResume(); main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll); super.onPause() }
    private fun render() {
        status.text = when {
            WatchSessionService.current?.isActive == true -> "Session enabled · ends automatically after 30 minutes"
            !LocalLlm.isReady(this) -> "Gemma isn't ready. Download it in Speech and understanding."
            else -> "Gemma ready · session off"
        }
    }
}
