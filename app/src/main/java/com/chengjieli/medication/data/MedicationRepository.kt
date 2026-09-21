package com.chengjieli.medication.data

import android.content.Context
import androidx.room.withTransaction
import com.chengjieli.medication.domain.DoseCalculator
import com.chengjieli.medication.domain.MedicationUnits
import com.chengjieli.medication.domain.ReminderReducer
import com.chengjieli.medication.domain.SchedulePlanner
import java.time.*

class MedicationRepository internal constructor(private val db: MedicationDatabase) {
    constructor(context: Context) : this(MedicationDatabase.get(context))
    private val dao = db.dao()
    val cases = dao.casesFlow()
    val medications = dao.medicationsFlow()
    val schedules = dao.schedulesFlow()
    val occurrences = dao.occurrencesFlow()
    val intakes = dao.intakesFlow()

    suspend fun saveCase(entity: CaseEntity) = db.withTransaction {
        require(entity.id.isNotBlank() && entity.title.isNotBlank()) { "请填写用药事项标题" }
        validateImages(entity.prescriptionImages)
        val old = dao.cases().firstOrNull { it.id == entity.id }
        val now = System.currentTimeMillis()
        reconcileLocked(now)
        dao.putCase(entity.copy(title = entity.title.trim(), createdAt = old?.createdAt ?: now))
        if (old != null && old.status != entity.status) updateCaseSchedules(entity.id, entity.status, now)
        dao.occurrences().filter {
            it.caseId == entity.id && it.status == OccurrenceStatus.SCHEDULED && it.originalAt > now
        }.forEach { dao.putOccurrence(it.copy(caseTitle = entity.title.trim())) }
        reconcileLocked(now)
    }

    suspend fun saveMedication(medication: MedicationEntity, schedules: List<ScheduleEntity>, now: Long = System.currentTimeMillis()) = db.withTransaction {
        validateMedication(medication)
        // New edits must separate count units from mass. Older backups/history remain readable.
        require(MedicationUnits.isQuantityUnit(medication.quantityUnit)) { "请选择粒、片等数量单位，mg/g 请填在剂量单位中" }
        require(dao.cases().any { it.id == medication.caseId }) { "所属用药事项不存在" }
        require(schedules.isNotEmpty()) { "请添加至少一个提醒时刻" }
        require(schedules.map { it.id }.distinct().size == schedules.size) { "提醒编号重复" }
        require(schedules.filter { it.enabled }.map { it.time }.distinct().size == schedules.count { it.enabled }) { "同一药品的提醒时刻不能重复" }
        schedules.forEach { validateSchedule(it); require(it.medicationId == medication.id) { "提醒与药品关联错误" } }
        val allSchedules = dao.schedules()
        require(schedules.none { draft -> allSchedules.any { it.id == draft.id && it.medicationId != medication.id } }) { "提醒编号已被其他药品使用" }
        val oldMedication = dao.medications().firstOrNull { it.id == medication.id }
        require(oldMedication == null || oldMedication.caseId == medication.caseId) { "不能移动已有药品到其他事项" }
        reconcileLocked(now)
        // Only future unstarted occurrences are replaced. Started rounds retain their dose snapshot.
        deleteFuture(medication.id, now)
        allSchedules.filter { it.medicationId == medication.id }.forEach { dao.putSchedule(it.copy(enabled = false)) }
        dao.putMedication(medication.copy(name = medication.name.trim()))
        schedules.forEach { dao.putSchedule(it.copy(effectiveFrom = now)) }
        if (!medication.active) stopMedication(medication.id, now)
        reconcileLocked(now)
    }

    suspend fun setCaseStatus(id: String, status: PlanStatus, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val item = dao.cases().firstOrNull { it.id == id } ?: error("用药事项不存在")
        reconcileLocked(now)
        if (item.status != status) {
            dao.putCase(item.copy(status = status))
            updateCaseSchedules(id, status, now)
        }
        reconcileLocked(now)
    }

    private suspend fun updateCaseSchedules(id: String, status: PlanStatus, now: Long) {
        val medicationIds = dao.medications().filter { it.caseId == id }.map { it.id }.toSet()
        if (status == PlanStatus.ACTIVE) {
            dao.schedules().filter { it.medicationId in medicationIds && it.enabled }.forEach { dao.putSchedule(it.copy(effectiveFrom = now)) }
        } else medicationIds.forEach { stopMedication(it, now) }
    }

    suspend fun setMedicationActive(id: String, active: Boolean, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val item = dao.medications().firstOrNull { it.id == id } ?: error("药品不存在")
        reconcileLocked(now)
        if (item.active != active) {
            dao.putMedication(item.copy(active = active))
            if (active) dao.schedules().filter { it.medicationId == id && it.enabled }.forEach { dao.putSchedule(it.copy(effectiveFrom = now)) }
            else stopMedication(id, now)
        }
        reconcileLocked(now)
    }

    private suspend fun deleteFuture(medicationId: String, now: Long) {
        dao.occurrences().filter { it.medicationId == medicationId && it.status == OccurrenceStatus.SCHEDULED && it.originalAt > now }
            .forEach { dao.deleteOccurrence(it.id) }
    }

    private suspend fun stopMedication(medicationId: String, now: Long) {
        deleteFuture(medicationId, now)
        dao.occurrences().filter { it.medicationId == medicationId && it.status in ReminderReducer.openStatuses }
            .forEach { dao.putOccurrence(it.copy(status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.PLAN_STOPPED, processedAt = now)) }
    }

    @Suppress("UNUSED_PARAMETER") // Future wall-clock times are checked on every refresh, including cold starts.
    suspend fun reconcile(now: Long = System.currentTimeMillis(), timeZoneChanged: Boolean = false) = db.withTransaction {
        reconcileLocked(now)
    }

    private suspend fun persist(item: OccurrenceEntity) {
        dao.putOccurrence(item)
        if (item.skipReason == SkipReason.TIMEOUT) {
            dao.putLock(ExpiryLockEntity(item.scheduleId, item.date, item.deadlineAt))
            dao.deleteIntake(item.id)
        }
    }

    private suspend fun reconcileLocked(now: Long) {
        val zone = ZoneId.systemDefault()
        val schedules = dao.schedules()
        val bySchedule = schedules.associateBy { it.id }
        val cases = dao.cases().associateBy { it.id }
        val medications = dao.medications().associateBy { it.id }
        val locks = dao.locks().associateBy { it.scheduleId to it.date }
        for (old in dao.occurrences()) {
            // A timeout tombstone always wins, before any timezone projection.
            var item = applyLock(old, locks[old.scheduleId to old.date])
            item = SchedulePlanner.reprojectFuture(item, bySchedule[item.scheduleId], now, zone)
            item = ReminderReducer.reconcile(item, now)
            if (item.status in ReminderReducer.openStatuses &&
                (cases[item.caseId]?.status != PlanStatus.ACTIVE || medications[item.medicationId]?.active != true)) {
                if (item.status == OccurrenceStatus.SCHEDULED && item.originalAt > now) {
                    dao.deleteOccurrence(item.id)
                    continue
                }
                item = item.copy(status = OccurrenceStatus.SKIPPED, skipReason = SkipReason.PLAN_STOPPED, processedAt = now)
            }
            if (item != old || item.skipReason == SkipReason.TIMEOUT && old.skipReason != SkipReason.TIMEOUT) persist(item)
        }
        val known = dao.occurrences().groupBy { it.scheduleId }.mapValues { (_, items) -> items.map { it.date }.toSet() }
        for (schedule in schedules) {
            val medication = medications[schedule.medicationId] ?: continue
            val case = cases[medication.caseId] ?: continue
            SchedulePlanner.generate(case, medication, schedule, now, zone, known[schedule.id] ?: emptySet()).forEach {
                persist(applyLock(it, locks[it.scheduleId to it.date]))
            }
        }
    }

    private fun applyLock(item: OccurrenceEntity, lock: ExpiryLockEntity?): OccurrenceEntity = if (lock == null) item else {
        ReminderReducer.timeout(item.copy(roundAt = lock.deadlineAt - ReminderReducer.WINDOW_MILLIS, deadlineAt = lock.deadlineAt))
    }

    suspend fun performAction(id: String, round: Int, action: ReminderAction, now: Long = System.currentTimeMillis()): ActionOutcome = db.withTransaction {
        reconcileLocked(now)
        val item = dao.occurrence(id) ?: return@withTransaction ActionOutcome.NOT_AVAILABLE
        val result = ReminderReducer.act(item, round, action, now)
        persist(result.occurrence)
        if (result.outcome == ActionOutcome.APPLIED && action == ReminderAction.TAKE) {
            dao.putIntake(IntakeEntity(occurrenceId = id, actualAt = now, quantity = item.quantity, quantityUnit = item.quantityUnit, updatedAt = now))
        }
        result.outcome
    }

    suspend fun snapshot(): BackupSnapshot = db.withTransaction {
        reconcileLocked(System.currentTimeMillis())
        BackupSnapshot(cases = dao.cases(), medications = dao.medications(), schedules = dao.schedules(),
            occurrences = dao.occurrences(), intakes = dao.intakes(), expiryLocks = dao.locks())
    }

    suspend fun restoreSnapshot(snapshot: BackupSnapshot, now: Long = System.currentTimeMillis()) = db.withTransaction {
        validateSnapshot(snapshot)
        // Settle the existing database before overwriting, including missed offline dates.
        reconcileLocked(now)
        snapshot.expiryLocks.forEach { dao.putLock(it) }
        snapshot.occurrences.filter { it.skipReason == SkipReason.TIMEOUT }.forEach { dao.putLock(ExpiryLockEntity(it.scheduleId, it.date, it.deadlineAt)) }
        val locks = dao.locks().associateBy { it.scheduleId to it.date }
        dao.clearIntakes(); dao.clearOccurrences(); dao.clearSchedules(); dao.clearMedications(); dao.clearCases()
        snapshot.cases.forEach { dao.putCase(it) }
        snapshot.medications.forEach { dao.putMedication(it) }
        snapshot.schedules.forEach { dao.putSchedule(it) }
        val restoredSchedules = snapshot.schedules.associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val restored = snapshot.occurrences.map {
            val locked = applyLock(it, locks[it.scheduleId to it.date])
            val projected = SchedulePlanner.reprojectFuture(locked, restoredSchedules[locked.scheduleId], now, zone)
            ReminderReducer.reconcile(projected, now)
        }
        restored.forEach { persist(it) }
        val validTakenIds = restored.filter { it.status == OccurrenceStatus.TAKEN }.map { it.id }.toSet()
        snapshot.intakes.filter { it.occurrenceId in validTakenIds }.forEach { dao.putIntake(it) }
        reconcileLocked(now)
    }

    private fun validateImages(paths: List<String>) {
        require(paths.all { it.startsWith("images/") && !it.contains("..") && !it.contains('\\') && it.length < 250 }) { "图片路径无效" }
    }
    private fun validateMedication(item: MedicationEntity) {
        require(item.id.isNotBlank() && item.caseId.isNotBlank() && item.name.isNotBlank()) { "请填写药品名称" }
        val start = LocalDate.parse(item.startDate)
        require(item.endDate == null || LocalDate.parse(item.endDate) >= start) { "结束日期不能早于开始日期" }
        require(item.quantityUnit.isNotBlank()) { "请填写数量单位" }
        require(item.strengthValue.isBlank() || DoseCalculator.isPositive(item.strengthValue)) { "单粒含量必须大于零" }
        validateImages(item.imagePaths)
    }
    private fun validateSchedule(item: ScheduleEntity) {
        require(item.id.isNotBlank() && item.medicationId.isNotBlank()) { "提醒编号无效" }
        require(item.time.matches(Regex("\\d{2}:\\d{2}"))) { "提醒时间格式应为 HH:mm" }
        LocalTime.parse(item.time)
        require(DoseCalculator.isPositive(item.quantity)) { "每次服药数量必须大于零" }
        require(item.doseValue.isBlank() || DoseCalculator.isPositive(item.doseValue)) { "单次剂量必须大于零" }
        require(item.inputMode in setOf("COUNT", "DOSE")) { "剂量录入方式无效" }
        if (item.inputMode == "DOSE") require(DoseCalculator.isPositive(item.doseValue)) { "请填写单次总剂量" }
    }

    private fun validateSnapshot(value: BackupSnapshot) {
        require(value.formatVersion == 1) { "不支持此备份版本" }
        require(value.cases.map { it.id }.distinct().size == value.cases.size &&
            value.medications.map { it.id }.distinct().size == value.medications.size &&
            value.schedules.map { it.id }.distinct().size == value.schedules.size &&
            value.occurrences.map { it.id }.distinct().size == value.occurrences.size &&
            value.intakes.map { it.id }.distinct().size == value.intakes.size) { "备份存在重复编号" }
        val cases = value.cases.associateBy { it.id }
        val meds = value.medications.associateBy { it.id }
        val schedules = value.schedules.associateBy { it.id }
        val occurrences = value.occurrences.associateBy { it.id }
        value.cases.forEach { require(it.id.isNotBlank() && it.title.isNotBlank()); validateImages(it.prescriptionImages) }
        value.medications.forEach { validateMedication(it); require(it.caseId in cases) { "备份药品所属事项缺失" } }
        value.schedules.forEach { validateSchedule(it); require(it.medicationId in meds && it.effectiveFrom > 0 && it.effectiveFrom <= value.exportedAt + 60_000) { "备份提醒关联或启用时间无效" } }
        require(value.occurrences.map { it.scheduleId to it.date }.distinct().size == value.occurrences.size) { "备份服药日期重复" }
        value.occurrences.forEach {
            val schedule = schedules[it.scheduleId]
            require(schedule != null && schedule.medicationId == it.medicationId && meds[it.medicationId]?.caseId == it.caseId) { "备份服药事项关联错误" }
            LocalDate.parse(it.date)
            require(it.round >= 0 && it.originalAt > 0 && it.roundAt > 0 && it.deadlineAt == it.roundAt + ReminderReducer.WINDOW_MILLIS) { "备份提醒窗口错误" }
            require(DoseCalculator.isPositive(it.quantity))
            if (it.imagePath != null) validateImages(listOf(it.imagePath))
            if (it.status == OccurrenceStatus.SCHEDULED) require(it.round == 0)
            if (it.status == OccurrenceStatus.SNOOZED) require(it.round > 0)
            when (it.status) {
                OccurrenceStatus.SCHEDULED, OccurrenceStatus.PENDING, OccurrenceStatus.SNOOZED -> require(it.processedAt == null && it.skipReason == null)
                OccurrenceStatus.TAKEN -> require(it.skipReason == null && it.processedAt != null && it.processedAt >= it.roundAt && it.processedAt < it.deadlineAt)
                OccurrenceStatus.SKIPPED -> {
                    require(it.skipReason != null && it.processedAt != null)
                    if (it.skipReason == SkipReason.MANUAL) require(it.processedAt >= it.roundAt && it.processedAt < it.deadlineAt)
                    if (it.skipReason == SkipReason.TIMEOUT) require(it.processedAt >= it.deadlineAt)
                }
            }
        }
        require(value.intakes.map { it.occurrenceId }.distinct().size == value.intakes.size) { "备份服药记录重复" }
        value.intakes.forEach { require(occurrences[it.occurrenceId]?.status == OccurrenceStatus.TAKEN && DoseCalculator.isPositive(it.quantity) && it.actualAt > 0) { "备份服药记录无效" } }
        require(value.occurrences.filter { it.status == OccurrenceStatus.TAKEN }.all { occurrence -> value.intakes.any { it.occurrenceId == occurrence.id } }) { "备份已服用事项缺少记录" }
        value.expiryLocks.forEach { require(it.scheduleId.isNotBlank() && it.deadlineAt > 0); LocalDate.parse(it.date) }
    }
}
