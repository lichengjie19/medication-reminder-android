package com.chengjieli.medication.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.chengjieli.medication.MainActivity
import com.chengjieli.medication.MedicationApplication
import com.chengjieli.medication.data.CaseEntity
import com.chengjieli.medication.data.IntakeEntity
import com.chengjieli.medication.data.MedicationEntity
import com.chengjieli.medication.data.OccurrenceEntity
import com.chengjieli.medication.data.OccurrenceStatus
import com.chengjieli.medication.data.PlanStatus
import com.chengjieli.medication.data.ScheduleEntity
import com.chengjieli.medication.data.SkipReason
import com.chengjieli.medication.reminders.AlarmScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Explicitly requested, static renders of production Compose screens with in-memory fixtures.
 * No taps, UI drivers, repository mutations, database restoration, or preference changes.
 * The normal MainActivity/Application startup lifecycle still runs when the activity launches.
 *
 * Select NativeDesignRenderTest#renderKeyPages (or pass designQa=true) to produce all 13 PNGs.
 * Pass historyQa=true for only the six history renders: case categories, record lists, a completed
 * intake detail dialog, and narrow-screen large-text variants. The full set also includes selected
 * navigation tabs and the alarm screen in standard and senior modes, with a 30-minute countdown.
 * A normal full regression run skips this optional artifact generator.
 */
@RunWith(AndroidJUnit4::class)
class NativeDesignRenderTest {
    @LargeTest
    @Test
    fun renderKeyPages() {
        val arguments = InstrumentationRegistry.getArguments()
        val historyOnly = arguments.getString("historyQa") == "true"
        assumeTrue(
            "Native design renders run only when explicitly selected",
            arguments.getString("class").orEmpty().contains("NativeDesignRenderTest") ||
                arguments.getString("designQa") == "true" || historyOnly,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as MedicationApplication).graph
        val fixtures = RenderFixtures(LocalDate.now())
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "design-qa")
        assertTrue("Cannot create render output directory", directory.isDirectory || directory.mkdirs())
        val captures = JSONArray()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun capture(name: String, senior: Boolean = false, fontScale: Float = 1f, renderWidthDp: Int? = null, content: @Composable () -> Unit) {
                val layoutReady = CountDownLatch(1)
                var measuredRenderWidthDp = 0f
                scenario.onActivity { activity ->
                    activity.setContent {
                        val baseDensity = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
                            MedicationTheme(seniorMode = senior) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                    val widthModifier = renderWidthDp?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
                                    Box(widthModifier.fillMaxHeight().onGloballyPositioned {
                                        if (it.size.width > 0 && it.size.height > 0) {
                                            measuredRenderWidthDp = it.size.width / baseDensity.density
                                            layoutReady.countDown()
                                        }
                                    }) { content() }
                                }
                            }
                        }
                    }
                }
                assertTrue("Layout did not complete for $name", layoutReady.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val framesReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.window.decorView.postOnAnimation {
                        activity.window.decorView.postOnAnimation { framesReady.countDown() }
                    }
                }
                assertTrue("Frames did not complete for $name", framesReady.await(5, TimeUnit.SECONDS))
                // Allow the compositor to publish the final frame before UiAutomation captures it.
                SystemClock.sleep(250)
                instrumentation.waitForIdleSync()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable for $name" }
                val target = File(directory, "$name.png")
                try {
                    target.outputStream().use { assertTrue("PNG encoding failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    captures.put(JSONObject().apply {
                        put("name", name)
                        put("path", target.absolutePath)
                        put("widthPx", bitmap.width)
                        put("heightPx", bitmap.height)
                        put("densityDpi", context.resources.displayMetrics.densityDpi)
                        put("fontScale", fontScale)
                        put("seniorMode", senior)
                        put("renderWidthDp", measuredRenderWidthDp)
                        put("requestedRenderWidthDp", renderWidthDp ?: JSONObject.NULL)
                    })
                } finally {
                    bitmap.recycle()
                }
                Log.i("NativeDesignRender", "Rendered ${target.absolutePath}")
            }

            if (!historyOnly) capture("today") {
                RenderRootPage("今日用药", selectedTab = 0) {
                    FocusTodayScreen(
                        graph, todayReminderGroups(fixtures.todayOccurrences, fixtures.date.toString(), fixtures.now),
                        fixtures.now, onAction = { _, _ -> }, onRecord = {}, onAdd = {},
                    )
                }
            }
            if (!historyOnly) capture("editor") {
                MedicationEditor(graph, EditMedicationRequest(fixtures.case.id, fixtures.medication), listOf(fixtures.schedule), {}, {})
            }
            capture("history-cases") {
                RenderRootPage("服药记录", selectedTab = 2) {
                    HistoryCaseList(fixtures.historyGroups, onSelect = {})
                }
            }
            capture("history-cases-narrow-font-1_5", fontScale = 1.5f, renderWidthDp = 360) {
                RenderRootPage("服药记录", selectedTab = 2) {
                    HistoryCaseList(fixtures.historyGroups, onSelect = {})
                }
            }
            capture("history") {
                RenderHistoryPage {
                    HistoryScreen(fixtures.historyGroup, listOf(fixtures.intake), fixtures.now, onRecord = {})
                }
            }
            capture("history-detail") {
                RenderHistoryPage {
                    HistoryScreen(fixtures.historyGroup, listOf(fixtures.intake), fixtures.now, onRecord = {})
                    RecordDialog(
                        fixtures.historyOccurrences.first { it.id == fixtures.intake.occurrenceId },
                        fixtures.intake, fixtures.now, dismiss = {},
                    )
                }
            }
            capture("history-font-1_3", fontScale = 1.3f) {
                RenderHistoryPage {
                    HistoryScreen(fixtures.historyGroup, listOf(fixtures.intake), fixtures.now, onRecord = {})
                }
            }
            capture("history-narrow-font-1_5", fontScale = 1.5f, renderWidthDp = 360) {
                val group = fixtures.historyGroup.copy(records = fixtures.historyGroup.records.mapIndexed { index, record ->
                    if (index == 0) record.copy(
                        medicineName = "示例药品 B 缓释胶囊每日用药说明", quantity = "12.5", quantityUnit = "毫升",
                        doseValue = "1250", doseUnit = "mg",
                    ) else record
                })
                RenderHistoryPage {
                    HistoryScreen(group, listOf(fixtures.intake), fixtures.now, onRecord = {})
                }
            }
            if (!historyOnly) capture("editor-senior-font-1_3", senior = true, fontScale = 1.3f) {
                MedicationEditor(graph, EditMedicationRequest(fixtures.case.id, fixtures.medication), listOf(fixtures.schedule), {}, {})
            }
            if (!historyOnly) capture("today-font-1_5", fontScale = 1.5f, renderWidthDp = 360) {
                val occurrences = fixtures.todayOccurrences.mapIndexed { index, item ->
                    if (index == 0) item.copy(
                        quantity = "12", quantityUnit = "毫升", doseValue = "",
                        medicineName = "示例药品 A 口服液每日用药说明", imagePath = null,
                    ) else item
                }
                RenderRootPage("今日用药", selectedTab = 0) {
                    FocusTodayScreen(
                        graph, todayReminderGroups(occurrences, fixtures.date.toString(), fixtures.now),
                        fixtures.now, onAction = { _, _ -> }, onRecord = {}, onAdd = {},
                    )
                }
            }
            if (!historyOnly) capture("today-long-name") {
                val occurrences = fixtures.todayOccurrences.mapIndexed { index, item ->
                    if (index == 0) item.copy(
                        medicineName = "示例药品A缓释制剂每日用药说明", imagePath = null,
                    ) else item
                }
                RenderRootPage("今日用药", selectedTab = 0) {
                    FocusTodayScreen(
                        graph, todayReminderGroups(occurrences, fixtures.date.toString(), fixtures.now),
                        fixtures.now, onAction = { _, _ -> }, onRecord = {}, onAdd = {},
                    )
                }
            }
            val alarmNow = System.currentTimeMillis()
            val alarmOccurrences = fixtures.todayOccurrences.filter {
                it.status == OccurrenceStatus.PENDING && fixtures.now >= it.roundAt && fixtures.now < it.deadlineAt
            }.map {
                it.copy(originalAt = alarmNow, roundAt = alarmNow, deadlineAt = alarmNow + 30 * 60_000L)
            }
            if (!historyOnly) capture("alarm") {
                AlarmScreen(items = alarmOccurrences, close = {}, openApp = {}, done = {})
            }
            if (!historyOnly) capture("alarm-senior-font-1_3", senior = true, fontScale = 1.3f) {
                AlarmScreen(items = alarmOccurrences, close = {}, openApp = {}, done = {})
            }
        }
        File(directory, "manifest.json").writeText(JSONObject().apply {
            put("fixtureDate", fixtures.date.toString())
            put("fixtureClock", "18:10")
            put("fixtureTimeZone", ZoneId.systemDefault().id)
            put("renderScope", if (historyOnly) "history" else "all")
            put("contentSource", "Production Compose components with static synthetic parameters")
            put("captures", captures)
        }.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("nativeDesignRenderDirectory", directory.absolutePath) })
        val expectedCaptures = if (historyOnly) 6 else 13
        assertTrue("Expected $expectedCaptures render artifacts", captures.length() == expectedCaptures)
    }

    @Composable
    private fun RenderHistoryPage(content: @Composable () -> Unit) {
        Scaffold(
            topBar = { FocusPageHeader(title = "服药记录", home = false, onBack = {}, onHistory = {}) },
        ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
    }

    @Composable
    private fun RenderRootPage(title: String, selectedTab: Int, content: @Composable () -> Unit) {
        Scaffold(
            topBar = { FocusPageHeader(title = title, home = selectedTab == 0, onHistory = {}) },
            bottomBar = { SeniorNavigation(selectedTab) {} },
        ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
    }

    private class RenderFixtures(val date: LocalDate) {
        fun at(time: String): Long = date.atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = at("18:10")
        val case = CaseEntity(id = "render-case", title = "日常用药示例", createdAt = at("00:00"))
        val medication = MedicationEntity(
            id = "render-med-a", caseId = case.id, name = "示例药品 A", specification = "100 mg / 片",
            strengthValue = "100", strengthUnit = "mg", quantityUnit = "片", startDate = date.toString(), mealNote = "饭后",
        )
        val schedule = ScheduleEntity(
            id = "render-schedule-a-evening", medicationId = medication.id, time = "18:00",
            quantity = "1", doseValue = "100", doseUnit = "mg", effectiveFrom = at("00:00"),
        )
        private fun occurrence(id: String, time: String, drugB: Boolean = false): OccurrenceEntity {
            val roundAt = at(time)
            return OccurrenceEntity(
                id = id, scheduleId = "schedule-$id", medicationId = if (drugB) "render-med-b" else medication.id,
                caseId = case.id, date = date.toString(), originalAt = roundAt, roundAt = roundAt,
                deadlineAt = roundAt + 30 * 60 * 1000L, status = OccurrenceStatus.SCHEDULED,
                medicineName = if (drugB) "示例药品 B" else medication.name, caseTitle = case.title,
                quantity = if (drugB) "2" else "1", quantityUnit = if (drugB) "粒" else "片",
                doseValue = if (drugB) "500" else "100", doseUnit = "mg", mealNote = if (drugB) "饭前" else "饭后",
            )
        }
        val todayOccurrences = listOf(
            occurrence("render-a-evening", "18:00").copy(status = OccurrenceStatus.PENDING),
            occurrence("render-b-evening", "20:00", drugB = true),
        )
        val historyOccurrences = listOf(
            occurrence("render-a-morning", "08:00").copy(status = OccurrenceStatus.TAKEN, processedAt = at("08:12")),
            occurrence("render-b-noon", "12:00", drugB = true).copy(
                status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.TIMEOUT, processedAt = at("12:30"),
            ),
            occurrence("render-a-yesterday", "08:00").let { occurrence ->
                val scheduledAt = date.minusDays(1).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                occurrence.copy(
                    date = date.minusDays(1).toString(), originalAt = scheduledAt, roundAt = scheduledAt,
                    deadlineAt = scheduledAt + 30 * 60_000L, status = OccurrenceStatus.SKIPPED,
                    skipReason = SkipReason.MANUAL, processedAt = scheduledAt + 10 * 60_000L,
                )
            },
        )
        private val archivedCase = CaseEntity(
            id = "render-archived-case", title = "疗程结束后的历史药单", status = PlanStatus.ARCHIVED,
        )
        private val emptyCase = CaseEntity(id = "render-empty-case", title = "新建药单，尚无服药记录")
        val historyGroups = historyCaseGroups(
            listOf(case, archivedCase, emptyCase),
            historyOccurrences + historyOccurrences.last().copy(
                id = "render-archived-record", caseId = archivedCase.id, caseTitle = archivedCase.title,
            ),
            now,
        )
        val historyGroup = historyGroups.single { it.caseId == case.id }
        val intake = IntakeEntity(
            id = "render-intake-a", occurrenceId = "render-a-morning", actualAt = at("08:12"),
            quantity = "1", quantityUnit = "片", updatedAt = at("08:12"),
        )
    }
}
