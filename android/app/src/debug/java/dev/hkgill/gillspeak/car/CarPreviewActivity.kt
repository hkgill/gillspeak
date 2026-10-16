package dev.hkgill.gillspeak.car

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import dev.hkgill.gillspeak.R
import java.util.concurrent.Executors

/** Launchable, synthetic-only preview. No permissions, models, network or real microphone are used here. */
class CarPreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var controller: CarController
    private lateinit var content: LinearLayout
    private var scenario = 0
    private val scenarios = listOf("Ready · synthetic audio/text", "Speech model missing", "Microphone permission missing", "Host API below 5")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom)
                insets
            }
        }
        root.addView(TextView(this).apply {
            setText(R.string.car_preview_notice)
            textSize = 16f
        })
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@CarPreviewActivity, android.R.layout.simple_spinner_dropdown_item, scenarios)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    scenario = position
                    if (::controller.isInitialized) controller.cancel("Fixture changed. Tap Speak to test it.")
                }
            }
        }
        root.addView(picker)
        val links = LinearLayout(this)
        links.addView(Button(this).apply { setText(R.string.car_preview_disconnect); setOnClickListener { controller.disconnect() } })
        links.addView(Button(this).apply { setText(R.string.car_preview_reconnect); setOnClickListener { controller.reconnect() } })
        root.addView(links)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply { addView(root) })
        controller = CarController(worker, { handler.post(it) }, { _ -> FixtureAudio() }, object : CarInference {
            override fun transcribe(wav: ByteArray): String {
                Thread.sleep(800) // leave time to exercise Cancel during synthetic processing
                return "This is a synthetic speech preview."
            }
            override fun polish(text: String, owner: Any, cancelled: () -> Boolean) =
                CarReply(text, "Fixture · neither ASR nor Gemma ran.")
            override fun cancel(owner: Any) = Unit
        }, {
            when (scenario) {
                1 -> "Speech model missing. Download on the phone only when parked."
                2 -> "Microphone permission missing. Set up on the phone only when parked."
                3 -> "Car microphone needs host API 5 or later."
                else -> null
            }
        }, { delay, action ->
            val task = Runnable(action)
            handler.postDelayed(task, delay)
            val remove = { handler.removeCallbacks(task) }
            remove
        }, { render(it) })
        render(controller.state)
    }

    private fun render(state: CarState) {
        val template = carTemplate(state, controller::speak, controller::finish, { controller.cancel() })
        content.removeAllViews()
        content.addView(TextView(this).apply { text = template.title?.toCharSequence(); textSize = 24f })
        content.addView(TextView(this).apply { text = template.message.toCharSequence(); textSize = 22f })
        template.actions.forEach { action ->
            val label = action.title?.toCharSequence().toString()
            content.addView(Button(this).apply {
                text = label
                minHeight = 72
                setOnClickListener {
                    when (label) {
                        "Speak" -> controller.speak()
                        "Finish" -> controller.finish()
                        else -> controller.cancel()
                    }
                }
            })
        }
    }

    override fun onStart() { super.onStart(); controller.reconnect() }
    override fun onStop() { controller.disconnect(); super.onStop() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

private class FixtureAudio : CarAudioInput {
    @Volatile private var stopped = false
    override fun start() = Unit
    override fun read(buffer: ByteArray): Int {
        Thread.sleep(16)
        if (stopped) return -1
        // Deliberately synthetic non-speech PCM, never handed to a real model.
        buffer.indices.forEach { buffer[it] = if (it % 2 == 0) 1 else 0 }
        return buffer.size
    }
    override fun stop() { stopped = true }
}
