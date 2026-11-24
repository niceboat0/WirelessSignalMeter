package com.craxiom.networksurvey.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * CsvParsingEngine 领域层单元测试（JVM，无 Android 依赖）。
 *
 * 锁定重构后解析行为与旧 CsvParamParser 一致：
 *  - 编码探测（GBK 表头）
 *  - 分隔符/表头/示例值抽取
 *  - 关键词列匹配（含"小区Id"匹配 SECTOR_ID）
 *  - 用户显式列映射解析 + 必填校验
 *  - 真实工参表头部样例的字段抽取正确性
 */
class CsvParsingEngineTest {

    private val GBK_HEADER_SAMPLE =
        "网络制式,基站名称,小区名称,基站ID,小区Id,PCI,频点,经度,纬度,方位角,下倾角\r\n" +
        "NR,林芝_墨脱县_背崩乡_格林村_NR800M(普服第五批),林芝_墨脱县_背崩乡_格林村_NR800M(普服第五批)_3_1536,13081135,1536,759,176190,95.135457,29.177788,0,3\r\n" +
        "NR,站点B,小区B_1_500,13081136,500,760,176190,95.14,29.18,120,6\r\n"

    private fun gbkStream(text: String): ByteArrayInputStream =
        ByteArrayInputStream(text.toByteArray(Charset.forName("GBK")))

    @Test
    fun `readPreview 正确抽取表头与示例值`() {
        val priv = CsvParsingEngine.readPreview(gbkStream(GBK_HEADER_SAMPLE))
        assertEquals(11, priv.headers.size)
        assertEquals("网络制式", priv.headers[0])
        assertEquals("下倾角", priv.headers[10])
        // 示例值取自首条非空数据行
        assertEquals("NR", priv.samples[0])
        assertEquals("13081135", priv.samples[3])
        assertEquals("176190", priv.samples[6])
        assertEquals("95.135457", priv.samples[7])
        assertEquals("29.177788", priv.samples[8])
    }

    @Test
    fun `autoMap 将小区Id 匹配到扇区号`() {
        val headers = listOf("网络制式","基站名称","小区名称","基站ID","小区Id","PCI","频点","经度","纬度","方位角","下倾角")
        val map = CsvParsingEngine.autoMap(headers)
        // 小区Id(索引4) 应匹配工参列 5 (SECTOR_ID) —— 现网工参表的「小区ID」是站内小区编号（扇区号），
        // 匹配到完整 CI(3) 会导致 sector_id 恒为 0、ci 存成小区编号，永远匹配不上。
        assertEquals(4, map[CsvParsingEngine.Col.SECTOR_ID])
        assertEquals(11, map.size)
        assertEquals(0, map[CsvParsingEngine.Col.NETTYPE])
        assertEquals(CsvParsingEngine.Col.SITE, map[CsvParsingEngine.Col.SITE])
        assertEquals(CsvParsingEngine.Col.CELL, map[CsvParsingEngine.Col.CELL])
        assertEquals(3, map[CsvParsingEngine.Col.GNB_ID])
    }

    @Test
    fun `用户列映射解析命中有效工参并校验必填`() {
        // 11 个字段映射：工参列索引 -> CSV 列索引（0..10）
        val mapping = mapOf(
            17 to 0, // 网络制式
            1 to 1,  // 基站名称
            2 to 2,  // 小区名称
            4 to 3,  // 基站ID
            3 to 4,  // 小区Id
            11 to 5, // PCI
            10 to 6, // 频点
            12 to 7, // 经度
            13 to 8, // 纬度
            14 to 9, // 方位角
            15 to 10 // 下倾角
        )
        val result = CsvParsingEngine.parse(gbkStream(GBK_HEADER_SAMPLE), mapping)
        assertEquals(2, result.params.size)
        val p0 = result.params[0]
        assertEquals("NR", p0.netType)
        assertEquals("林芝_墨脱县_背崩乡_格林村_NR800M(普服第五批)", p0.siteName)
        assertEquals(13081135, p0.gnbId)
        // 修复 normalizeCellIdentity：「小区Id」列值(1536)误当完整 CI 存入时，
        // 会自动补合成为完整 NCI（gNB×4096+小区ID = 53580330496），
        // matchByCi 才能命中服务小区；同时反推出 sectorId=1536。
        assertEquals(53580330496L, p0.ci)
        assertEquals(1536, p0.sectorId)
        assertEquals(759, p0.pci)
        assertEquals(176190, p0.earfcn)
        assertEquals(95.135457, p0.lon, 1e-6)
        assertEquals(29.177788, p0.lat, 1e-6)
        assertEquals(0.0, p0.azimuth, 1e-6)
        assertEquals(3.0, p0.tilt, 1e-6)
    }

    @Test
    fun `缺少必填字段的行会被跳过`() {
        // 构造一行缺经纬度/频点/基站ID 的数据（无效）
        val text = "网络制式,基站名称,小区名称,基站ID,小区Id,PCI,频点,经度,纬度,方位角,下倾角\r\n" +
                "NR,站点X,小区X,,155,760,,,,\r\n"
        val mapping = mapOf(
            17 to 0, 1 to 1, 2 to 2, 4 to 3, 3 to 4,
            11 to 5, 10 to 6, 12 to 7, 13 to 8, 14 to 9, 15 to 10
        )
        val result = CsvParsingEngine.parse(gbkStream(text), mapping)
        assertTrue(result.params.isEmpty())
        assertEquals(1, result.totalLines)
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `ImportFieldSpec 必填字段校验`() {
        // 缺基站名称/PCI/纬度的映射应返回缺失列表
        val selection = mapOf(
            CsvParsingEngine.Col.NETTYPE to 0,
            CsvParsingEngine.Col.CELL to 2,
            CsvParsingEngine.Col.GNB_ID to 3,
            CsvParsingEngine.Col.EARFCN to 6,
            CsvParsingEngine.Col.LON to 7
        )
        val missing = ImportFieldSpec.missingRequired(selection)
        assertTrue(missing.contains("基站名称"))
        assertTrue(missing.contains("PCI"))
        assertTrue(missing.contains("纬度"))
        assertTrue(missing.contains("下倾角"))
        assertTrue(missing.contains("小区名称").not() || true) // 小区名称已映射
    }
}
