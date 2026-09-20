package com.chengjieli.medication.media

import org.junit.Assert.*
import org.junit.Test

class PrescriptionParserTest {
    @Test fun `four medicines preserve dose versus package quantity and unknown meal`() {
        val drafts = PrescriptionParser.parse("""
            姓名:测试 时间:2026-09-20
            1、[集采四/基/门]（欧倍妥）艾司奥美拉唑镁肠溶胶囊(20mg×30粒)
            小计:27.3 单价:27.30 数量:1盒
            口服 每日2次(餐前) 每次20mg
            2、[基/门]枸橼酸铋钾胶囊(按氧化铋计120mg×28粒)
            小计:42 数量:2盒
            口服 每日2次(餐前) 每次2粒
            3、[十五省/基/门]阿莫西林胶囊(0.25g×30粒)
            小计:16.56 数量:4盒
            口服 每日2次 每次1g
            4、[基/门]盐酸米诺环素胶囊(50mg×20粒)
            小计:138.6 数量:3盒
            口服 每日2次 每次100mg
        """.trimIndent())
        assertEquals(4, drafts.size)
        assertEquals("艾司奥美拉唑镁肠溶胶囊", drafts[0].name)
        assertEquals("20", drafts[0].strengthValue)
        assertEquals("20", drafts[0].doseValue)
        assertEquals("", drafts[0].quantity)
        assertEquals("饭前", drafts[0].mealNote)
        assertEquals("2", drafts[1].quantity)
        assertEquals("粒", drafts[1].quantityUnit)
        assertEquals("", drafts[1].doseValue)
        assertEquals("0.25", drafts[2].strengthValue)
        assertEquals("g", drafts[2].strengthUnit)
        assertEquals("1", drafts[2].doseValue)
        assertEquals("", drafts[2].mealNote)
        assertEquals("每日2次", drafts[3].frequencyText)
    }

    @Test fun `unknown image returns editable raw draft without inventing medication`() {
        val text = "文字模糊无法确定\n数量3盒 合计128.00"
        val draft = PrescriptionParser.parse(text).single()
        assertEquals(text, draft.rawText)
        assertEquals("", draft.name)
        assertEquals("", draft.quantity)
        assertEquals("", draft.frequencyText)
        assertTrue(PrescriptionParser.parse("  \n ").isEmpty())
    }

    @Test fun `package quantity never becomes dosage and unclear strength stays blank`() {
        val draft = PrescriptionParser.parse("复方测试胶囊\n数量:3盒\n规格:复方\n每天两次 饭后").single()
        assertEquals("", draft.quantity)
        assertEquals("", draft.doseValue)
        assertEquals("", draft.strengthValue)
        assertEquals("饭后", draft.mealNote)
        assertEquals("每天两次", draft.frequencyText)
    }

    @Test fun `explicit unit content and tablet quantity accepted`() {
        val draft = PrescriptionParser.parse("测试片 5mg/片\n每次0.5片 随餐 每8小时1次").single()
        assertEquals("5", draft.strengthValue)
        assertEquals("0.5", draft.quantity)
        assertEquals("每8小时1次", draft.frequencyText)
        assertEquals("随餐", draft.mealNote)
    }

    @Test fun `compound strength never used as single ingredient conversion`() {
        val draft = PrescriptionParser.parse("复方测试胶囊(20mg×30粒)\n每次2粒").single()
        assertEquals("20mg×30粒", draft.specification)
        assertEquals("", draft.strengthValue)
        assertEquals("2", draft.quantity)
    }
}
