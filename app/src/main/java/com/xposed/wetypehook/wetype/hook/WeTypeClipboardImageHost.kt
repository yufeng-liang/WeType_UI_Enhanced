package com.xposed.wetypehook.wetype.hook

import android.os.Bundle
import android.os.Parcelable
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewParent
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
        val decor: View,
        val hidden: List<Pair<View, Float>>,
        val layoutListener: ViewTreeObserver.OnGlobalLayoutListener,
        val observer: ViewTreeObserver
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
        line1HeightRes = 0
        line2HeightRes = 0
        inputIndentRes = 0
        getterCache.clear()
        setterCache.clear()
        holderItemFieldCache.clear()
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
                .onFailure { AndroidLog.e(TAG, "image preview back hook failed: ${it.message}") }
            runCatching { hookImagePreviewSaveAction(classLoader) }
                .onFailure { AndroidLog.e(TAG, "image preview save hook failed: ${it.message}") }
            runCatching { hookPreviewBackdrop(classLoader) }
                .onFailure { AndroidLog.e(TAG, "image preview backdrop hook failed: ${it.message}") }
            installed = true
            AndroidLog.i(TAG, "image host handles installed (line1=$line1HeightRes line2=$line2HeightRes)")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image host handles unavailable: ${t.message}")
            false
        }
    }

    /**
     * 图片预览页（S33）走宿主 `keyboard.l#B0` 作为左上角“收回”。
     * 模块是直接用 `N.n3(ImagePreview, Bundle)` 切页的，不在宿主页面栈里，
     * 原生收回会落到普通键盘。这里拦截 S33 的收回并手动导航回剪贴板面板。
     *
     * 注意：宿主切回面板时会把列表滚动重置到顶部（实测 m00559），页面未销毁并不等于
     * 滚动位置还在——所以面板重开后要调 [scheduleScrollRestore] 恢复原滚动位置。
     */
    private fun hookImagePreviewBack(classLoader: ClassLoader) {
        val previewClass = resolvePreviewKeyboard(classLoader) ?: run {
            AndroidLog.e(TAG, "image preview keyboard class missing")
            return
        }
        val baseClass = resolvePreviewHost(classLoader)
            ?: throw ClassNotFoundException(PREVIEW_HOST_CLASS)
        val backMethod = pickHostMethod(baseClass, "B0") {
            it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: run {
            AndroidLog.e(TAG, "image preview back method missing (keyboard.l#B0)")
            return
        }
        backMethod.isAccessible = true
        backMethod.hookBefore { param ->
            val self = param.thisObject ?: return@hookBefore
            if (!previewClass.isInstance(self)) return@hookBefore
            if (openClipboardPanel()) {
                scheduleScrollRestore()
                AndroidLog.i(TAG, "image preview back -> clipboard panel")
                param.result = null
            }
        }
        AndroidLog.i(TAG, "hooked image preview back (keyboard.l#B0)")
    }

    /**
     * 恢复关联图片预览页的“保存到相册”。
     *
     * 宿主 `keyboard.k#c(local)` 在图片已是本地文件（pathType=0）时隐藏保存按钮；
     * 模块为同步图片提前下载解密，导致走本地分支后保存入口消失。这里在
     * `k#e(item, tempPath, local)` 绑定完成后，对同步来源图片（receiveTimestampFromServer>0）
     * 重新按远端样式渲染一次卡片，恢复“保存到相册 + 发送”。
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
            AndroidLog.e(TAG, "image preview card bind method missing (keyboard.k#e)")
            return
        }
        val refreshMethod = pickHostMethod(cardClass, "c") {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: run {
            AndroidLog.e(TAG, "image preview card refresh method missing (keyboard.k#c)")
            return
        }
        bindMethod.isAccessible = true
        refreshMethod.isAccessible = true
        bindMethod.hookAfter { param ->
            val item = param.argumentOrNull(0) ?: return@hookAfter
            // 本机图片与同步图片统一按远端样式刷新：宿主对本地文件会隐藏
            // 「保存到相册」，但剪贴板里的图片并不在系统相册中，保存入口应当保留。
            runCatching { refreshMethod.invoke(param.thisObject, false) }
                .onFailure { AndroidLog.e(TAG, "restore image save action failed: ${it.message}") }
        }
        AndroidLog.i(TAG, "hooked image preview save action (keyboard.k#e)")
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
        if (previewStates.containsKey(view)) return
        val slot = view.parent as? ViewGroup ?: return
        val container = slot.parent as? ViewGroup ?: return
        val decor = view.rootView
        val hidden = ArrayList<Pair<View, Float>>()
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child === slot || child.visibility != View.VISIBLE) continue
            hidden.add(child to child.alpha)
            // 不能用 INVISIBLE：MIUI 底部栏会据此把两个图标整体下移 6px。
            child.alpha = 0f
        }
        val observer = container.viewTreeObserver
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            // 宿主切页时可能重新显示键盘层，预览期间保持透明。
            hidden.forEach { (child, _) ->
                if (child.alpha != 0f) child.alpha = 0f
            }
        }
        observer.addOnGlobalLayoutListener(layoutListener)
        previewStates[view] = PreviewState(decor, hidden, layoutListener, observer)
        WeTypeWindowHooks.setPreviewActive(decor, true)
    }

    private fun hidePreviewOverKeyboard(view: View) {
        val state = previewStates.remove(view) ?: return
        if (state.observer.isAlive) state.observer.removeOnGlobalLayoutListener(state.layoutListener)
        state.hidden.forEach { (child, alpha) ->
            if (child.alpha == 0f) child.alpha = alpha
        }
        WeTypeWindowHooks.setPreviewActive(state.decor, false)
    }

    fun isReady(): Boolean = installed

    /** 首次实际渲染/导航时再解析宿主单例与枚举（此时宿主已完成自身初始化）。 */
    private fun ensureRuntime() {
        if (runtimeReady) return
        synchronized(this) {
            if (runtimeReady) return
            val cl = hostClassLoader ?: return
            runCatching { resolveScaleHandles(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve scale handles failed: ${it.message}") }
            runCatching { resolveNavigation(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve navigation failed: ${it.message}") }
            runtimeReady = true
            AndroidLog.i(
                TAG,
                "image host runtime resolved: line1=${dimenToPxRaw(line1HeightRes)} " +
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
            AndroidLog.e(TAG, "scale handles missing: l0=${scaleL0Method != null} e0=${rawScaleMethod != null}")
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
            AndroidLog.e(TAG, "ImagePreview panel enum missing")
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
            AndroidLog.e(TAG, "S33 navigation method missing (n3/p3/o3/k3)")
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
            AndroidLog.e(TAG, "open image preview failed: ${t.message}")
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
        val state: Parcelable,
        val itemCount: Int
    )

    /** 剪贴板列表的 RecyclerView 二进制名（库真实类名，不是宿主 R8 产物，可安全比对）。 */
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

    /** 待恢复的滚动状态；同一时刻最多一份（重复进预览会覆盖旧的）。 */
    private var pendingScrollRestore: PendingScrollRestore? = null

    /**
     * 保存锚点所在剪贴板列表的滚动状态。
     *
     * 沿父链找 RecyclerView（按类名比对——模块没有 androidx.recyclerview 编译依赖，
     * 且宿主 R8 只改内部类名，`androidx.recyclerview.widget.RecyclerView` 本名不变）。
     * RecyclerView 把 `onSaveInstanceState()` 覆写为 public 并把 LayoutManager 滚动
     * 位置打进返回的 Parcelable；但模块编译期只有 android.view.View 类型（那里它是
     * protected），所以走反射。这两个方法 override 的是框架 View 的方法，R8 不会改名
     * （改了就破坏多态分发），按名字找是安全的。
     */
    private fun saveClipboardScrollState(anchor: View?) {
        if (anchor == null) return
        pendingScrollRestore = null
        runCatching {
            var parent: ViewParent? = anchor.parent
            while (parent != null) {
                if (parent.javaClass.name == RECYCLER_VIEW_CLASS) {
                    val rv = parent as? View ?: return
                    val state = rv.javaClass.getMethod("onSaveInstanceState")
                        .invoke(rv) as? Parcelable ?: return
                    val count = recyclerViewItemCount(rv)
                    if (count > 0) {
                        pendingScrollRestore =
                            PendingScrollRestore(WeakReference(rv), state, count)
                        AndroidLog.i(TAG, "saved clipboard scroll state (itemCount=$count)")
                    }
                    return
                }
                parent = parent.parent
            }
        }.onFailure { AndroidLog.w(TAG, "save clipboard scroll state failed: ${it.message}") }
    }

    /**
     * 恢复保存的滚动位置（预览返回、剪贴板面板重新打开后调用）。
     *
     * 时序：宿主切回面板时会重置列表滚动，所以不能立即恢复——挂一次性
     * OnLayoutChangeListener，等列表真正布局出内容（isShown 且有宽度）后再在
     * 下一个消息循环里恢复，绕开宿主在布局后同步执行的 scrollToPosition(0)。
     */
    private fun scheduleScrollRestore() {
        val pending = pendingScrollRestore ?: return
        val rv = pending.rv.get() ?: run {
            pendingScrollRestore = null
            return
        }
        pendingScrollRestore = null
        rv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                if (!v.isShown || v.width <= 0) return
                v.post {
                    runCatching {
                        // 数据变了（条目数不同）就不恢复，宁可停在顶部也不跳错位置
                        if (recyclerViewItemCount(rv) != pending.itemCount) {
                            AndroidLog.i(TAG, "skip scroll restore: item count changed")
                            return@post
                        }
                        // 同 saveClipboardScrollState 的理由走反射（View 上是 protected）
                        rv.javaClass
                            .getMethod("onRestoreInstanceState", Parcelable::class.java)
                            .invoke(rv, pending.state)
                        AndroidLog.i(TAG, "restored clipboard scroll position")
                    }.onFailure {
                        AndroidLog.w(TAG, "restore clipboard scroll failed: ${it.message}")
                    }
                }
            }
        })
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
            AndroidLog.e(TAG, "open clipboard panel failed: ${t.message}")
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
}
