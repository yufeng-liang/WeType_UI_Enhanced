package com.xposed.wetypehook.wetype.hook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewParent
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageEntryLogic
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.HostResources
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.host.pickHostMethod
import com.xposed.wetypehook.xposed.Log as XLog
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 剪贴板图片增强的宿主反射句柄（C/z 数据与视图、尺寸换算、S33 导航）。
 *
 * 3.5.3 与 3.5.4 实证：
 * - C 访问器二进制名一致：q()=type、i()=path、j()=pathType、k()=receiveTimestampFromServer、
 *   f()=id、z(String)=setPath、A(int)=setPathType；
 * - 尺寸换算类 3.5.3=`...utils.m1`、3.5.4=`...utils.n1`（实例单例 + l0(Integer)）；
 *   原始设计 px 换算 3.5.3=`...utils.q1.e0`、3.5.4=`...utils.r1.e0`（静态）；
 *   两条都先走 HostContract（`clipboard.scale.instance` / `clipboard.scale.static`），
 *   下面的候选表只作兜底，取类后仍按 l0/e0 形状复核。
 * - 面板枚举二进制名一致 `...keyboard.t`（含 ImagePreview(509)）；
 * - 面板导航：3.5.4 上 `(面板枚举, Bundle) -> void` 命中的是 `N#o3`
 *   （`N#t3` 形状相同但语义是切键盘，不在候选表内、不能选它）；
 *   按 int 值切面板的唯一入口是 `N#l3`。
 * - 导航优先走 HostContract（`panel.enum` / `panel.manager` / `panel.manager.instance` /
 *   `panel.switch.enum` / `panel.constant.clipboard`），下面的名字候选表 n3/p3/o3/k3/l3 只作兜底。
 */
internal object WeTypeClipboardImageHost {

    const val TAG = "WeTypeClipboardImage"
    const val EXPIRY_EXTEND_WINDOW_MS = 24L * 60 * 60 * 1000L
    const val EXPIRY_EXTEND_TARGET_MS = 30L * 24 * 60 * 60 * 1000L

    private const val IDS_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val DIMEN_CLASS = "com.tencent.wetype.plugin.hld.q"
    private const val PANEL_ENUM_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"
    private const val N_CLASS = "com.tencent.wetype.plugin.hld.model.N"
    private const val CLIPBOARD_ITEM_CLASS = "com.tencent.wetype.plugin.hld.clipboard.C"
    private const val IMAGE_CARD_CLASS = "com.tencent.wetype.plugin.hld.keyboard.k"
    private const val PREVIEW_HOST_CLASS = "com.tencent.wetype.plugin.hld.keyboard.l"
    private const val PREVIEW_KEYBOARD_CLASS =
        "com.tencent.wetype.plugin.hld.keyboard.S33ImagePreviewKeyboard"

    /** 预览页消失后延迟多少毫秒再撤销透明遮罩（等宿主退出动画结束，避免预览页里透出键盘）。 */
    private const val PREVIEW_RESTORE_DELAY_MS = 400L

    /** 尺寸换算单例的候选短名（`clipboard.scale.instance` 契约失效时的最后手段）。 */
    private val SCALE_INSTANCE_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.n1",
        "com.tencent.wetype.plugin.hld.utils.m1"
    )

    /** 原始像素换算静态工具的候选短名（`clipboard.scale.static` 契约失效时的最后手段）。 */
    private val RAW_SCALE_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.r1",
        "com.tencent.wetype.plugin.hld.utils.q1"
    )

    /**
     * 展开操作栏的视图 id 字段名。图片条目的点击路由按这份名单整体放行给宿主，
     * 顺序与 `WeTypeClipboardWaterfallHooks` 里挪按钮用的一致。
     */
    private val ACTION_BAR_ID_FIELDS = arrayOf(
        "clipboard_action_container",
        "clipboard_action_add_container",
        "clipboard_action_add_icon",
        "clipboard_action_add_text",
        "clipboard_action_bomb_container",
        "clipboard_action_bomb_icon",
        "clipboard_action_bomb_text",
        "clipboard_action_delete",
        "clipboard_action_delete_icon",
        "clipboard_action_delete_confirm"
    )

    private const val KEY_CLIPBOARD_ID = "key_clipboard_id"
    private const val KEY_TARGET_TAB_INDEX = "target_tab_index"
    private const val KEY_FROM = "key_from"
    private const val PANEL_IMAGE_PREVIEW = "ImagePreview"
    private const val PANEL_CLIPBOARD = "CustomPhraseAndClipboard"
    private const val CLIPBOARD_TAB_INDEX = 0

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var itemClass: Class<*>? = null

    @Volatile
    private var scaleInstance: Any? = null

    @Volatile
    private var scaleL0Method: Method? = null

    @Volatile
    private var rawScaleMethod: Method? = null

    @Volatile
    private var panelClass: Class<*>? = null

    @Volatile
    private var navManager: Any? = null

    @Volatile
    private var navMethod: Method? = null

    @Volatile
    private var navPanel: Any? = null

    @Volatile
    private var navClipboardPanel: Any? = null

    @Volatile
    var contentTvId: Int = 0
        private set

    @Volatile
    var contentScrollId: Int = 0
        private set

    @Volatile
    var line1Id: Int = 0
        private set

    @Volatile
    var keyInfoId: Int = 0
        private set

    @Volatile
    var moreBtnId: Int = 0
        private set

    /**
     * 展开操作栏（＋常用语 / 选词 / 删除 / 确认删除）里全部视图 id。
     * 图片条目的点击路由要把这些点击还给宿主：漏一个就会把「删除」当整行点击，弹出大图预览。
     */
    @Volatile
    var actionBarViewIds: Set<Int> = emptySet()
        private set

    /**
     * 操作栏里「＋常用语」与「选词」两颗按钮的容器 id。
     * 图片条目只保留「删除」—— 这两颗对图片没有任何意义，绑定图片条目时要把它们藏起来。
     */
    @Volatile
    var actionAddViewId: Int = 0
        private set

    @Volatile
    var actionBombViewId: Int = 0
        private set

    @Volatile
    var line1HeightRes: Int = 0
        private set

    @Volatile
    var line2HeightRes: Int = 0
        private set

    @Volatile
    var inputIndentRes: Int = 0
        private set

    private val getterCache = ConcurrentHashMap<String, Method>()
    private val setterCache = ConcurrentHashMap<String, Method>()
    private val holderItemFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val previewStates =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, PreviewState>())

    /** 已挂过 attach 监听的预览页实例：宿主复用同一实例，监听必须常驻。 */
    private val trackedPreviews =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())

    @Volatile
    private var runtimeReady = false

    /**
     * 预览页显示期间的状态：宿主把键盘页与候选栏留在预览页底下，
     * 这里把它们临时隐藏，让透明预览页直接露出键盘 carrier。
     */
    private class PreviewState(
        val container: ViewGroup,
        val decor: View,
        val layoutListener: ViewTreeObserver.OnGlobalLayoutListener,
        val observer: ViewTreeObserver
    )

    /**
     * 被预览页隐藏的兄弟层：容器 -> (子视图 -> 隐藏前的 alpha)。
     *
     * 按容器登记而不是每次预览各自记一份：宿主可能同时存在多个预览页实例，
     * 若各自记录，后一次会把前一次留下的 `0f` 当成原始值，恢复后键盘永久透明。
     */
    private val hiddenSiblings = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<ViewGroup, MutableMap<View, Float>>()
    )

    /**
     * 丢弃全部宿主类句柄、反射缓存与资源 ID，热重载后由 [install]/[prepareRuntime] 按新
     * ClassLoader 重新定位。getter/setter 缓存的键含宿主混淆名，跨代复用会指向旧类的方法。
     */
    fun prepareForHotReload() {
        installed = false
        runtimeReady = false
        hostClassLoader = null
        itemClass = null
        scaleInstance = null
        scaleL0Method = null
        rawScaleMethod = null
        panelClass = null
        navManager = null
        navMethod = null
        navPanel = null
        navClipboardPanel = null
        contentTvId = 0
        contentScrollId = 0
        line1Id = 0
        keyInfoId = 0
        moreBtnId = 0
        actionBarViewIds = emptySet()
        actionAddViewId = 0
        actionBombViewId = 0
        line1HeightRes = 0
        line2HeightRes = 0
        inputIndentRes = 0
        getterCache.clear()
        setterCache.clear()
        holderItemFieldCache.clear()
        // 先撤销预览页留下的透明遮罩：否则热重载后键盘会永久看不见（只能打字）。
        restorePreviewLayers()
        previewStates.clear()
        trackedPreviews.clear()
    }

    /**
     * 只做零初始化的类加载与资源 ID 读取。
     * 禁止在此读宿主静态字段/枚举：宿主 `utils.m1` 的 <clinit> 会碰 MMKV，
     * 在 Application 早启动阶段触发会直接杀死微信输入法进程（实机实证）。
     */
    fun install(classLoader: ClassLoader): Boolean {
        if (installed) return true
        hostClassLoader = classLoader
        return try {
            itemClass = resolveClipboardItem(classLoader)
                ?: throw ClassNotFoundException(CLIPBOARD_ITEM_CLASS)
            resolveIds(classLoader)
            runCatching { hookImagePreviewBack(classLoader) }
                .onFailure { XLog.e("[$TAG] image preview back hook failed: ${it.message}") }
            runCatching { hookImagePreviewSaveAction(classLoader) }
                .onFailure { XLog.e("[$TAG] image preview save hook failed: ${it.message}") }
            runCatching { hookPreviewBackdrop(classLoader) }
                .onFailure { XLog.e("[$TAG] image preview backdrop hook failed: ${it.message}") }
            installed = true
            XLog.i("[$TAG] image host handles installed (line1=$line1HeightRes line2=$line2HeightRes)")
            true
        } catch (t: Throwable) {
            XLog.e("[$TAG] image host handles unavailable: ${t.message}")
            false
        }
    }

    /**
     * 图片预览页（S33）的左上角“收回”走宿主 `keyboard.l` 的返回处理：3.x 是 B0 ()V，
     * 4.0.1 起挪到 A0 ()V（见下方查找注释）。模块是直接用 `N.n3(ImagePreview, Bundle)`
     * 切页的，不在宿主页面栈里，原生收回会落到普通键盘。这里拦截 S33 的收回并手动
     * 导航回剪贴板面板。
     *
     * 注意：宿主切回面板时会把列表滚动重置到顶部（实测 m00559），页面未销毁并不等于
     * 滚动位置还在——所以面板重开后要调 [scheduleScrollRestore] 恢复原滚动位置。
     */
    private fun hookImagePreviewBack(classLoader: ClassLoader) {
        val previewClass = resolvePreviewKeyboard(classLoader) ?: run {
            XLog.e("[$TAG] image preview keyboard class missing")
            return
        }
        val baseClass = resolvePreviewHost(classLoader)
            ?: throw ClassNotFoundException(PREVIEW_HOST_CLASS)
        // 3.x：返回处理是 B0 ()V；4.0.1 起 B0 变成 ()Z 且被 y0 样式刷新调用（预览页
        // 打开即触发，拦它会直接把预览页切走），返回语义挪到了 A0 ()V——返回按钮的
        // click 回调 l$a.a(View) 直接调 A0。优先走 CLIPBOARD_PREVIEW_BACK 契约的
        // DexKit 调用图识别（唯一调用面板管理 N 的无参 void 方法），名字 A0/B0 只做
        // 兜底；两级都失败才放弃拦截。
        val backMethod = runCatching {
            WeTypeHostContracts.methodOf(HostContractId.CLIPBOARD_PREVIEW_BACK)
        }.getOrNull() ?: pickHostMethod(baseClass, "A0", "B0") {
            it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: run {
            XLog.e("[$TAG] image preview back method missing (keyboard.l#A0/B0)")
            return
        }
        backMethod.isAccessible = true
        backMethod.hookBefore { param ->
            val self = param.thisObject ?: return@hookBefore
            if (!previewClass.isInstance(self)) return@hookBefore
            if (openClipboardPanel()) {
                scheduleScrollRestore()
                XLog.i("[$TAG] image preview back -> clipboard panel")
                param.result = null
            }
        }
        XLog.i("[$TAG] hooked image preview back (keyboard.l#${backMethod.name})")
    }

    /**
     * 按图片来源决定预览页是否保留“保存到相册”。
     *
     * 宿主 `keyboard.k#c(local)` 在图片已是本地文件（pathType=0）时隐藏保存按钮。
     * 模块为同步图片提前下载解密，导致它走了本地分支、保存入口消失 —— 所以这里在
     * `k#e(item, tempPath, local)` 绑定完成后，只对**跨设备同步**图片
     * （receiveTimestampFromServer>0）重新按远端样式渲染一次卡片，恢复
     * “保存到相册 + 发送”。
     *
     * 本机图片（receiveTimestampFromServer==0）不动：它本来就是本地文件，再往系统相册
     * 存一份是重复，保持宿主原生的“只发送”。
     *
     * 点击保存仍走宿主原生 `S33ImagePreviewKeyboard.a()` → SavePicHelper，落盘到
     * 系统相册 `Pictures/WeType`（MediaStore），即宿主原有路径。
     */
    private fun hookImagePreviewSaveAction(classLoader: ClassLoader) {
        val cardClass = resolveImageCard(classLoader)
            ?: throw ClassNotFoundException(IMAGE_CARD_CLASS)
        val bindMethod = pickHostMethod(cardClass, "e") {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        } ?: run {
            XLog.e("[$TAG] image preview card bind method missing (keyboard.k#e)")
            return
        }
        val refreshMethod = pickHostMethod(cardClass, "c") {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: run {
            XLog.e("[$TAG] image preview card refresh method missing (keyboard.k#c)")
            return
        }
        bindMethod.isAccessible = true
        refreshMethod.isAccessible = true
        bindMethod.hookAfter { param ->
            val item = param.argumentOrNull(0) ?: return@hookAfter
            // 只有跨设备同步来的图片才补“保存到相册”：它没进过系统相册，存一份有意义。
            // 本机图片本来就在本地，再存一份是重复，保持宿主原生的“只发送”。
            if (!ClipboardImageEntryLogic.shouldOfferSaveToGallery(itemReceiveTimestamp(item))) {
                return@hookAfter
            }
            runCatching { refreshMethod.invoke(param.thisObject, false) }
                .onFailure { XLog.e("[$TAG] restore image save action failed: ${it.message}") }
        }
        XLog.i("[$TAG] hooked image preview save action (keyboard.k#e)")
    }

    /**
     * 预览页背景直接复用键盘 carrier。
     *
     * 宿主把 S33 预览页放在页面容器（s5）里，键盘页与候选栏作为兄弟层留在它下面，
     * 预览页本身是透明的。这里把兄弟层临时隐藏，预览页就会露出键盘的
     * carrier（系统液态玻璃/高级材质），不再另画底色或描边。
     */
    private fun hookPreviewBackdrop(classLoader: ClassLoader) {
        val previewClass = resolvePreviewKeyboard(classLoader)
            ?: throw ClassNotFoundException(PREVIEW_KEYBOARD_CLASS)
        val hostClass = previewClass.superclass ?: return
        val getter = pickHostMethod(hostClass, "getBackImageId") {
            it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
        } ?: run {
            XLog.i("[$TAG] preview back image getter missing on ${hostClass.name}")
            return
        }
        getter.isAccessible = true
        getter.hookAfter { param ->
            val view = param.thisObject as? View ?: return@hookAfter
            if (!previewClass.isInstance(view)) return@hookAfter
            trackPreviewPage(view)
        }
        XLog.i("[$TAG] hooked preview backdrop via ${hostClass.name}#getBackImageId")
    }

    private fun trackPreviewPage(view: View) {
        // 宿主只在构造预览页时调用一次 getBackImageId，之后复用同一个 S33 实例。
        // 若在 detach 时注销监听，第二次进预览页就收不到任何回调，键盘页会留在
        // 预览页底下同时显示。因此监听常驻，只按实例去重。
        if (trackedPreviews.put(view, true) != null) {
            if (view.isAttachedToWindow) view.post { showPreviewOverKeyboard(view) }
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // 必须同步隐藏：等到下一帧再隐藏，键盘工具栏会在切页时闪一帧。
                showPreviewOverKeyboard(v)
                if (!previewStates.containsKey(v)) v.post { showPreviewOverKeyboard(v) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                hidePreviewOverKeyboard(v)
            }
        })
        if (view.isAttachedToWindow) view.post { showPreviewOverKeyboard(view) }
    }

    private fun showPreviewOverKeyboard(view: View) {
        if (synchronized(previewStates) { previewStates.containsKey(view) }) return
        val slot = view.parent as? ViewGroup ?: return
        val container = slot.parent as? ViewGroup ?: return
        val decor = view.rootView
        hideSiblingsBehind(container, slot)
        val observer = container.viewTreeObserver
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!isPreviewPageShown(view, slot)) {
                // 预览页被隐藏或移出后必须还原：布局回调原本只负责"预览期间保持透明"，
                // 一旦宿主把预览页藏起来又不再 detach，键盘就会永久透明（能打字、看不见）。
                container.postDelayed({
                    if (!isPreviewPageShown(view, slot)) hidePreviewOverKeyboard(view)
                }, PREVIEW_RESTORE_DELAY_MS)
                return@OnGlobalLayoutListener
            }
            // 宿主切页时可能重新显示键盘层，预览期间保持透明。
            hideSiblingsBehind(container, slot)
        }
        observer.addOnGlobalLayoutListener(layoutListener)
        synchronized(previewStates) {
            previewStates[view] = PreviewState(container, decor, layoutListener, observer)
        }
        WeTypeWindowHooks.setPreviewActive(decor, true)
    }

    /** 预览页仍在用户眼前时才需要继续隐藏兄弟层。 */
    private fun isPreviewPageShown(view: View, slot: ViewGroup): Boolean =
        view.isAttachedToWindow &&
            view.visibility == View.VISIBLE &&
            view.parent === slot &&
            view.isShown

    private fun hideSiblingsBehind(container: ViewGroup, slot: View) {
        val records = synchronized(hiddenSiblings) {
            hiddenSiblings.getOrPut(container) { java.util.WeakHashMap() }
        }
        synchronized(records) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                if (child === slot || child.visibility != View.VISIBLE) continue
                // 只记录第一次隐藏前的 alpha，且绝不把 0 当原始值（否则恢复后键盘仍然透明）。
                if (!records.containsKey(child) && child.alpha != 0f) records[child] = child.alpha
                // 不能用 INVISIBLE：MIUI 底部栏会据此把两个图标整体下移 6px。
                if (child.alpha != 0f) child.alpha = 0f
            }
        }
    }

    private fun restoreSiblings(container: ViewGroup) {
        val records = synchronized(hiddenSiblings) { hiddenSiblings.remove(container) } ?: return
        synchronized(records) {
            records.forEach { (child, alpha) ->
                runCatching { if (child.alpha != alpha) child.alpha = alpha }
            }
        }
    }

    private fun hidePreviewOverKeyboard(view: View) {
        val state = synchronized(previewStates) { previewStates.remove(view) } ?: return
        if (state.observer.isAlive) state.observer.removeOnGlobalLayoutListener(state.layoutListener)
        restoreSiblings(state.container)
        val othersActive = synchronized(previewStates) {
            previewStates.values.any { it.decor === state.decor }
        }
        if (!othersActive) WeTypeWindowHooks.setPreviewActive(state.decor, false)
    }

    /**
     * 兜底还原：键盘重新显示或模块热重载时，把已不可见预览页留下的隐藏全部撤销。
     * 预览页实例可能被宿主长期持有、退出时只改 visibility 而不 detach，仅靠 detach 回调不可靠。
     */
    fun restorePreviewLayers() {
        val states = synchronized(previewStates) { previewStates.entries.toList() }
        // 预览页仍在显示时不能还原，否则键盘会从预览页底下透出来。
        states.forEach { (page, state) ->
            if (!isPreviewPageShown(page, state.container)) hidePreviewOverKeyboard(page)
        }
        val activeContainers = synchronized(previewStates) { previewStates.values.map { it.container } }
        val containers = synchronized(hiddenSiblings) { hiddenSiblings.keys.toList() }
        containers.forEach { container ->
            if (activeContainers.none { it === container }) restoreSiblings(container)
        }
    }

    fun isReady(): Boolean = installed

    /** 首次实际渲染/导航时再解析宿主单例与枚举（此时宿主已完成自身初始化）。 */
    private fun ensureRuntime() {
        if (runtimeReady) return
        synchronized(this) {
            if (runtimeReady) return
            val cl = hostClassLoader ?: return
            runCatching { resolveScaleHandles(cl) }
                .onFailure { XLog.e("[$TAG] resolve scale handles failed: ${it.message}") }
            runCatching { resolveNavigation(cl) }
                .onFailure { XLog.e("[$TAG] resolve navigation failed: ${it.message}") }
            runtimeReady = true
            XLog.i(
                "[$TAG] image host runtime resolved: line1=${dimenToPxRaw(line1HeightRes)} " +
                    "line2=${dimenToPxRaw(line2HeightRes)} inset12=${rawToPxRaw(12)} " +
                    "indent=${dimenToPxRaw(inputIndentRes)} nav=${navMethod != null}"
            )
        }
    }

    private fun dimenToPxRaw(resId: Int): Int {
        if (resId == 0) return 0
        val method = scaleL0Method ?: return resId
        val instance = scaleInstance ?: return resId
        return runCatching { method.invoke(instance, resId) as? Int }.getOrNull() ?: resId
    }

    private fun rawToPxRaw(designPx: Int): Int {
        val method = rawScaleMethod ?: return designPx
        return runCatching { method.invoke(null, designPx) as? Int }.getOrNull() ?: designPx
    }

    private fun resolveIds(classLoader: ClassLoader) {
        val ids = HostResources.rClass(IDS_CLASS, classLoader)
            ?: Class.forName(IDS_CLASS, false, classLoader)
        contentTvId = ids.getField("clipboard_content_tv").getInt(null)
        moreBtnId = runCatching { ids.getField("clipboard_more_btn").getInt(null) }.getOrDefault(0)
        // 操作栏 id 允许缺失（宿主改版时字段可能被裁掉），缺哪颗就只少放行哪颗。
        actionBarViewIds = ACTION_BAR_ID_FIELDS.mapNotNull { name ->
            runCatching { ids.getField(name).getInt(null) }.getOrNull()
        }.filter { it != 0 }.toSet()
        actionAddViewId =
            runCatching { ids.getField("clipboard_action_add_container").getInt(null) }.getOrDefault(0)
        actionBombViewId =
            runCatching { ids.getField("clipboard_action_bomb_container").getInt(null) }.getOrDefault(0)
        contentScrollId = ids.getField("clipboard_content_scrollview").getInt(null)
        line1Id = ids.getField("clipboard_item_line1").getInt(null)
        keyInfoId = ids.getField("clipboard_key_information_rv").getInt(null)
        val dimen = HostResources.rClass(DIMEN_CLASS, classLoader)
            ?: Class.forName(DIMEN_CLASS, false, classLoader)
        line1HeightRes = dimen.getField("keyboard_custom_phrase_item_line1_height").getInt(null)
        line2HeightRes = dimen.getField("keyboard_clipboard_item_line2_height").getInt(null)
        inputIndentRes = dimen.getField("keyboard_custom_phrase_item_input_code_margin_start").getInt(null)
    }

    private fun resolveScaleHandles(classLoader: ClassLoader) {
        for (cls in scaleCandidates(HostContractId.CLIPBOARD_SCALE_INSTANCE, SCALE_INSTANCE_CLASSES, classLoader)) {
            val instance = staticSelfInstance(cls) ?: continue
            val l0 = pickHostMethod(cls, "l0") {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Integer::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType) &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java)
            } ?: continue
            l0.isAccessible = true
            scaleInstance = instance
            scaleL0Method = l0
            break
        }
        for (cls in scaleCandidates(HostContractId.CLIPBOARD_SCALE_STATIC, RAW_SCALE_CLASSES, classLoader)) {
            val e0 = pickHostMethod(cls, "e0") {
                Modifier.isStatic(it.modifiers) &&
                    (it.parameterTypes.size == 1) &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Integer::class.java) &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java)
            } ?: continue
            e0.isAccessible = true
            rawScaleMethod = e0
            break
        }
        if (scaleL0Method == null || rawScaleMethod == null) {
            XLog.e("[$TAG] scale handles missing: l0=${scaleL0Method != null} e0=${rawScaleMethod != null}")
        }
    }

    /**
     * 取一组「先契约、后短名表」的候选类。
     *
     * 契约类排在最前，命中即先复核形状；契约没解析出来（DexKit 不可用或判据不唯一）才逐条试
     * 写死的短名 —— 与改动前的行为逐字一致。
     */
    private fun scaleCandidates(id: String, names: Array<String>, classLoader: ClassLoader): List<Class<*>> {
        val classes = LinkedHashSet<Class<*>>()
        WeTypeHostContracts.classOf(id)?.let { classes.add(it) }
        for (name in names) {
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let { classes.add(it) }
        }
        return classes.toList()
    }

    private fun resolveNavigation(classLoader: ClassLoader) {
        panelClass = WeTypeHostContracts.classOf(HostContractId.PANEL_ENUM)
            ?: Class.forName(PANEL_ENUM_CLASS, false, classLoader)
        val panel = panelClass ?: return
        navPanel = panel.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == PANEL_IMAGE_PREVIEW }
        navClipboardPanel = WeTypeHostContracts.constantOf(HostContractId.PANEL_CLIPBOARD_CONSTANT)
            ?: panel.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == PANEL_CLIPBOARD }
        if (navPanel == null) {
            XLog.e("[$TAG] ImagePreview panel enum missing")
            return
        }
        val n = WeTypeHostContracts.classOf(HostContractId.PANEL_MANAGER)
            ?: runCatching { Class.forName(N_CLASS, false, classLoader) }.getOrNull() ?: return
        navManager = WeTypeHostContracts.fieldOf(HostContractId.PANEL_MANAGER_INSTANCE)
            ?.let { field -> runCatching { field.get(null) }.getOrNull() }
            ?: staticSelfInstance(n)
        navMethod = WeTypeHostContracts.methodOf(HostContractId.PANEL_SWITCH_ENUM)
        if (navMethod == null) {
            val candidates = arrayOf("n3", "p3", "o3", "k3", "l3")
            for (name in candidates) {
                val m = n.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == panel && it.parameterTypes[1] == Bundle::class.java &&
                        it.returnType == Void.TYPE
                } ?: continue
                m.isAccessible = true
                navMethod = m
                break
            }
        }
        if (navMethod == null) {
            XLog.e("[$TAG] S33 navigation method missing (n3/p3/o3/k3)")
        }
    }

    private fun staticSelfInstance(cls: Class<*>): Any? {
        val field = cls.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == cls
        } ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    /**
     * 契约优先、写死的短名兜底。契约解析出来的 Class 自带宿主 ClassLoader，不用再传 loader。
     */
    private fun resolveClipboardItem(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ITEM) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(CLIPBOARD_ITEM_CLASS, false, it) }.getOrNull()
            }

    private fun resolveImageCard(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_IMAGE_CARD) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(IMAGE_CARD_CLASS, false, it) }.getOrNull()
            }

    private fun resolvePreviewHost(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_PREVIEW_HOST) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(PREVIEW_HOST_CLASS, false, it) }.getOrNull()
            }

    private fun resolvePreviewKeyboard(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.IMAGE_PREVIEW_KEYBOARD) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(PREVIEW_KEYBOARD_CLASS, false, it) }.getOrNull()
            }

    /**
     * 打开图片预览页（带滚动锚点）。
     *
     * 在跳转预览前先把锚点所在的剪贴板列表 RecyclerView 滚动状态存起来，
     * 返回剪贴板面板时由 [scheduleScrollRestore] 恢复到原位置（m00559）。
     * [anchor] 传 null 时等价于旧签名（不保存滚动，保持旧行为）。
     */
    fun openImagePreview(item: Any, anchor: View?): Boolean {
        saveClipboardScrollState(anchor)
        return openImagePreview(item)
    }

    fun openImagePreview(item: Any): Boolean {
        return try {
            ensureRuntime()
            val manager = navManager ?: return false
            val method = navMethod ?: return false
            val panel = navPanel ?: return false
            val id = itemId(item) ?: return false
            val bundle = Bundle().apply { putLong(KEY_CLIPBOARD_ID, id) }
            method.invoke(manager, panel, bundle)
            true
        } catch (t: Throwable) {
            XLog.e("[$TAG] open image preview failed: ${t.message}")
            false
        }
    }

    // ---- 剪贴板列表滚动位置保存/恢复（m00559：预览返回后不再回到列表顶部）----

    /**
     * 待恢复的滚动状态。
     *
     * [rv] 用弱引用持有，避免面板重建时把旧列表 View 钉在内存里；
     * [itemCount] 是保存时的条目数，恢复前校验数据未变，变了就放弃恢复（保持顶部，
     * 宁可保守也不跳到错误位置）。宿主切面板用的是同一个 RecyclerView 实例，弱引用
     * 在正常返回路径上一定还活着。
     */
    private class PendingScrollRestore(
        val rv: WeakReference<View>,
        /** 保存时锚点所在的窗口 decor 根：宿主重建面板后旧 RV 失联，用它重新定位。 */
        val decor: WeakReference<View>,
        val state: Parcelable,
        val itemCount: Int
    )

    /** 剪贴板列表的 RecyclerView 二进制名（库真实类名，不是宿主 R8 产物，可安全比对）。 */
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

    /** 待恢复的滚动状态；同一时刻最多一份（重复进预览会覆盖旧的）。 */
    private var pendingScrollRestore: PendingScrollRestore? = null

    /** 恢复轮询代数（仅主线程访问）：新的保存/恢复会顶掉上一轮轮询。 */
    private var restoreGeneration = 0

    /** 主线程 Handler：恢复轮询不能依赖待恢复 View 自身（宿主可能已把它从窗口拆掉）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 恢复轮询间隔（ms）。 */
    private const val RESTORE_POLL_MS = 120L

    /** 恢复轮询总上限（ms）；到期仍未恢复则放弃。 */
    private const val RESTORE_TIMEOUT_MS = 4000L

    /** 条目数稳定判定（ms）：小于保存值且这段时间不再增长，视为数据已定型（真变了）。 */
    private const val RESTORE_SETTLE_MS = 800L

    /**
     * 保存锚点所在剪贴板列表的滚动状态。
     *
     * 沿父链找 RecyclerView（按类名比对——模块没有 androidx.recyclerview 编译依赖，
     * 且宿主 R8 只改内部类名，`androidx.recyclerview.widget.RecyclerView` 本名不变）。
     * RecyclerView 把 `onSaveInstanceState()` 覆写为 public 并把 LayoutManager 滚动
     * 位置打进返回的 Parcelable。实测宿主 R8 连这个 override 的名字也改了，
     * `getMethod("onSaveInstanceState")` 直接 NoSuchMethodException——所以从
     * android.view.View 基类取 Method（框架方法名恒定不会被改），setAccessible
     * 后 invoke，虚表派发必然命中真实 override（编译期直调则是 protected 不可见）。
     */
    private fun saveClipboardScrollState(anchor: View?) {
        if (anchor == null) {
            XLog.i("[$TAG] save scroll: anchor is null (click path passed no view)")
            return
        }
        pendingScrollRestore = null
        runCatching {
            var parent: ViewParent? = anchor.parent
            while (parent != null) {
                if (parent.javaClass.name == RECYCLER_VIEW_CLASS) {
                    val rv = parent as? View ?: return
                    // 宿主 R8 会重命名 RecyclerView 的 override，按名反射取不到；
                    // 从 View 基类拿 Method（框架方法名恒定），setAccessible 后
                    // invoke 走虚表派发命中真实 override。
                    val state = View::class.java
                        .getDeclaredMethod("onSaveInstanceState")
                        .apply { isAccessible = true }
                        .invoke(rv) as? Parcelable
                    if (state == null) {
                        XLog.e("[$TAG] save scroll: onSaveInstanceState returned null")
                        return
                    }
                    val count = recyclerViewItemCount(rv)
                    if (count > 0) {
                        pendingScrollRestore = PendingScrollRestore(
                            WeakReference(rv),
                            WeakReference(anchor.rootView),
                            state,
                            count
                        )
                        XLog.i("[$TAG] saved clipboard scroll state " +
                            "(itemCount=$count, rv=${System.identityHashCode(rv)}, " +
                            "attached=${rv.isAttachedToWindow})")
                    } else {
                        XLog.e("[$TAG] save scroll: invalid itemCount=$count")
                    }
                    return
                }
                parent = parent.parent
            }
            // 父链走完没找到 RecyclerView：锚点不在列表里（或宿主换了容器类）
            XLog.e("[$TAG] save scroll: no RecyclerView found in anchor parent chain " +
                "(anchor=${anchor.javaClass.name})")
        }.onFailure { XLog.e("[$TAG] save clipboard scroll state failed: ${it.message}") }
    }

    /**
     * 恢复保存的滚动位置（预览返回、剪贴板面板重新打开后调用）。
     *
     * 实测（2026-10-19 复测轮）推翻了上一版两个假设：
     * 1. 宿主重开面板时 adapter 异步回填——保存时 294 条、重开首布局仅
     *    13~15 条，「条目数相等才恢复」等不到匹配；
     * 2. 返回后看到的列表可能是宿主重建的新 RecyclerView——旧实例上
     *    onRestoreInstanceState 日志“成功”但视觉无效。
     *
     * 因此改为 120ms 轮询（上限 4s，仅主线程）：
     * - 旧 RV 仍 attached 就直接用；否则从保存时的窗口 decor 树重新定位
     *   当前可见的 RecyclerView（类名比对，多候选时按条目数择优）；
     * - 条目数 ≥ 保存值（回填完成）才恢复；条目数更少且已稳定 800ms 或
     *   轮询到期，视为数据真变了，放弃恢复停在顶部（避免跳到列表底部）；
     * - 新的保存会顶掉本轮轮询；
     * - 等待回填期间把列表临时置 INVISIBLE（恢复或放弃时复原 VISIBLE），
     *   避免“先看到顶部一帧、再跳回原位置”的闪烁（2026-10-19 复测反馈）。
     */
    private fun scheduleScrollRestore() {
        val pending = pendingScrollRestore ?: run {
            XLog.i("[$TAG] restore: no pending scroll state")
            return
        }
        pendingScrollRestore = null
        val generation = ++restoreGeneration
        val startedAt = SystemClock.elapsedRealtime()
        // 等待期间隐藏列表，恢复/放弃时复原——同步执行，赶在宿主首帧绘制前
        val savedVisibilities = ArrayList<Pair<View, Int>>(4)
        val hideForRestore: (View) -> Unit = { target ->
            runCatching {
                if (target.visibility == View.VISIBLE) {
                    savedVisibilities.add(target to View.VISIBLE)
                    target.visibility = View.INVISIBLE
                }
            }
        }
        // 被我们临时置 INVISIBLE 的视图仍视为有效恢复目标：
        // resolveRestoreTarget 的 isShown 判定会把我们藏掉的 RV 判成不可用，
        // 导致 4 秒轮询全程 no usable list（2026-10-19 13:00 复测轮实测）。
        val isHiddenByUs: (View) -> Boolean = { v ->
            savedVisibilities.any { it.first === v }
        }
        // 首帧压制：轮询是 post 消息，赶不上宿主面板的第一帧 traversal——
        // 顶部列表先画一帧、下轮轮询才被藏掉，两段过渡肉眼可见闪烁
        // （2026-10-19 复测反馈）。preDraw 在每帧绘制前同步执行，恢复完成前
        // 一律按住目标列表，保证首帧就看不到顶部。
        val suppressDecor = pending.decor.get()
        val preDrawSuppressor = ViewTreeObserver.OnPreDrawListener {
            // 本轮结束（恢复成功/放弃/被新保存顶掉）由 unhideForRestore 摘除
            if (restoreGeneration != generation) return@OnPreDrawListener true
            val target = runCatching {
                resolveRestoreTarget(pending, isHiddenByUs)
            }.getOrNull() ?: return@OnPreDrawListener true
            hideForRestore(target)
            true
        }
        runCatching {
            if (suppressDecor != null && suppressDecor.viewTreeObserver.isAlive) {
                suppressDecor.viewTreeObserver.addOnPreDrawListener(preDrawSuppressor)
            }
        }
        val unhideForRestore = {
            runCatching {
                if (suppressDecor?.isAttachedToWindow == true &&
                    suppressDecor.viewTreeObserver.isAlive
                ) {
                    suppressDecor.viewTreeObserver.removeOnPreDrawListener(preDrawSuppressor)
                }
                for ((v, vis) in savedVisibilities) {
                    if (v.isAttachedToWindow) v.visibility = vis
                }
                savedVisibilities.clear()
            }
        }
        val poll = object : Runnable {
            var lastCount = -1
            var lastCountAt = 0L
            override fun run() {
                if (restoreGeneration != generation) {
                    XLog.i("[$TAG] restore poll superseded by newer save")
                    return
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (pending.rv.get() == null && pending.decor.get() == null) {
                    restoreGeneration++
                    unhideForRestore()
                    XLog.e("[$TAG] scroll restore aborted: saved rv and decor both gone")
                    return
                }
                val target = resolveRestoreTarget(pending, isHiddenByUs) ?: run {
                    if (elapsed >= RESTORE_TIMEOUT_MS) {
                        restoreGeneration++
                        unhideForRestore()
                        XLog.e("[$TAG] scroll restore gave up: no usable list " +
                            "after ${elapsed}ms (saved itemCount=${pending.itemCount})")
                    } else {
                        mainHandler.postDelayed(this, RESTORE_POLL_MS)
                    }
                    return
                }
                val count = recyclerViewItemCount(target)
                when {
                    // 列表还没数据：继续等
                    count <= 0 -> if (elapsed >= RESTORE_TIMEOUT_MS) {
                        restoreGeneration++
                        unhideForRestore()
                        XLog.e("[$TAG] scroll restore gave up: empty list after ${elapsed}ms")
                    } else {
                        hideForRestore(target)
                        mainHandler.postDelayed(this, RESTORE_POLL_MS)
                    }
                    // 数据回填到位：恢复
                    count >= pending.itemCount -> {
                        unhideForRestore()
                        performScrollRestore(target, pending, elapsed)
                    }
                    // 条目数比保存值少：可能仍在回填，也可能数据真变了
                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        if (count != lastCount) {
                            lastCount = count
                            lastCountAt = now
                        }
                        val settled = now - lastCountAt >= RESTORE_SETTLE_MS
                        if (settled || elapsed >= RESTORE_TIMEOUT_MS) {
                            restoreGeneration++
                            unhideForRestore()
                            XLog.e("[$TAG] scroll restore gave up: item count settled " +
                                "at $count < saved ${pending.itemCount}")
                        } else {
                            hideForRestore(target)
                            mainHandler.postDelayed(this, RESTORE_POLL_MS)
                        }
                    }
                }
            }
        }
        mainHandler.post(poll)
    }

    /**
     * 解析恢复目标：保存的 RV 还挂在窗口上就直接用；否则（宿主重建了面板）
     * 从保存时的 decor 树 BFS 找当前可见的 RecyclerView。
     *
     * 被我们为等待回填而临时置 INVISIBLE 的视图视为有效目标（2026-10-19 13:00
     * 实测：isShown 判定否决了被自己藏掉的 RV，4 秒轮询全程 no usable list）。
     *
     * @param hiddenByUs 判断视图是否被本次恢复流程临时隐藏。
     * @return 可见且有宽度的列表；暂时不满足返回 null（调用方继续轮询）。
     */
    private fun resolveRestoreTarget(
        pending: PendingScrollRestore,
        hiddenByUs: (View) -> Boolean
    ): View? {
        val savedRv = pending.rv.get()
        if (savedRv != null && savedRv.isAttachedToWindow &&
            (savedRv.isShown || hiddenByUs(savedRv)) && savedRv.width > 0
        ) return savedRv
        val decor = pending.decor.get() ?: return null
        val candidates = ArrayList<View>(4)
        val queue = ArrayDeque<View>()
        queue.add(decor)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name == RECYCLER_VIEW_CLASS && (v.isShown || hiddenByUs(v)) && v.width > 0) {
                candidates.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
            }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        // 多候选：优先条目数与保存值一致的，否则挑条目数最多的（回填中的列表）
        var best: View? = null
        var bestCount = -1
        for (c in candidates) {
            val n = recyclerViewItemCount(c)
            if (n == pending.itemCount) return c
            if (n > bestCount) {
                bestCount = n
                best = c
            }
        }
        XLog.e("[$TAG] restore: ${candidates.size} RecyclerViews in decor tree, " +
            "picking largest (count=$bestCount, saved=${pending.itemCount})")
        return best
    }

    /** 在目标列表上执行 onRestoreInstanceState 并记日志（同 save：View 基类 Method+虚表派发）。 */
    private fun performScrollRestore(target: View, pending: PendingScrollRestore, elapsed: Long) {
        restoreGeneration++
        val rediscovered = target !== pending.rv.get()
        runCatching {
            View::class.java
                .getDeclaredMethod("onRestoreInstanceState", Parcelable::class.java)
                .apply { isAccessible = true }
                .invoke(target, pending.state)
            XLog.i("[$TAG] restored clipboard scroll position " +
                "(rv=${System.identityHashCode(target)}" +
                "${if (rediscovered) ", rediscovered" else ""}, " +
                "count=${recyclerViewItemCount(target)}, saved=${pending.itemCount}, " +
                "${elapsed}ms)")
        }.onFailure {
            XLog.e("[$TAG] restore clipboard scroll failed: ${it.message}")
        }
    }

    /**
     * 反射取 RecyclerView 的条目数。
     *
     * `getAdapter`/`getItemCount` 按名字找：当前宿主版未对它们改名（refreshItem 里
     * `notifyItemChanged` 同样按名调用成功）；将来若改名导致取不到，返回 -1，
     * 调用方以 `count > 0` / `count != pending.itemCount` 判断自然降级（不保存/不恢复）。
     */
    private fun recyclerViewItemCount(rv: View): Int {
        return runCatching {
            val adapter = rv.javaClass.getMethod("getAdapter").invoke(rv) ?: return -1
            val count = adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int
            count ?: -1
        }.getOrDefault(-1)
    }

    /** 用宿主原生导航切回剪贴板面板（与搜索提交 `N#o3(CustomPhraseAndClipboard, …)` 同契约）。 */
    fun openClipboardPanel(): Boolean {
        return try {
            ensureRuntime()
            val manager = navManager ?: return false
            val method = navMethod ?: return false
            val panel = navClipboardPanel ?: return false
            val bundle = Bundle().apply {
                putInt(KEY_TARGET_TAB_INDEX, CLIPBOARD_TAB_INDEX)
                putInt(KEY_FROM, 1)
            }
            method.invoke(manager, panel, bundle)
            true
        } catch (t: Throwable) {
            XLog.e("[$TAG] open clipboard panel failed: ${t.message}")
            false
        }
    }

    // ---- C 条目数据面 ----

    private fun bindGetter(item: Any, name: String, returnType: Class<*>?): Method? {
        if (returnType == null) return null
        val cls = item.javaClass
        val key = "${cls.name}#$name"
        getterCache[key]?.let { return it }
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == returnType
        } ?: return null
        m.isAccessible = true
        getterCache[key] = m
        return m
    }

    fun itemType(item: Any): Long = invokeLong(item, "q", 0L)

    fun itemId(item: Any): Long? {
        val m = bindGetter(item, "f", Long::class.javaPrimitiveType) ?: return null
        return runCatching { m.invoke(item) as? Long }.getOrNull()
    }

    fun itemPath(item: Any): String? {
        val m = bindGetter(item, "i", String::class.java) ?: return null
        return runCatching { m.invoke(item) as? String }.getOrNull()
    }

    fun itemPathType(item: Any): Int {
        val m = bindGetter(item, "j", Int::class.javaPrimitiveType) ?: return 0
        return runCatching { m.invoke(item) as? Int }.getOrNull() ?: 0
    }

    fun itemReceiveTimestamp(item: Any): Long = invokeLong(item, "k", 0L)

    fun itemCreateTime(item: Any): Long = invokeLong(item, "c", 0L)

    fun itemExpireTimestamp(item: Any): Long = invokeLong(item, "d", 0L)

    private fun invokeLong(item: Any, name: String, fallback: Long): Long {
        val m = bindGetter(item, name, Long::class.javaPrimitiveType) ?: return fallback
        return runCatching { m.invoke(item) as? Long }.getOrNull() ?: fallback
    }

    fun setItemPath(item: Any, path: String): Boolean {
        val m = bindSetter(item, "z", String::class.java) ?: return false
        return runCatching { m.invoke(item, path); true }.getOrDefault(false)
    }

    fun setItemPathType(item: Any, pathType: Int): Boolean {
        val m = bindSetter(item, "A", Int::class.javaPrimitiveType) ?: return false
        return runCatching { m.invoke(item, pathType); true }.getOrDefault(false)
    }

    fun setItemExpireTimestamp(item: Any, expireTimestamp: Long): Boolean {
        val m = bindSetter(item, "v", Long::class.javaPrimitiveType) ?: return false
        return runCatching { m.invoke(item, expireTimestamp); true }.getOrDefault(false)
    }

    private fun bindSetter(item: Any, name: String, paramType: Class<*>?): Method? {
        if (paramType == null) return null
        val cls = item.javaClass
        val key = "${cls.name}#$name"
        setterCache[key]?.let { return it }
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == paramType
        } ?: return null
        m.isAccessible = true
        setterCache[key] = m
        return m
    }

    fun holderRecordItem(holder: Any): Any? {
        val cls = holder.javaClass
        holderItemFieldCache[cls]?.let { field ->
            return runCatching { field.get(holder) }.getOrNull()
        }
        val field = findRecordField(cls) ?: return null
        holderItemFieldCache[cls] = field
        return runCatching { field.get(holder) }.getOrNull()
    }

    private fun findRecordField(cls: Class<*>): Field? {
        val target = itemClass
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (target != null && field.type == target) {
                    runCatching { field.isAccessible = true }
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    // ---- 尺寸换算 ----

    fun dimenToPx(resId: Int): Int {
        ensureRuntime()
        return dimenToPxRaw(resId)
    }

    fun rawToPx(designPx: Int): Int {
        ensureRuntime()
        return rawToPxRaw(designPx)
    }

    fun findView(itemView: View, id: Int): View? {
        if (id == 0) return null
        return runCatching { itemView.findViewById<View>(id) }.getOrNull()
    }

    /**
     * 按条目类型同步展开操作栏的可用按钮：图片条目只留「删除」，普通条目恢复原生三颗。
     *
     * 「＋常用语」要把文本存成常用语、「选词」要把文本片段插进输入框，图片条目两样都没有，
     * 点了只会得到空内容或落回整条图片，所以图片条目上直接藏掉。
     *
     * RecyclerView 会复用条目视图：同一条目视图这次绑图片、下次绑文本，所以**两个方向都要写**，
     * 只藏不恢复会让文本条目也少两颗按钮。只在可见性真的不同时才写，避免绑定期多余的 requestLayout。
     * 宿主没解析出这两个 id 时（老版本 / 字段被裁）静默跳过，保持原生行为。
     */
    fun syncActionBarForItem(itemView: View, isImage: Boolean) {
        val visibility = if (isImage) View.GONE else View.VISIBLE
        applyActionVisibility(itemView, actionAddViewId, visibility)
        applyActionVisibility(itemView, actionBombViewId, visibility)
    }

    private fun applyActionVisibility(itemView: View, id: Int, visibility: Int) {
        val view = findView(itemView, id) ?: return
        if (view.visibility != visibility) view.visibility = visibility
    }
}
