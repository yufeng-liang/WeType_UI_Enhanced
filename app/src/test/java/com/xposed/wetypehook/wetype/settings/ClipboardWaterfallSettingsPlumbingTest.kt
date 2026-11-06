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
 * 与上游 82c05ff 之后架构对齐：外观是亮暗双 profile（Snapshot），剪贴板行为设置是全局
 * 的，挂在 `Profiles` 上无前缀落盘。设置页只 `readProfiles` 一次做状态初始化，保存时
 * 重新组一个 `Profiles` 交给 `WeTypeSettings.save`。因此这里钉三条管道：
 * `WeTypeSettings`（Profiles 字段/访问器/写盘/读回/sanitize）、MainActivity 的 UI 接线、
 * 以及 ClipboardSubPage 的回调。
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
            // Profiles 字段（剪贴板是全局设置，与 disableHotUpdate 同级；高度是最后一项，无尾逗号）
            "val clipboardWaterfallFlowEnabled: Boolean = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "val clipboardWaterfallMaxHeightDp: Int = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
            // 输入法进程侧的读取入口（全局读 Profiles，不区分亮暗）
            "readProfilesXposed().clipboardWaterfallFlowEnabled",
            "readProfilesXposed().clipboardWaterfallMaxHeightDp",
            "fun isClipboardWaterfallFlowEnabledXposed(): Boolean =",
            "fun getClipboardWaterfallMaxHeightDpXposed(): Int =",
            // 保存入口的 sanitize（开关直通、高度夹紧）
            "clipboardWaterfallMaxHeightDp = sanitizeClipboardWaterfallMaxHeightDp(",
            // 写盘路径（Profiles.toPreferenceValues 统一写，无前缀键）
            "put(KEY_CLIPBOARD_WATERFALL_FLOW, profiles.clipboardWaterfallFlowEnabled)",
            "put(KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP, profiles.clipboardWaterfallMaxHeightDp)",
            // 读取路径（toProfiles 无版本标记时 getBoolean/getInt 读回）
            "clipboardWaterfallFlowEnabled = getBoolean(",
            "DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
            // 高度夹紧
            "fun sanitizeClipboardWaterfallMaxHeightDp(value: Int): Int =",
        )
        required.forEach { snippet ->
            assertTrue("WeTypeSettings 缺少落点：$snippet", settings.contains(snippet))
        }
    }

    /** 设置页状态必须从 Profiles 初始化、并在保存时传进 WeTypeSettings.save。 */
    @Test
    fun settingsPageCarriesBothFields() {
        val required = listOf(
            // Profiles 只读一次，作为状态初始化源
            "WeTypeSettings.readProfiles(preferencesContext)",
            // 状态变量从 Profiles 初始化（rememberSaveable + mutableStateOf / mutableIntStateOf）
            "var clipboardWaterfallFlowEnabled by rememberSaveable {",
            "mutableStateOf(profiles.clipboardWaterfallFlowEnabled)",
            "var clipboardWaterfallMaxHeightDp by rememberSaveable {",
            "mutableIntStateOf(profiles.clipboardWaterfallMaxHeightDp)",
            // 保存链路把值传进 WeTypeSettings.save 的 Profiles（高度是最后一项，无尾逗号）
            "clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = clipboardWaterfallMaxHeightDp",
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
