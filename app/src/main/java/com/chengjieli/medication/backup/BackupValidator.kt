package com.chengjieli.medication.backup

import com.chengjieli.medication.data.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Runs both before preview and before commit; it never relies on SQLite foreign keys. */
object BackupValidator {
    private const val WINDOW = 30 * 60 * 1000L
    const val MAX_RECORDS = 200_000
    private val imagePath = Regex("images/[A-Za-z0-9_-]{1,100}\\.jpg")

    fun imagePaths(snapshot: BackupSnapshot): Set<String> = buildSet {
        snapshot.cases.forEach { addAll(it.prescriptionImages) }
        snapshot.medications.forEach { addAll(it.imagePaths) }
        snapshot.occurrences.mapNotNullTo(this) { it.imagePath }
    }

    fun validate(snapshot: BackupSnapshot) {
        require(snapshot.formatVersion == 1) { "不支持的备份版本" }
        require(snapshot.exportedAt > 0) { "备份时间无效" }
        require(snapshot.cases.size + snapshot.medications.size + snapshot.schedules.size + snapshot.occurrences.size +
            snapshot.intakes.size + snapshot.expiryLocks.size <= MAX_RECORDS) { "备份记录过多" }
        val cases = unique(snapshot.cases) { it.id }
        val medications = unique(snapshot.medications) { it.id }
        val schedules = unique(snapshot.schedules) { it.id }
        val occurrences = unique(snapshot.occurrences) { it.id }
        unique(snapshot.intakes) { it.id }
        require(snapshot.occurrences.map { it.scheduleId to it.date }.toSet().size == snapshot.occurrences.size) { "服药事项日期重复" }
        require(snapshot.intakes.map { it.occurrenceId }.toSet().size == snapshot.intakes.size) { "实际服药记录重复" }
        require(snapshot.expiryLocks.map { it.scheduleId to it.date }.toSet().size == snapshot.expiryLocks.size) { "超时锁重复" }
        snapshot.cases.forEach {
            require(it.title.isNotBlank() && it.createdAt > 0) { "用药事项信息无效" }
        }
        snapshot.medications.forEach {
            require(it.caseId in cases && it.name.isNotBlank() && it.quantityUnit.isNotBlank()) { "药品关联或名称无效" }
            val start = date(it.startDate)
            it.endDate?.let { end -> require(date(end) >= start) { "结束日期早于开始日期" } }
            require(it.strengthValue.isBlank() || positive(it.strengthValue) && it.strengthUnit in setOf("g", "mg")) { "药品规格无效" }
        }
        snapshot.schedules.forEach {
            require(it.medicationId in medications && it.effectiveFrom > 0) { "服药安排关联无效" }
            require(Regex("[0-2][0-9]:[0-5][0-9]").matches(it.time)) { "提醒时刻无效" }
            LocalTime.parse(it.time)
            require(positive(it.quantity) && it.inputMode in setOf("COUNT", "DOSE")) { "服药数量或输入方式无效" }
            validateDose(it.doseValue, it.doseUnit)
        }
        val scheduleTimes = snapshot.schedules.filter { it.enabled }.map { it.medicationId to it.time }
        require(scheduleTimes.size == scheduleTimes.toSet().size) { "同一药品的提醒时刻重复" }
        snapshot.occurrences.forEach {
            val schedule = schedules[it.scheduleId]
            val medication = medications[it.medicationId]
            require(schedule != null && medication != null && it.caseId in cases &&
                schedule.medicationId == it.medicationId && medication.caseId == it.caseId) { "服药事项关联无效" }
            date(it.date)
            require(it.originalAt > 0 && it.roundAt > 0 && it.round >= 0 &&
                it.roundAt <= Long.MAX_VALUE - WINDOW && it.deadlineAt == it.roundAt + WINDOW) { "提醒轮次或截止时间无效" }
            require(positive(it.quantity) && it.quantityUnit.isNotBlank() && it.medicineName.isNotBlank()) { "服药快照无效" }
            validateDose(it.doseValue, it.doseUnit)
            when (it.status) {
                OccurrenceStatus.SCHEDULED -> require(it.round == 0 && it.processedAt == null && it.skipReason == null) { "未到时状态无效" }
                OccurrenceStatus.PENDING -> require(it.processedAt == null && it.skipReason == null) { "待处理状态无效" }
                OccurrenceStatus.SNOOZED -> require(it.round > 0 && it.processedAt == null && it.skipReason == null) { "稍后提醒状态无效" }
                OccurrenceStatus.TAKEN -> require(it.skipReason == null && inWindow(it)) { "已服用处理时间无效" }
                OccurrenceStatus.SKIPPED -> when (it.skipReason) {
                    SkipReason.TIMEOUT -> require(it.processedAt == it.deadlineAt) { "自动跳过时间无效" }
                    SkipReason.MANUAL -> require(inWindow(it)) { "主动跳过时间无效" }
                    SkipReason.PLAN_STOPPED -> require(it.processedAt != null && it.processedAt > 0) { "计划停止时间无效" }
                    null -> error("跳过原因缺失")
                }
            }
        }
        val intakesByOccurrence = snapshot.intakes.associateBy { it.occurrenceId }
        snapshot.intakes.forEach {
            require(occurrences[it.occurrenceId]?.status == OccurrenceStatus.TAKEN) { "服药记录缺少对应已服用事项" }
            require(it.actualAt > 0 && it.updatedAt > 0 && positive(it.quantity) && it.quantityUnit.isNotBlank()) { "实际服药记录无效" }
        }
        snapshot.occurrences.filter { it.status == OccurrenceStatus.TAKEN }.forEach {
            require(it.id in intakesByOccurrence) { "已服用事项缺少实际记录" }
        }
        snapshot.expiryLocks.forEach { require(it.scheduleId.isNotBlank() && it.deadlineAt > 0); date(it.date) }
        require(imagePaths(snapshot).all { imagePath.matches(it) }) { "备份图片路径无效" }
    }

    private fun date(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "日期格式无效" }
        return LocalDate.parse(value)
    }
    private fun inWindow(value: OccurrenceEntity): Boolean = value.processedAt?.let { it >= value.roundAt && it < value.deadlineAt } == true
    private fun validateDose(value: String, unit: String) {
        require(value.isBlank() || positive(value) && unit in setOf("g", "mg")) { "单次剂量无效" }
    }
    private fun positive(value: String): Boolean = value.length <= 40 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(value) &&
        runCatching { BigDecimal(value).signum() > 0 }.getOrDefault(false)
    private fun <T> unique(values: List<T>, id: (T) -> String): Map<String, T> {
        require(values.all { id(it).isNotBlank() && id(it).length <= 128 }) { "记录 ID 无效" }
        return values.associateBy(id).also { require(it.size == values.size) { "备份记录 ID 重复" } }
    }
}
