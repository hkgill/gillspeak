package dev.hkgill.gillspeak.watch

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.hkgill.gillspeak.wear.PendingRequest
import dev.hkgill.gillspeak.wear.Protocol

/** No SpeechRecognizer or microphone permission: typed questions only. */
class WatchActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var question: EditText
    private lateinit var ask: Button
    private lateinit var check: Button
    private lateinit var cancel: Button
    private lateinit var scroll: ScrollView
    private var pending: PendingRequest? = null
    private var discovery: String? = null
    private var visible = false
    private val timeout = Runnable { end("Phone took too long. Keep it nearby, enable its watch session, then try again.", true) }
    private val discoveryTimeout = Runnable { end("Couldn't find your phone. Check Bluetooth and the matching phone app.", false) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        actionBar?.hide()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(36), dp(26), dp(40))
        }
        fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        label("GillSpeak", 22f, Color.rgb(135, 244, 206))
        label("Your phone's local Gemma", 12f, Color.LTGRAY)
        status = label(state?.getString("status") ?: "Enable Watch companion in GillSpeak on your phone.", 14f)
        answer = label(state?.getString("answer") ?: "", 17f).apply { setTextIsSelectable(true) }
        question = EditText(this).apply {
            hint = "Type a question"
            textSize = 16f
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(Protocol.MAX_QUESTION))
            minLines = 2; maxLines = 4
            setText(state?.getString("question") ?: "")
        }
        col.addView(question, LinearLayout.LayoutParams(-1, -2))
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.rgb(30, 59, 49)) }
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }) }
        ask = button("Ask phone") {
            val text = question.text.toString().trim()
            if (text.isEmpty()) status.text = "Type a question first." else connect(Protocol.Kind.ASK, text)
        }
        cancel = button("Cancel") { end("Cancelled. You can ask another question.", true) }.apply { visibility = View.GONE }
        check = button("Check phone") { connect(Protocol.Kind.PING, "") }
        button("Example question") { question.setText("Why does the Moon have phases?") }
        label("Type with your keyboard. Keyboard voice input may use its provider's cloud. GillSpeak does not record audio.", 11f, Color.GRAY)
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK); isFillViewport = true; addView(col)
            isFocusable = true; isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action == MotionEvent.ACTION_SCROLL) {
                    scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(32)).toInt()); true
                } else false
            }
        }
        setContentView(scroll)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        scroll.requestFocus()
    }

    override fun onStart() {
        super.onStart(); visible = true
        Wearable.getMessageClient(this).addListener(this).addOnFailureListener {
            if (visible) status.text = "Wear OS connection unavailable. Check Google Play services."
        }
    }
    override fun onStop() {
        visible = false
        // Results are deliberately ephemeral. Leaving the app cancels, including display timeout.
        if (pending != null || discovery != null) end("Cancelled after leaving GillSpeak. Ask again when ready.", true)
        Wearable.getMessageClient(this).removeListener(this)
        super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("question", question.text.toString())
        out.putString("answer", answer.text.toString())
        out.putString("status", if (pending != null || discovery != null) "Previous request ended. Send it again." else status.text.toString())
    }

    private fun connect(kind: Protocol.Kind, text: String) {
        if (pending != null || discovery != null) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(question.windowToken, 0)
        question.clearFocus()
        val id = Protocol.id()
        discovery = id
        busy(true)
        answer.text = ""
        status.text = "Finding paired phone…"
        scroll.smoothScrollTo(0, 0)
        main.postDelayed(discoveryTimeout, Protocol.CONNECT_TIMEOUT_MS)
        Wearable.getCapabilityClient(this).getCapability(Protocol.PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                if (!visible || discovery != id) return@addOnSuccessListener
                val phones = capability.nodes.filter { it.isNearby }
                if (phones.size != 1) {
                    end(if (phones.isEmpty()) "Phone not nearby. Check Bluetooth and install the matching GillSpeak phone update."
                        else "More than one phone found. Keep only your paired phone nearby.", false)
                    return@addOnSuccessListener
                }
                discovery = null
                main.removeCallbacks(discoveryTimeout)
                val node = phones.single().id
                pending = PendingRequest(node, id, SystemClock.elapsedRealtime() + Protocol.TIMEOUT_MS)
                main.postDelayed(timeout, Protocol.TIMEOUT_MS)
                status.text = "Waiting for phone…"
                Wearable.getMessageClient(this).sendMessage(node, Protocol.REQUEST_PATH,
                    Protocol.encode(Protocol.Packet(id, kind, text)))
                    .addOnFailureListener {
                        if (pending?.id == id) end("Couldn't reach phone. Reconnect, then try again.", true)
                    }
            }.addOnFailureListener {
                if (discovery == id) end("Can't connect to phone. Check pairing and Google Play services.", false)
            }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.REPLY_PATH) return
        val packet = Protocol.decode(event.data) ?: return
        main.post {
            if (!visible || pending?.accept(event.sourceNodeId, packet, SystemClock.elapsedRealtime()) != true) return@post
            if (packet.kind == Protocol.Kind.STATUS) status.text = packet.text
            else {
                pending = null
                main.removeCallbacks(timeout)
                busy(false)
                status.text = if (packet.kind == Protocol.Kind.RESULT) "From your phone" else "Couldn't answer"
                answer.text = packet.text
                scroll.smoothScrollTo(0, 0)
            }
        }
    }
    private fun end(message: String, sendCancel: Boolean) {
        pending?.let { request ->
            request.cancel()
            if (sendCancel) Wearable.getMessageClient(this).sendMessage(request.node, Protocol.REQUEST_PATH,
                Protocol.encode(Protocol.Packet(request.id, Protocol.Kind.CANCEL))).addOnFailureListener { }
        }
        pending = null; discovery = null
        main.removeCallbacks(timeout); main.removeCallbacks(discoveryTimeout)
        status.text = message
        busy(false)
    }
    private fun busy(on: Boolean) {
        ask.isEnabled = !on; check.isEnabled = !on; question.isEnabled = !on
        cancel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
