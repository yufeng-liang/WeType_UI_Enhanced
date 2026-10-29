package com.xposed.wetypehook

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xposed.wetypehook.wetype.settings.WeTypeProcessRestarter
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok

/**
 * 设置界面顶栏唯一的动作按钮：按下即「保存 + 让输入法立刻生效」。
 *
 * 上游 Costben 的模块把这两个动作拆成两颗按钮（✓ 只落盘，⟳ 才重启输入法进程）。本模块
 * 合成一颗，原因是设置页寄生在宿主进程里，用户改完要立刻看到键盘外观变化；拆两颗的话，
 * 只点 ✓ 的那一次会让人以为设置没生效。
 *
 * 落盘成功之后调 [applySettingsToImeProcess] 重启 `com.tencent.wetype:hld`。本模块的改动
 * 平时靠 `WeTypeSettings.ensureHostSnapshot()` 上的 FileObserver 自动失效重读，这一步是
 * 「现在就要看到」的显式触发；重启失败不影响已经写入磁盘的值。
 *
 * 图标沿用 ✓：合并后的主语义仍是「把改动存下去」，重启是紧随其后的收尾动作。
 */
@Composable
internal fun SettingsApplyButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = MiuixIcons.Ok,
            contentDescription = stringResource(R.string.settings_apply_title)
        )
    }
}

/** 让输入法进程重新读取已写入的配置。 */
internal fun applySettingsToImeProcess(context: Context): Boolean =
    WeTypeProcessRestarter.restartImeProcess(context)
