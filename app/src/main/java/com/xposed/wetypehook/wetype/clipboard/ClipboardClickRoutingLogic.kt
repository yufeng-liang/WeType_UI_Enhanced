package com.xposed.wetypehook.wetype.clipboard

/**
 * 剪贴板条目点击路由决策（纯逻辑，不引用 Android/宿主类，供单测与 Hook 层共用）。
 *
 * 背景：宿主 ViewHolder 的 `onClick(View)` 是图片条目的唯一点击入口，
 * 展开后的操作栏按钮（＋常用语 / 选词 / 删除 / 确认删除）也走同一条路径。
 * 模块为了「点击整行进大图预览、禁止直接粘贴」而拦截这条 onClick，
 * 因此必须先把操作栏的点击整体放行给宿主，否则「删除」会被当成整行点击弹预览。
 */
internal object ClipboardClickRoutingLogic {

    /**
     * 点击链里是否命中操作栏。逆序扫描：从点击视图自身开始，逐层向上找带 id 的祖先。
     *
     * @param ancestry 点击视图起、沿父链向上的视图 id 序列（自身在首位），0 表示无 id
     * @param actionBarIds 操作栏视图 id 名单；为空时整条判定退化为「未命中」
     */
    fun isActionBarHit(ancestry: List<Int>, actionBarIds: Set<Int>): Boolean {
        if (actionBarIds.isEmpty()) return false
        for (id in ancestry) {
            if (id != 0 && id in actionBarIds) return true
        }
        return false
    }
}
