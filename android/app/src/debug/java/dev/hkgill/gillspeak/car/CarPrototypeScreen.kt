package dev.hkgill.gillspeak.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Deliberately NOT registered in any manifest: no supported general-assistant category exists.
 * TestCarContext can exercise the screen without lying to Android Auto about an app category.
 * A future eligible product needs manifest review and a specific host allowlist before binding.
 */
class CarPrototypeService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this).build()
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarPrototypeScreen(carContext)
    }
}

internal class CarPrototypeScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val controller = CarController(worker, { handler.post(it) },
        { interrupted -> CarMicrophone(context, interrupted) },
        CarLocalInference(context), {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) "Open the car screen before speaking."
            else carProblem(context)
        },
        { delay, action ->
            val task = Runnable(action)
            handler.postDelayed(task, delay)
            val remove = { handler.removeCallbacks(task) }
            remove
        }, { invalidate() })

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { controller.reconnect() }
    override fun onResume(owner: LifecycleOwner) {
        if (controller.state.phase == CarPhase.DISCONNECTED) controller.reconnect()
    }
    override fun onPause(owner: LifecycleOwner) { controller.disconnect() }
    override fun onStop(owner: LifecycleOwner) { controller.disconnect() }
    override fun onDestroy(owner: LifecycleOwner) {
        controller.disconnect()
        worker.shutdown() // let a native ASR call finish; never unload a model another phone surface owns
    }

    override fun onGetTemplate(): Template = carTemplate(controller.state, controller::speak,
        controller::finish, { controller.cancel() })
}

/** Same template and action policy used by the synthetic launcher preview. At most two large actions. */
internal fun carTemplate(state: CarState, speak: () -> Unit, finish: () -> Unit, cancel: () -> Unit): MessageTemplate {
    val builder = MessageTemplate.Builder(state.message)
        .setTitle("GillSpeak · developer prototype")
        .setHeaderAction(Action.APP_ICON)
    fun action(title: String, click: () -> Unit) = Action.Builder().setTitle(title).setOnClickListener(click).build()
    when {
        state.canSpeak -> builder.addAction(action("Speak", speak))
        state.phase == CarPhase.LISTENING -> builder.addAction(action("Finish", finish))
    }
    if (state.phase in setOf(CarPhase.LISTENING, CarPhase.PROCESSING, CarPhase.RESULT)) {
        builder.addAction(action(if (state.phase == CarPhase.RESULT) "Clear" else "Cancel", cancel))
    }
    return builder.build()
}
