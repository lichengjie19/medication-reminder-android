package com.chengjieli.medication.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chengjieli.medication.MainActivity
import com.chengjieli.medication.data.MedicationEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the production calendar with synthetic dates and in-memory callbacks only.
 * No repository or preference writes are performed by these tests; MainActivity's normal
 * application/activity startup lifecycle still runs, as in NativeDesignRenderTest.
 * Use an emulator/test installation. Explicitly select this class or calendarQa=true for PNGs.
 */
@RunWith(AndroidJUnit4::class)
class HistoryCalendarUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val initialDate = LocalDate.of(2026, 9, 29)
    private val takenDates = (21..29).map { LocalDate.of(2026, 9, it) }.toSet() +
        setOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 10, 1))

    @Test
    fun takenDayMarkersRemainCorrectAfterChangingMonths() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showCalendar(scenario)
            assertDate("2026年9月29日，已服药", selected = true)
            assertDate("2026年9月21日，已服药", selected = false)
            assertDate("2026年9月20日", selected = false)

            clickDescription("上一月")
            awaitNode("August heading") { it.text?.toString() == "2026年8月" }.recycle()
            assertDate("2026年8月31日，已服药", selected = false)
            assertDate("2026年8月30日", selected = false)

            clickDescription("下一月")
            awaitNode("September heading") { it.text?.toString() == "2026年9月" }.recycle()
            assertDate("2026年9月21日，已服药", selected = false)
            assertDate("2026年9月20日", selected = false)
            assertDate("2026年9月29日，已服药", selected = true)
        }
    }

    @Test
    fun selectingThenCancellingDoesNotSubmitDate() {
        val submittedDate = AtomicReference<LocalDate>()
        val dismissCount = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showCalendar(scenario, dismissed = { dismissCount.incrementAndGet() }, submitted = submittedDate::set)
            clickDescription("2026年9月21日，已服药")
            assertDate("2026年9月21日，已服药", selected = true)
            assertNull("Selecting a day must wait for confirmation", submittedDate.get())

            clickText("取消")
            awaitCondition("Cancel callback") { dismissCount.get() == 1 }
            assertNull("Cancel must not apply the draft date", submittedDate.get())
        }
    }

    @Test
    fun confirmingDateInAnotherMonthSubmitsExactlyThatDate() {
        val submittedDate = AtomicReference<LocalDate>()
        val submitCount = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showCalendar(scenario, submitted = {
                submittedDate.set(it)
                submitCount.incrementAndGet()
            })
            clickDescription("下一月")
            clickDescription("2026年10月1日，已服药")
            assertDate("2026年10月1日，已服药", selected = true)
            assertNull("Month/day changes must wait for confirmation", submittedDate.get())

            clickText("查看记录")
            awaitCondition("Confirm callback") { submitCount.get() == 1 }
            assertEquals(LocalDate.of(2026, 10, 1), submittedDate.get())
        }
    }

    @Test
    fun renderCalendarVariants() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Calendar renders run only when explicitly selected",
            arguments.getString("class").orEmpty().contains("HistoryCalendarUiTest") ||
                arguments.getString("calendarQa") == "true",
        )
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "history-calendar-qa")
        assertTrue("Cannot create calendar render directory", directory.isDirectory || directory.mkdirs())
        val captures = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf(
                RenderMode("history-calendar"),
                RenderMode("history-calendar-senior-font-1_5", senior = true, fontScale = 1.5f),
                RenderMode("history-calendar-dark", dark = true),
            ).forEach { mode ->
                fun capture(name: String) {
                    awaitRenderedFrames(scenario)
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                        "Screenshot unavailable for $name"
                    }
                    val file = File(directory, "$name.png")
                    try {
                        file.outputStream().use {
                            assertTrue("PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        captures.put(JSONObject().apply {
                            put("name", name)
                            put("path", file.absolutePath)
                            put("widthPx", bitmap.width)
                            put("heightPx", bitmap.height)
                            put("seniorMode", mode.senior)
                            put("fontScale", mode.fontScale)
                            put("darkTheme", mode.dark)
                        })
                    } finally {
                        bitmap.recycle()
                    }
                }
                showCalendar(scenario, mode = mode)
                if (!mode.senior) {
                    assertDate("2026年9月21日，已服药", selected = false)
                    assertDate("2026年9月29日，已服药", selected = true)
                }
                awaitNode("Confirm button") { it.text?.toString() == "查看记录" }.recycle()
                capture(mode.name)
                if (mode.senior) {
                    val scrollable = awaitNode("Scrollable calendar") { it.isScrollable }
                    try {
                        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    } finally {
                        scrollable.recycle()
                    }
                    assertDate("2026年9月29日，已服药", selected = true)
                    awaitNode("Taken marker legend") { it.text?.toString() == "当天已服药" }.recycle()
                    capture("${mode.name}-scrolled")
                }
                showSummary(scenario, mode)
                awaitNode("18 records over 9 taken days") {
                    it.text?.toString()?.contains("执行中 · 18 条记录 · 9 天") == true
                }.recycle()
                capture(mode.name.replace("calendar", "summary-days"))
            }
        }
        File(directory, "manifest.json").writeText(JSONObject().apply {
            put("initialDate", initialDate.toString())
            put("contentSource", "Production calendar and medication summary with synthetic in-memory records")
            put("captures", captures)
        }.toString(2))
        instrumentation.sendStatus(0, Bundle().apply {
            putString("historyCalendarRenderDirectory", directory.absolutePath)
        })
    }

    private fun showCalendar(
        scenario: ActivityScenario<MainActivity>,
        mode: RenderMode = RenderMode("test"),
        dismissed: () -> Unit = {},
        submitted: (LocalDate) -> Unit = {},
    ) {
        render(scenario, mode) {
            var visible by remember { mutableStateOf(true) }
            if (visible) HistoryDatePicker(
                initialDate = initialDate,
                takenDates = takenDates,
                dismiss = {
                    visible = false
                    dismissed()
                },
                selected = {
                    visible = false
                    submitted(it)
                },
            )
        }
        awaitNode("Calendar heading") { it.text?.toString() == "选择记录日期" }.recycle()
    }

    private fun showSummary(scenario: ActivityScenario<MainActivity>, mode: RenderMode) {
        val medication = MedicationEntity(id = "calendar-render-med", caseId = "calendar-render-case", name = "示例药品 A")
        val records = (21..29).flatMap { day ->
            listOf(8, 19).map { hour ->
                val date = LocalDate.of(2026, 9, day)
                val at = date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                OccurrenceEntity(
                    id = "calendar-render-$day-$hour", scheduleId = "calendar-render-$hour",
                    medicationId = medication.id, caseId = medication.caseId, date = date.toString(),
                    originalAt = at, roundAt = at, deadlineAt = at + 30 * 60_000L,
                    status = OccurrenceStatus.TAKEN, processedAt = at,
                    medicineName = medication.name, caseTitle = "日常用药示例", quantity = "1", quantityUnit = "粒",
                )
            }
        }
        val group = HistoryCaseGroup(medication.caseId, "日常用药示例", PlanStatus.ACTIVE, records)
        render(scenario, mode) {
            Scaffold(topBar = { FocusPageHeader(title = "服药汇总", home = false, onBack = {}, onHistory = {}) }) { padding ->
                Box(Modifier.padding(padding)) {
                    HistorySummaryScreen(group, listOf(medication), records.maxOf { it.originalAt } + 60_000L, onMedication = {})
                }
            }
        }
    }

    private fun render(scenario: ActivityScenario<MainActivity>, mode: RenderMode, content: @Composable () -> Unit) {
        scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (mode.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, mode.fontScale),
                    LocalConfiguration provides configuration,
                ) {
                    MedicationTheme(seniorMode = mode.senior) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun assertDate(description: String, selected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        var lastDetails = "Date description was not found"
        do {
            instrumentation.waitForIdleSync()
            val descriptionNode = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
                it.contentDescription?.toString() == description
            }
            if (descriptionNode != null) {
                val (checked, details) = dateSelectionState(descriptionNode)
                lastDetails = details
                if (checked == selected) return
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        val evidence = captureCalendarFailureEvidence()
        Log.e("HistoryCalendarUiTest", "$description expected selected=$selected; $lastDetails; $evidence")
        throw AssertionError("$description expected selected=$selected; date node/ancestors: $lastDetails; $evidence")
    }

    private fun captureCalendarFailureEvidence(): String {
        val texts = mutableListOf<String>()
        fun collectText(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                if (node.isVisibleToUser && !node.text.isNullOrBlank()) texts += node.text.toString()
                for (index in 0 until node.childCount) collectText(node.getChild(index))
            } finally {
                node.recycle()
            }
        }
        collectText(instrumentation.uiAutomation.rootInActiveWindow)
        val screenshot = runCatching {
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "history-calendar-qa")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create failure screenshot directory" }
            val target = File(directory, "calendar-failure.png")
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
            try {
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            target.absolutePath
        }.getOrElse { "capture failed: ${it.message}" }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("historyCalendarFailureScreenshot", screenshot)
            putString("historyCalendarFailureVisibleText", texts.joinToString(" | "))
        })
        return "visibleText=${texts.joinToString(" | ")}; screenshot=$screenshot"
    }

    /**
     * Compose 1.8 emits a content-description child for merged nodes. That synthetic child
     * has no selection state: the real date cell ancestor carries Selected semantics, mapped
     * to Android's checkable/checked flags for Role.RadioButton (only Role.Tab uses selected).
     * Require a checkable date cell instead of treating a missing state as 'not selected'.
     */
    private fun dateSelectionState(descriptionNode: AccessibilityNodeInfo): Pair<Boolean?, String> {
        var current: AccessibilityNodeInfo? = descriptionNode
        val details = mutableListOf<String>()
        try {
            repeat(6) {
                val node = current ?: return null to details.joinToString(" -> ")
                // A synthetic description child can retain a cached parent after selection changes.
                node.refresh()
                val state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription else null
                details += "class=${node.className}, description=${node.contentDescription}, " +
                    "checkable=${node.isCheckable}, checked=${node.isChecked}, selected=${node.isSelected}, " +
                    "clickable=${node.isClickable}, state=$state"
                if (node.isCheckable) return node.isChecked to details.joinToString(" -> ")
                val parent = node.parent
                node.recycle()
                current = parent
            }
            return null to details.joinToString(" -> ")
        } finally {
            current?.recycle()
        }
    }

    private fun clickDescription(description: String) = clickNode(description) {
        it.contentDescription?.toString() == description
    }

    private fun clickText(text: String) = clickNode(text) { it.text?.toString() == text }

    private fun clickNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node = awaitNode(label, predicate)
        try {
            while (!node.isClickable) {
                val parent = node.parent ?: throw AssertionError("No clickable ancestor for $label")
                node.recycle()
                node = parent
            }
            assertTrue("Click failed for $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            node.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        do {
            instrumentation.waitForIdleSync()
            findNode(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Accessibility node not found: $label")
    }

    @Suppress("DEPRECATION")
    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        try {
            if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                findNode(node.getChild(index), predicate)?.let { return it }
            }
            return null
        } finally {
            node.recycle()
        }
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private fun awaitRenderedFrames(scenario: ActivityScenario<MainActivity>) {
        val ready = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { ready.countDown() }
            }
        }
        assertTrue("Calendar frames did not complete", ready.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
    }

    private data class RenderMode(
        val name: String,
        val senior: Boolean = false,
        val fontScale: Float = 1f,
        val dark: Boolean = false,
    )
}
