package com.chengjieli.medication.media

data class OcrDrugDraft(
    val name: String = "", val specification: String = "", val strengthValue: String = "",
    val strengthUnit: String = "", val quantity: String = "", val quantityUnit: String = "",
    val doseValue: String = "", val doseUnit: String = "", val frequencyText: String = "",
    val mealNote: String = "", val rawText: String = ""
)

/** Conservative extraction; uncertain text stays in the draft, never schedules reminders. */
object PrescriptionParser {
    private val form = Regex("[\\u4e00-\\u9fffA-Za-z][\\u4e00-\\u9fffA-Za-z0-9·\\-]{1,45}?(?:肠溶胶囊|缓释胶囊|肠溶片|缓释片|分散片|胶囊|颗粒|口服液|混悬液|滴丸|丸剂|片|散|膏)(?=[（(\\s]|$)")
    private val strength = Regex("(?:^|[（(：:\\s])([0-9]+(?:\\.[0-9]+)?)\\s*(mg|g|毫克|克)\\s*(?:[/／]\\s*(粒|片)|[xX×*])", RegexOption.IGNORE_CASE)
    private val single = Regex("每次\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(mg|g|毫克|克|粒|片|丸|袋|包|滴|毫升|ml)", RegexOption.IGNORE_CASE)
    private val frequency = Regex("(?:每日|每天|一日)\\s*([0-9一二两三四五六七八九十]+)\\s*次|每\\s*([0-9]+)\\s*小时\\s*(?:一次|1次)")
    private val spec = Regex("[（(]([^()（）]*?(?:mg|g|毫克|克|ml|毫升)[^()（）]*)[）)]", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<OcrDrugDraft> {
        val normalized = text.replace('：', ':').replace('，', ',').replace("㎎", "mg")
            .replace("ｍｇ", "mg").replace("ＭＧ", "mg").replace('\r', '\n')
        val lines = normalized.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            val clean = line.replace(Regex("[\\[【][^]】]*[]】]"), "").trim()
            val name = form.find(clean)?.value
            // A drug dosage form is a stronger boundary than numbers (which may be prices).
            if (name != null && !line.startsWith("每次")) {
                current = mutableListOf(line)
                groups += current
            } else current?.add(line)
        }
        if (groups.isEmpty()) return listOf(OcrDrugDraft(rawText = normalized))
        return groups.map { linesInDrug ->
            val block = linesInDrug.joinToString("\n")
            val heading = linesInDrug.first().replace(Regex("[\\[【][^]】]*[]】]"), "")
                .replace(Regex("^\\s*\\d+\\s*[、,.．]\\s*"), "")
            val amount = single.find(block)
            val unit = amount?.groupValues?.get(2)?.lowercase().orEmpty()
            val isCount = unit in setOf("粒", "片", "丸", "袋", "包", "滴", "毫升", "ml")
            val specification = spec.find(heading)?.groupValues?.get(1).orEmpty()
            // A unit amount followed by a package multiplier or explicit /tablet is unambiguous.
            val content = if (heading.contains("复方")) null else strength.find(heading)
            OcrDrugDraft(
                name = form.find(heading)?.value.orEmpty().trim(), specification = specification,
                strengthValue = content?.groupValues?.get(1).orEmpty(),
                strengthUnit = normalizeMass(content?.groupValues?.get(2).orEmpty()),
                quantity = if (isCount) amount?.groupValues?.get(1).orEmpty() else "",
                quantityUnit = if (isCount) unit else "",
                doseValue = if (!isCount) amount?.groupValues?.get(1).orEmpty() else "",
                doseUnit = if (!isCount) normalizeMass(unit) else "",
                frequencyText = frequency.find(block)?.value.orEmpty(),
                mealNote = when {
                    block.contains("餐前") || block.contains("饭前") -> "饭前"
                    block.contains("餐后") || block.contains("饭后") -> "饭后"
                    block.contains("随餐") -> "随餐"
                    else -> ""
                }, rawText = block
            )
        }
    }

    private fun normalizeMass(unit: String) = when (unit.lowercase()) { "毫克", "mg" -> "mg"; "克", "g" -> "g"; else -> unit }
}
