package dev.hkgill.gillspeak.car

import androidx.car.app.model.MessageTemplate
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.LifecycleOwner
import dev.hkgill.gillspeak.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarTemplateTest {
    @Test fun everyStateBuildsARealTemplateWithAtMostTwoActions() {
        for (phase in CarPhase.entries) {
            val template = carTemplate(CarState(phase, "Synthetic status"), {}, {}, {})
            assertTrue(template.actions.size <= 2)
            val labels = template.actions.map { it.title?.toCharSequence().toString() }
            assertEquals(phase in setOf(CarPhase.READY, CarPhase.RESULT, CarPhase.ERROR), "Speak" in labels)
            assertEquals(phase == CarPhase.LISTENING, "Finish" in labels)
            assertEquals(phase in setOf(CarPhase.LISTENING, CarPhase.PROCESSING), "Cancel" in labels)
        }
    }

    @Test fun actualCarScreenBuildsWithoutStartingCapture() {
        val context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        val screen = CarPrototypeScreen(context)
        val template = screen.onGetTemplate() as MessageTemplate
        assertTrue(template.message.toCharSequence().contains("Tap Speak"))
        assertEquals("Speak", template.actions.single().title?.toCharSequence().toString())
        val owner = object : LifecycleOwner { override fun getLifecycle() = screen.lifecycle }
        screen.onPause(owner)
        assertTrue((screen.onGetTemplate() as MessageTemplate).actions.isEmpty())
        screen.onResume(owner)
        assertEquals("Speak", (screen.onGetTemplate() as MessageTemplate).actions.single().title?.toCharSequence().toString())
        screen.onDestroy(owner)
    }

    @Test fun syntheticLauncherSurvivesRecreationWithoutStartingModels() {
        Robolectric.buildActivity(CarPreviewActivity::class.java).use { activity ->
            activity.setup().pause().stop().restart().start().resume().visible()
        }
    }

    @Test fun productionManifestAndSourcesContainNoCarPrototypeOrCategoryClaim() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("CarPrototype"))
        assertFalse(manifest.contains("car.app.category"))
        assertFalse(File("src/debug/AndroidManifest.xml").readText().contains("<service"))
        assertTrue(File("src/main/java").walkTopDown().none { it.name.startsWith("CarPrototype") })
    }

    @Test fun localAdapterUsesRulesOfflineWhenGemmaIsOffOrMissingWithoutWritingHistory() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(context)
        val engine = CarLocalInference(context)
        settings.localPolish = false
        val off = engine.polish("synthetic offline transcript", Any()) { false }
        assertTrue(off.detail.contains("Gemma polish is off"))
        assertTrue(off.text.isNotBlank())
        settings.localPolish = true
        val missing = engine.polish("synthetic offline transcript", Any()) { false }
        assertEquals(off.text, missing.text)
        assertTrue(missing.detail.contains("Gemma missing"))
        assertTrue(settings.recentLog().isEmpty())
        assertEquals("", engine.polish("cancelled text", Any()) { true }.text)
    }
}
