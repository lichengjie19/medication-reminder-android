package com.chengjieli.medication.backup

import com.chengjieli.medication.data.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Strict JSON shape checking prevents Gson's null/default coercions from accepting broken data. */
object SnapshotCodec {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    fun encode(snapshot: BackupSnapshot): String = gson.toJson(snapshot)

    fun decode(json: String): BackupSnapshot {
        require(json.length <= 24 * 1024 * 1024) { "备份数据过大" }
        val root = JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = readValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "备份 JSON 尾部有多余内容" }
            result.asJsonObject
        }
        shape(root, longs = "formatVersion exportedAt")
        require(root["formatVersion"].asLong == 1L) { "不支持的备份版本" }
        records(root, "cases") {
            shape(it, strings = "id title cause notes", longs = "createdAt")
            enum<PlanStatus>(it, "status"); strings(it, "prescriptionImages")
        }
        records(root, "medications") {
            shape(it, strings = "id caseId name specification strengthValue strengthUnit quantityUnit startDate mealNote notes", bools = "active")
            optionalString(it, "endDate"); strings(it, "imagePaths")
        }
        records(root, "schedules") {
            shape(it, strings = "id medicationId time quantity doseValue doseUnit inputMode", longs = "effectiveFrom", bools = "enabled")
        }
        records(root, "occurrences") {
            shape(it, strings = "id scheduleId medicationId caseId date medicineName caseTitle quantity quantityUnit doseValue doseUnit mealNote notes",
                longs = "originalAt roundAt deadlineAt round")
            require(it["round"].asLong in 0..Int.MAX_VALUE.toLong()) { "提醒轮次无效" }
            enum<OccurrenceStatus>(it, "status")
            if (it.has("skipReason") && !it["skipReason"].isJsonNull) enum<SkipReason>(it, "skipReason")
            optionalString(it, "imagePath")
            if (it.has("processedAt") && !it["processedAt"].isJsonNull) requireLong(it, "processedAt")
        }
        records(root, "intakes") {
            shape(it, strings = "id occurrenceId quantity quantityUnit notes", longs = "actualAt updatedAt")
        }
        records(root, "expiryLocks") { shape(it, strings = "scheduleId date", longs = "deadlineAt") }
        return gson.fromJson(root, BackupSnapshot::class.java).also(BackupValidator::validate)
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "备份 JSON 嵌套过深" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name.length <= 128 && !result.has(name)) { "备份 JSON 字段重复或无效" }
                    result.add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(result.size() < BackupValidator.MAX_RECORDS) { "备份数组过大" }
                    result.add(readValue(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 65_536) { "备份文字过长" } })
            JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toLongOrNull() ?: error("备份数值无效"))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("备份 JSON 无效")
        }
    }
    private fun shape(obj: JsonObject, strings: String = "", longs: String = "", bools: String = "") {
        strings.split(' ').filter(String::isNotEmpty).forEach { requireString(obj, it) }
        longs.split(' ').filter(String::isNotEmpty).forEach { requireLong(obj, it) }
        bools.split(' ').filter(String::isNotEmpty).forEach {
            require(obj[it]?.isJsonPrimitive == true && obj[it].asJsonPrimitive.isBoolean) { "备份布尔字段 $it 无效" }
        }
    }
    private fun requireString(obj: JsonObject, key: String) {
        require(obj[key]?.isJsonPrimitive == true && obj[key].asJsonPrimitive.isString) { "备份文字字段 $key 无效" }
    }
    private fun optionalString(obj: JsonObject, key: String) { if (obj.has(key) && !obj[key].isJsonNull) requireString(obj, key) }
    private fun requireLong(obj: JsonObject, key: String) {
        require(obj[key]?.isJsonPrimitive == true && obj[key].asJsonPrimitive.isNumber && obj[key].asString.toLongOrNull() != null) { "备份整数字段 $key 无效" }
    }
    private fun records(obj: JsonObject, key: String, check: (JsonObject) -> Unit) {
        require(obj[key]?.isJsonArray == true) { "备份缺少记录数组 $key" }
        obj[key].asJsonArray.forEach { require(it.isJsonObject) { "备份记录 $key 无效" }; check(it.asJsonObject) }
    }
    private fun strings(obj: JsonObject, key: String) {
        require(obj[key]?.isJsonArray == true && obj[key].asJsonArray.size() <= 100) { "备份图片列表无效" }
        obj[key].asJsonArray.forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "备份图片路径无效" } }
    }
    private inline fun <reified T : Enum<T>> enum(obj: JsonObject, key: String) {
        requireString(obj, key)
        require(enumValues<T>().any { it.name == obj[key].asString }) { "备份枚举字段 $key 无效" }
    }
}
