package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板双列瀑布流的两项设置（开关 + 卡片最大高度）必须落满「能存能读」的每一个点。
 *
 * 漏掉任何一处都不是编译错误：表现是设置页里点了没反应、或者重启后被悄悄改回默认，
 * 从现象根本倒推不出来。
 *
 * 本仓库架构与上游 ref 不同：设置页（MainActivity）直连 `WeTypeSettings`
 * 快照读写，没有中间的 State/StateSaver 层，因此这里钉两条管道：
 * `WeTypeSettings` 快照（读/写/默认值/sanitize）与 MainActivity 的 UI 接线。
 */
class ClipboardWaterfallSettingsPlumbingTest {

    private val settings = File(
        "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
    ).readText()
    private val mainActivity = File(
        "src/main/java/com/xposed/wetypehook/MainActivity.kt"
    ).readText()
    private val subPage = File(
        "src/main/java/com/xposed/wetypehook/ClipboardSubPage.kt"
    ).readText()

    @Test
    fun settingsObjectLandsOnEveryReadWriteSite() {
        val required = listOf(
            // 键与默认值
            "const val KEY_CLIPBOARD_WATERFALL_FLOW = \"clipboard_waterfall_flow_enabled\"",
            "const val KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP = \"clipboard_waterfall_max_height_dp\"",
            "const val DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED = false",
            "const val DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP = 140",
            // 快照字段
            "val clipboardWaterfallFlowEnabled: Boolean = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "val clipboardWaterfallMaxHeightDp: Int = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 输入法进程侧的读取入口（本仓库无 Context 版入口，只有 Xposed 版）
            "readSnapshotXposed().clipboardWaterfallFlowEnabled",
            "readSnapshotXposed().clipboardWaterfallMaxHeightDp",
            "fun isClipboardWaterfallFlowEnabledXposed(): Boolean =",
            "fun getClipboardWaterfallMaxHeightDpXposed(): Int =",
            // 整页保存入口的参数
            "clipboardWaterfallFlowEnabled: Boolean = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "clipboardWaterfallMaxHeightDp: Int = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 组装快照（本地 sanitize 调用换行为两行，逐点仍在）
            "clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = sanitizeClipboardWaterfallMaxHeightDp(",
            // 写盘路径（本地只有 writeSnapshot 一条，以 snapshot. 形式写）
            ".putBoolean(KEY_CLIPBOARD_WATERFALL_FLOW, snapshot.clipboardWaterfallFlowEnabled)",
            "KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 读取路径（本地 toSnapshot 直接用 DEFAULT_ 常量，无 defaults 变量）
            "clipboardWaterfallFlowEnabled = getBoolean(",
            "DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
            // 默认快照（高度是最后一项，无尾逗号）
            "clipboardWaterfallFlowEnabled = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "clipboardWaterfallMaxHeightDp = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
            // 高度夹紧
            "fun sanitizeClipboardWaterfallMaxHeightDp(value: Int): Int =",
        )
        required.forEach { snippet ->
            assertTrue("WeTypeSettings 缺少落点：$snippet", settings.contains(snippet))
        }
    }

    /** 设置页状态必须从快照初始化、并在恢复默认时重置回默认值。 */
    @Test
    fun settingsPageCarriesBothFields() {
        val required = listOf(
            // 状态变量从快照初始化（rememberSaveable + mutableStateOf / mutableIntStateOf）
            "var clipboardWaterfallFlowEnabled by rememberSaveable {",
            "mutableStateOf(snapshot.clipboardWaterfallFlowEnabled)",
            "var clipboardWaterfallMaxHeightDp by rememberSaveable {",
            "mutableIntStateOf(snapshot.clipboardWaterfallMaxHeightDp)",
            // 恢复默认
            "clipboardWaterfallFlowEnabled = WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED",
            "clipboardWaterfallMaxHeightDp = WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
            // 保存链路把值传进 WeTypeSettings.save
            "clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = clipboardWaterfallMaxHeightDp,",
        )
        required.forEach { snippet ->
            assertTrue("MainActivity 缺少落点：$snippet", mainActivity.contains(snippet))
        }
    }

    /** 设置页必须真的把两个回调接上，否则 UI 上点了不动。 */
    @Test
    fun settingsSubPageWiresBothCallbacks() {
        assertTrue(mainActivity.contains("clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,"))
        assertTrue(mainActivity.contains("onClipboardWaterfallFlowEnabledChange = { clipboardWaterfallFlowEnabled = it },"))
        assertTrue(mainActivity.contains("clipboardWaterfallMaxHeightDp = clipboardWaterfallMaxHeightDp,"))
        assertTrue(mainActivity.contains("onClipboardWaterfallMaxHeightDpChange = { clipboardWaterfallMaxHeightDp = it },"))
        assertTrue(subPage.contains("onClipboardWaterfallFlowEnabledChange: (Boolean) -> Unit,"))
        assertTrue(subPage.contains("onClipboardWaterfallMaxHeightDpChange: (Int) -> Unit,"))
    }

    @Test
    fun maxHeightSanitizerClampsToTheSupportedRange() {
        assertEquals(
            WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(
                WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP
            )
        )
        assertEquals(
            WeTypeSettings.CLIPBOARD_WATERFALL_MIN_HEIGHT_DP,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(1)
        )
        assertEquals(
            WeTypeSettings.CLIPBOARD_WATERFALL_MAX_HEIGHT_DP_LIMIT,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(10_000)
        )
        assertEquals(160, WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(160))
    }
}
