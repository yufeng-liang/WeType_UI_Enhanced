<div align="center">
<img src="./assets/icon.png" width="120px"/>

# WeType UI Enhanced

一个以 **微信输入法（WeType）界面美化与个性化** 为主要功能的 Xposed 模块，同时为作用域其它输入法解锁 MIUI 全面屏优化限制，并为微信输入法提供剪贴板增强套件。

本项目是 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced) 与 [Costben/WeType-Enhance](https://github.com/Costben/WeType-Enhance) 两个上游项目的综合增强版：保留 NEORUAA 主线的全部美化能力，移植 Costben 的剪贴板增强，并修复悬浮键盘、图片预览返回等场景问题。


<p align="center">

![Android 12 or later](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&amp;logoColor=white)
![LSPosed 102](https://img.shields.io/badge/LSPosed-Modern_API_102-5C6BC0)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-AGPLv3-orange)

</p>
</div>

---

## 功能

### 微信输入法美化

- 自定义浅色 / 深色模式下的窗口背景颜色与透明度
- 自定义浅色 / 深色按键颜色、透明度与平滑圆角，移除按键描边和阴影
- 自定义背景模糊强度、平滑圆角及边缘高光效果
- 自定义输入法全局品牌强调色
- 单独设置浅色 / 深色候选词强调色，独立于全局品牌色
- 调节工具栏图标背景透明度
- 调节候选词背景透明度与圆角
- 调节首个候选词及候选栏拼音边距
- 键盘文字统一使用 SF Pro Text 字距，缺失字形使用微信输入法自带的 WE-Regular，模块仅内置按码点子集化的 SF Pro（不显著增大安装包体积）；功能图标使用 SF Symbols，空格键保持空白，Shift 区分大小写和锁定状态，动作键随搜索、发送、完成、前往等含义切换图标
- 悬浮键盘模式下，剪贴板 / 常用语等面板与键盘的图层遮挡关系正常合成，不会相互透出
- 设置页支持「保存并生效」一键保存并重启输入法，改动立即生效
- 支持阻止微信输入法热更新

设置页顶栏的「浅色 / 深色」用于选择当前编辑的外观方案，颜色、材质、圆角、候选词和图标可分别保存。键盘自动使用微信输入法当前主题对应的方案，支持跟随系统和宿主手动选择主题，不限手机品牌。升级后旧外观设置会沿用到两套方案，原来的浅色 / 深色背景色分别保留。

点击颜色项左侧的色块或整行，可在调色盘弹窗中选择颜色、透明度或输入 Hex 色值。每项颜色还可单独开启「跟随浅色 / 深色」，与另一方案的对应颜色保持同步，包含透明度；改为反向跟随时会自动解除原有方向。切换编辑模式会保留尚未保存的修改，点击保存时一起保存两套方案。「重置当前模式外观」只重置当前方案，仍需点击保存生效；热更新与桌面图标等通用设置由两种模式共用。

### 剪贴板增强

自 Costben/WeType-Enhance 移植并适配微信输入法 4.0.x，在设置的「剪贴板」子页分组管理：

- **基础增强**：跨设备条目可见化持久保存（多端同步的远程条目转为本地条目）；解除保留条数与时长限制（上限提升至 100,000 条、留存永久）；解除单条文本长度限制（上限提升至 1 亿字符，抑制超限提示）
- **搜索**：剪贴板页面显示搜索框，按文本拼音分词过滤；返回时自动清理搜索关键词（可关闭以保留上次结果）
- **图片缩略图**：保持原比例 / 裁剪填满 / 统一行高三选二排版；图片数量上限（默认 100 张，可选无上限）与容量上限（默认 512 MB）均可调节；点击缩略图进入大图预览，返回后回到剪贴板列表
- **排版与布局**：双列瀑布流展示，卡片超过最大高度时文本末行显示省略号、图片等比缩放，卡片最大高度可调
- **备份与恢复**：剪贴板内容导出 zip / WebDAV 备份 / 导入还原

### 高级材质与液态玻璃

在支持相应系统视效接口的 HyperOS 设备上，可在设置的「外观」分组中启用：

- **高级材质**：采用超级小爱输入法的系统磨砂背景效果，保留模块的自定义平滑圆角。
- **系统液态玻璃**：在高级材质下启用原生玻璃渲染，支持调节折射、厚度、模糊及高光等参数；使用「颜色」分组保存的浅色 / 深色背景色与透明度作为 tint。

两项功能默认关闭，需要系统视效处于开启状态；不支持的设备上，对应开关不可用。

### MIUI / HyperOS 附加功能

在支持小米全面屏键盘优化的 MIUI / HyperOS 系统上，提供三方输入法解锁全面屏键盘优化限制，解锁小米短语的包名校验，修复三方输入法无法获取系统剪贴板列表的问题。

该部分并非模块主要功能，在非小米系统上不会启用，也不影响 WeType 美化功能。

## 效果预览

默认效果为 iOS 27 Apple 官方设计稿内的配色、圆角等数值
<details open>
<summary>#FB7299 主题色截图</summary>
<table>
  <tr>
    <td><img src="./assets/prew/dark_1.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/dark_2.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/light_1.jpg" width="200" alt="浅色模式"></td>
    <td><img src="./assets/prew/light_2.jpg" width="200" alt="浅色模式"></td>
  </tr>
</table>
</details>

## 使用要求

- Android 12+
- LSPosed / 兼容的 Xposed 框架
- Xposed API Version ≥ 102
- 微信输入法

安装模块后，在 LSPosed 中启用模块并勾选 **微信输入法** 作用域，然后重启微信输入法。

模块生效后，可通过桌面入口进入设置；也可以点击微信输入法「关于」页面中的 Logo 打开寄生设置页。

部分设置修改后需要重启微信输入法进程才能完全生效；外观类改动可点击设置页的「保存并生效」按钮一步完成。

## 测试环境

设备：Xiaomi 17 Pro

HyperOS 4.0.0.27 Beta

Android 17

LSPosed v2.1.1-it (7846)

微信输入法：4.0.1

## 兼容性

模块主要针对微信输入法进行适配，美化与剪贴板功能已在微信输入法 4.0.1 实测。微信输入法内部实现、资源名称或云端热修复发生变化时，部分功能可能暂时失效。

针对宿主混淆符号随版本漂移的问题，模块对关键 hook 点（如剪贴板大图预览的返回处理）采用调用图动态识别 + 历史符号名兜底的双保险策略，尽量在宿主更新后自动完成适配。

MIUI / HyperOS 相关附加功能仅针对小米系统，不适用于其他厂商的系统级输入法优化实现。

## 下载

前往本仓库 [Releases](https://github.com/yufeng-liang/WeType_UI_Enhanced/releases) 下载最新版本。

国内网络直连 GitHub 下载较慢时，可在下载链接前加上 `https://gh-proxy.org/` 前缀加速，例如：

```
https://gh-proxy.org/https://github.com/yufeng-liang/WeType_UI_Enhanced/releases/download/v1.28.5.2/WeType_UI_Enhanced-1.28.5.2_release.apk
```

## 与上游项目的区别

- **包名**：本项目保持原包名 `com.xposed.wetypehook`，可直接覆盖安装原版与 NEORUAA 版本；Costben 自 v1.29.0 起改用独立包名 `com.costben.wetypeenhance`（可与原版共存，但不可互相覆盖）。
- **功能来源**：美化部分跟随 NEORUAA 主线（浅色 / 深色双外观方案、SF Pro 字体与 SF Symbols 图标、平滑圆角等）；剪贴板增强移植自 Costben/WeType-Enhance。
- **未包含**：Costben 的 Alpha 更新通道、模块配置导出等独立生态功能暂未移植。

## 开源致谢

感谢项目 [MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock) 提供的解锁 MIUI 全面屏优化限制功能

感谢 [miuix](https://github.com/compose-miuix-ui/miuix) 提供的 Compose UI 库

感谢上游项目 [NEORUAA/WeType_UI_Enhanced](https://github.com/NEORUAA/WeType_UI_Enhanced) 与 [Costben/WeType-Enhance](https://github.com/Costben/WeType-Enhance)

## 开源许可

本项目自 2026.8.16 起换用 AGPL-3.0 许可协议，要求修改和分发的同时也公开源码，且使用相同的许可协议。
