package com.zerobox.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zerobox.runtime.config.PassthroughConfig
import com.zerobox.runtime.daemon.StealthSelfChecker
import com.zerobox.runtime.daemon.ZeroPackageEngine
import com.zerobox.runtime.daemon.ZeroRuntimeSession
import com.zerobox.runtime.delegate.SlotProvider
import com.zerobox.runtime.delegate.ZeroInstrumentation
import com.zerobox.runtime.hook.ZeroHookImpl
import com.zerobox.runtime.module.SiblingModuleLoader
import com.zerobox.sample.SampleZeroModule
import com.zerobox.sdk.control.ZeroControlClient
import com.zerobox.sdk.hook.ZeroHook
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Complete ZeroBox Host Manager & Cross-Process Control Console.
 * Supports:
 * 1. Real APK importing from installed device apps or storage .apk files (100% Zero-Modification)
 * 2. Multi-user app cloning (User #0, User #1, ...) and real Activity launching inside :p0 ~ :p3
 * 3. Per-app Granular Passthrough Policy Configurator ("Hiding > Confrontation")
 * 4. Module & Native (.so) Plugin Manager + Real ART ArtMethod Hook verification
 * 5. Shizuku-style Cross-Process Memory R/W, Wildcard Pattern Scanner, Private VFS & Stealth Audit
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT_GUEST_APK = 1001
        private const val REQ_IMPORT_MODULE_APK = 1002
        private const val REQ_IMPORT_NATIVE_SO = 1003

        @JvmStatic
        fun sampleArtHookTargetMethod(input: Int): Int {
            return input * 2 + 1
        }

        @JvmStatic
        fun sampleArtHookObjectMethod(prefix: String, version: Int): String {
            return "$prefix-v$version"
        }
    }

    private lateinit var packageEngine: ZeroPackageEngine
    private lateinit var contentFrame: LinearLayout
    private lateinit var consoleOutputView: TextView

    private var activeTargetPackage: String = ""
    private var activeUserId: Int = 0
    private var currentConfig: PassthroughConfig = PassthroughConfig.createPureStealthPreset()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageEngine = ZeroPackageEngine(this)

        val registered = packageEngine.listRegisteredApps()
        activeTargetPackage = if (registered.isNotEmpty()) {
            registered[0].packageName
        } else {
            packageName
        }
        currentConfig = packageEngine.loadPassthroughConfig(activeTargetPackage, activeUserId)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        rootLayout.addView(buildHeaderBar())
        rootLayout.addView(buildTabRow())

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(contentFrame)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        renderVirtualAppsTab()
    }

    private fun buildHeaderBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            val pad = dp(16)
            setPadding(pad, dp(16), pad, dp(12))

            val title = TextView(this@MainActivity).apply {
                text = "ZeroBox 免改包多开与模块容器"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val subtitle = TextView(this@MainActivity).apply {
                text = "Seccomp-BPF (SIGSYS) • Native Binder 过滤 • 平级 ClassLoader • 分级直通"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            addView(title)
            addView(subtitle)
        }
    }

    private fun buildTabRow(): View {
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }

        row.addView(createActionButton("1. 应用分身工作台", "#0284C7") {
            renderVirtualAppsTab()
        })
        row.addView(createActionButton("2. 分级直通配置", "#0D9488") {
            renderPassthroughConfigTab()
        })
        row.addView(createActionButton("3. 模块与 Hook 中心", "#4F46E5") {
            renderModuleSandboxTab()
        })
        row.addView(createActionButton("4. 跨进程内存与目录", "#059669") {
            renderCrossProcessConsoleTab()
        })
        row.addView(createActionButton("5. 隐蔽性自检", "#D97706") {
            renderStealthAuditTab()
        })

        scroll.addView(row)
        return scroll
    }

    // =========================================================================
    // TAB 1: Virtual Apps & Multi-Instance Launcher
    // =========================================================================
    private fun renderVirtualAppsTab() {
        contentFrame.removeAllViews()

        val importCard = createCardLayout("导入应用到容器 (100% 零修改原 APK · 保留 V1/V2/V3 签名)")
        val importHint = TextView(this).apply {
            text = "支持从本机已安装应用列表直接提取原装 APK，或从存储中选择任意未安装的 .apk 文件导入容器运行。"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        importCard.addView(importHint)

        val importBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        importBtnRow.addView(createActionButton("📲 从本机已安装应用导入", "#0284C7") {
            showInstalledAppsPickerDialog(false)
        })
        importBtnRow.addView(createActionButton("📂 选择存储中的 .APK 导入", "#4F46E5") {
            openSystemFilePicker(REQ_IMPORT_GUEST_APK, "application/vnd.android.package-archive")
        })
        importBtnRow.addView(createActionButton("🧪 导入内置测试目标应用", "#059669") {
            try {
                val rec = packageEngine.importUnmodifiedApk(File(applicationInfo.sourceDir))
                activeTargetPackage = rec.packageName
                activeUserId = 0
                renderVirtualAppsTab()
                appendConsole("已导入内置测试应用: ${rec.appLabel} (${rec.packageName})\n可直接点击下方【▶ 启动分身】在独立槽位 :p0 中运行真实 Activity！")
            } catch (t: Throwable) {
                appendConsole("导入失败: ${t.message}")
            }
        })
        importCard.addView(importBtnRow)
        contentFrame.addView(importCard)

        // Registered Virtual Apps List
        val apps = packageEngine.listRegisteredApps()
        val appsHeaderCard = createCardLayout("已导入的容器应用列表 (共 ${apps.size} 款)")
        if (apps.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "当前容器内尚未导入应用。\n请点击上方【📲 从本机已安装应用导入】、【📂 选择存储中的 .APK 导入】或【🧪 导入内置测试目标应用】。"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            appsHeaderCard.addView(emptyTv)
            contentFrame.addView(appsHeaderCard)
        } else {
            contentFrame.addView(appsHeaderCard)
            for (record in apps) {
                contentFrame.addView(buildVirtualAppCard(record))
            }
        }

        contentFrame.addView(buildConsoleCard())
    }

    private fun buildVirtualAppCard(record: ZeroPackageEngine.VirtualAppRecord): View {
        val card = createCardLayout("${record.appLabel}  (v${record.versionName})")

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (record.appIcon != null) {
            val iconView = ImageView(this).apply {
                setImageDrawable(record.appIcon)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    rightMargin = dp(12)
                }
            }
            topRow.addView(iconView)
        }

        val targetActivity = if (record.packageName == packageName) {
            BuiltInGuestTargetActivity::class.java.name
        } else {
            record.mainActivityClass
        }

        val metaText = TextView(this).apply {
            text = "包名: ${record.packageName}\n" +
                    "入口 Activity: ${targetActivity.ifEmpty { "自动解析" }}\n" +
                    "原包路径: ${record.originalApkFile.absolutePath}\n" +
                    "已创建多开分身: ${record.userIds.joinToString(", ") { "User #$it" }}"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        topRow.addView(metaText)
        card.addView(topRow)

        // Instance launch buttons for each userId
        for (uid in record.userIds) {
            val userRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            userRow.addView(createActionButton("▶ 启动分身 #$uid", "#059669") {
                launchVirtualAppInstance(record, uid, targetActivity)
            })
            if (record.allActivityClasses.size > 1) {
                userRow.addView(createActionButton("📋 选择 Activity 启动", "#0284C7") {
                    showActivityChooserDialog(record, uid)
                })
            }
            userRow.addView(createActionButton("⚙ 分身 #$uid 直通设置", "#0D9488") {
                activeTargetPackage = record.packageName
                activeUserId = uid
                currentConfig = packageEngine.loadPassthroughConfig(activeTargetPackage, activeUserId)
                renderPassthroughConfigTab()
            })
            card.addView(userRow)
        }

        // Management row (Add clone / Delete app)
        val manageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        manageRow.addView(createActionButton("➕ 新建多开分身 #${(record.userIds.maxOrNull() ?: 0) + 1}", "#7C3AED") {
            val nextUid = (record.userIds.maxOrNull() ?: 0) + 1
            val dir = packageEngine.createInstanceUser(record.packageName, nextUid)
            renderVirtualAppsTab()
            appendConsole("已为 ${record.appLabel} 创建独立多开分身 User #$nextUid\n隔离目录: ${dir.absolutePath}")
        })
        manageRow.addView(createActionButton("🗑 移除应用", "#DC2626") {
            packageEngine.removeRegisteredApp(record.packageName)
            renderVirtualAppsTab()
            appendConsole("已移除容器应用: ${record.packageName}")
        })
        card.addView(manageRow)
        return card
    }

    private fun launchVirtualAppInstance(
        record: ZeroPackageEngine.VirtualAppRecord,
        userId: Int,
        activityClass: String
    ) {
        try {
            val resolvedActivity = when {
                activityClass.isNotEmpty() -> activityClass
                record.packageName == packageName -> BuiltInGuestTargetActivity::class.java.name
                record.mainActivityClass.isNotEmpty() -> record.mainActivityClass
                record.allActivityClasses.isNotEmpty() -> record.allActivityClasses[0]
                else -> ""
            }
            if (resolvedActivity.isEmpty()) {
                Toast.makeText(this, "该 APK 未包含可启动的 Activity 组件", Toast.LENGTH_SHORT).show()
                return
            }

            val dataDir = packageEngine.createInstanceUser(record.packageName, userId)
            val slotIndex = userId % 4
            val launchIntent = ZeroInstrumentation.wrapSlotActivityIntent(
                this,
                slotIndex,
                record.packageName,
                resolvedActivity,
                userId,
                record.originalApkFile.absolutePath,
                dataDir.absolutePath,
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            )
            activeTargetPackage = record.packageName
            activeUserId = userId
            startActivity(launchIntent)
        } catch (t: Throwable) {
            appendConsole("启动分身失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun showInstalledAppsPickerDialog(includeSystem: Boolean) {
        val candidates = packageEngine.listHostInstalledApps(includeSystem)
        if (candidates.isEmpty()) {
            Toast.makeText(this, "未找到可导入的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { "${it.appLabel}\n(${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要导入容器的本机应用 (共 ${candidates.size} 款)")
            .setItems(labels) { _, which ->
                val chosen = candidates[which]
                try {
                    val imported = packageEngine.importUnmodifiedApk(File(chosen.sourceApkPath))
                    activeTargetPackage = imported.packageName
                    activeUserId = 0
                    renderVirtualAppsTab()
                    appendConsole(
                        "=== 成功免修改导入应用 ===\n" +
                                "名称: ${imported.appLabel}\n" +
                                "包名: ${imported.packageName}\n" +
                                "主入口: ${imported.mainActivityClass}\n" +
                                "原装 APK: ${imported.originalApkFile.absolutePath}"
                    )
                } catch (t: Throwable) {
                    appendConsole("导入 ${chosen.packageName} 失败: ${t.message}")
                }
            }
            .setNeutralButton(if (includeSystem) "仅显示用户应用" else "包含系统应用") { _, _ ->
                showInstalledAppsPickerDialog(!includeSystem)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showActivityChooserDialog(
        record: ZeroPackageEngine.VirtualAppRecord,
        userId: Int
    ) {
        val items = record.allActivityClasses.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要启动的 Activity (${record.appLabel} #$userId)")
            .setItems(items) { _, which ->
                launchVirtualAppInstance(record, userId, items[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =========================================================================
    // TAB 2: Granular Passthrough Policy Configurator
    // =========================================================================
    private fun renderPassthroughConfigTab() {
        contentFrame.removeAllViews()

        val policyCard = createCardLayout(
            "分级直通策略配置 (当前目标: $activeTargetPackage · User #$activeUserId)"
        )
        val desc = TextView(this).apply {
            text = "设计原则：“隐藏永远大于对抗”。不强制伪造 40+ 个系统服务，默认将 95% 以上的系统服务原生直通真机内核，支持按应用精细开关拦截层。"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        policyCard.addView(desc)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        presetRow.addView(createActionButton("纯净隐身预设 (推荐)", "#0D9488") {
            currentConfig = PassthroughConfig.createPureStealthPreset()
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
            renderPassthroughConfigTab()
        })
        presetRow.addView(createActionButton("全模块 Hook 预设", "#6366F1") {
            currentConfig = PassthroughConfig.createFullModulePreset()
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
            renderPassthroughConfigTab()
        })
        presetRow.addView(createActionButton("全直通无拦截预设", "#475569") {
            currentConfig = PassthroughConfig.createDirectPassthroughPreset()
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
            renderPassthroughConfigTab()
        })
        policyCard.addView(presetRow)

        policyCard.addView(createToggleSwitch(
            "启用 Seccomp-BPF (SIGSYS) 内核边界文件系统重定向 (不改 libc.so 代码段)",
            currentConfig.isEnableSeccompVfs
        ) { checked ->
            currentConfig.isEnableSeccompVfs = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        policyCard.addView(createToggleSwitch(
            "启用 /proc 伪文件系统净化 (隐藏 /proc/self/maps, fd, cmdline 中的容器痕迹)",
            currentConfig.isEnableProcCloak
        ) { checked ->
            currentConfig.isEnableProcCloak = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        policyCard.addView(createToggleSwitch(
            "启用 Native Binder 驱动层最小化过滤 (不使用 Java Proxy 替换系统单例)",
            currentConfig.isEnableBinderFilter
        ) { checked ->
            currentConfig.isEnableBinderFilter = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        policyCard.addView(createToggleSwitch(
            "外置存储 (/sdcard) 原生直通模式",
            currentConfig.isPassthroughExternalStorage
        ) { checked ->
            currentConfig.isPassthroughExternalStorage = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        policyCard.addView(createToggleSwitch(
            "真机已安装同名包 PMS 签名与安装源直通",
            currentConfig.isPassthroughHostInstalledPkg
        ) { checked ->
            currentConfig.isPassthroughHostInstalledPkg = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        policyCard.addView(createToggleSwitch(
            "按需懒加载 Java / LSPosed Hook 引擎 (关闭时进程内零 ART Hook 特征)",
            currentConfig.isEnableJavaHookEngine
        ) { checked ->
            currentConfig.isEnableJavaHookEngine = checked
            packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
        })

        val customPathInput = EditText(this).apply {
            hint = "添加自定义直通路径前缀 (例如 /sdcard/Documents)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        policyCard.addView(customPathInput)
        policyCard.addView(createActionButton("➕ 添加直通白名单路径", "#0284C7") {
            val path = customPathInput.text.toString().trim()
            if (path.isNotEmpty()) {
                currentConfig.addCustomPassthroughPath(path)
                packageEngine.savePassthroughConfig(activeTargetPackage, activeUserId, currentConfig)
                appendConsole("已添加直通路径: $path\n当前直通列表: ${currentConfig.customPassthroughPaths}")
                customPathInput.setText("")
            }
        })

        contentFrame.addView(policyCard)
        contentFrame.addView(buildConsoleCard())
    }

    // =========================================================================
    // TAB 3: Module Sandbox, Native .so Plugins & Real ART Hook Engine
    // =========================================================================
    private fun renderModuleSandboxTab() {
        contentFrame.removeAllViews()

        val modCard = createCardLayout("模块与 Native 插件中心 (自研 SDK + LSPosed 兼容 + Native C ABI)")
        val enabledMods = packageEngine.enabledModuleApks
        val enabledSos = packageEngine.enabledNativePlugins
        val desc = TextView(this).apply {
            text = "• 已挂载模块 APK 数量: ${enabledMods.size} (${enabledMods.joinToString { it.name }})\n" +
                    "• 已挂载 Native .so 插件: ${enabledSos.size} (${enabledSos.joinToString { it.name }})\n" +
                    "• 平级 ClassLoader 隔离: 模块运行在 SiblingBridgeClassLoader，目标 App 无法通过 Class.forName 探测到任何模块或 XposedBridge。"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        modCard.addView(desc)

        val importRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        importRow.addView(createActionButton("📂 导入外部模块 APK (ZeroSDK/LSPosed)", "#4F46E5") {
            openSystemFilePicker(REQ_IMPORT_MODULE_APK, "application/vnd.android.package-archive")
        })
        importRow.addView(createActionButton("📂 导入外部 Native 插件 (.so)", "#0284C7") {
            openSystemFilePicker(REQ_IMPORT_NATIVE_SO, "*/*")
        })
        modCard.addView(importRow)

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        testRow.addView(createActionButton("⚡ 测试真机 ART ArtMethod 原生 Hook", "#D97706") {
            runRealArtMethodHookVerification()
        })
        testRow.addView(createActionButton("▶ 执行内置 SampleZeroModule", "#059669") {
            val session = ZeroRuntimeSession.getCurrentOrCreateDefault(this)
            SampleZeroModule().onLoad(session)
            val logBytes = session.vfs.readPrivateFile("files/zerobox_module_audit.log")
            appendConsole("=== 自研 SDK 模块执行成功 ===\n" + String(logBytes, StandardCharsets.UTF_8))
        })
        testRow.addView(createActionButton("💉 注入 libzerobox_sample_plugin.so", "#7C3AED") {
            val session = ZeroRuntimeSession.getCurrentOrCreateDefault(this)
            val pluginSo = File(applicationInfo.nativeLibraryDir, "libzerobox_sample_plugin.so")
            val ok = session.loadNativePlugin(pluginSo.absolutePath)
            appendConsole(
                "=== Native .so 插件动态注入 ===\n" +
                        "路径: ${pluginSo.absolutePath}\n" +
                        "结果: ${if (ok) "SUCCESS (已自动加入 /proc/self/maps 隐藏白名单)" else "FAILED"}"
            )
        })
        modCard.addView(testRow)

        contentFrame.addView(modCard)
        contentFrame.addView(buildConsoleCard())
    }

    private fun runRealArtMethodHookVerification() {
        try {
            val targetMethod = MainActivity::class.java.getDeclaredMethod(
                "sampleArtHookTargetMethod",
                Int::class.javaPrimitiveType
            )
            val beforeHookVal = sampleArtHookTargetMethod(10) // 10 * 2 + 1 = 21

            val unhook = ZeroHookImpl.getInstance().hookMethod(
                targetMethod,
                object : ZeroHook.MethodCallback() {
                    override fun beforeCall(frame: ZeroHook.HookFrame) {
                        val origArg = frame.args[0] as Int
                        frame.args[0] = origArg * 10
                    }

                    override fun afterCall(frame: ZeroHook.HookFrame) {
                        val res = frame.result as Int
                        frame.result = res + 7000
                    }
                }
            )

            // Direct Java call (intercepted by C++ art_hook.cpp ArtMethod trampoline!)
            val directCallVal = sampleArtHookTargetMethod(10)
            unhook.unhook()
            val restoredVal = sampleArtHookTargetMethod(10)

            // Object + Primitive mixed method hook verification
            val objTargetMethod = MainActivity::class.java.getDeclaredMethod(
                "sampleArtHookObjectMethod",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val beforeObjVal = sampleArtHookObjectMethod("ZeroBox", 2)
            val unhookObj = ZeroHookImpl.getInstance().hookMethod(
                objTargetMethod,
                object : ZeroHook.MethodCallback() {
                    override fun beforeCall(frame: ZeroHook.HookFrame) {
                        frame.args[0] = "[Hooked:${frame.args[0]}]"
                        frame.args[1] = (frame.args[1] as Int) + 8
                    }

                    override fun afterCall(frame: ZeroHook.HookFrame) {
                        frame.result = "${frame.result}:VERIFIED"
                    }
                }
            )
            val hookedObjVal = sampleArtHookObjectMethod("ZeroBox", 2)
            unhookObj.unhook()
            val restoredObjVal = sampleArtHookObjectMethod("ZeroBox", 2)

            appendConsole(
                "=== ART ArtMethod 原生 Hook 引擎实测 (基本类型 + 引用类型) ===\n" +
                        "1. [基本类型] Hook 前: $beforeHookVal | Hook 后: $directCallVal (期望: 7201) | Unhook 后: $restoredVal\n" +
                        "2. [Object/String] Hook 前: $beforeObjVal\n" +
                        "3. [Object/String] Hook 后: $hookedObjVal (期望: [Hooked:ZeroBox]-v10:VERIFIED)\n" +
                        "4. [Object/String] Unhook 后: $restoredObjVal"
            )
        } catch (t: Throwable) {
            appendConsole("ART Hook 测试异常: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // =========================================================================
    // TAB 4: Shizuku-Style Cross-Process Memory & VFS Console
    // =========================================================================
    private fun renderCrossProcessConsoleTab() {
        contentFrame.removeAllViews()

        val rpcCard = createCardLayout("类 Shizuku 跨进程实时控制通道 (IZeroControlSession Binder)")
        val desc = TextView(this).apply {
            text = "通过直接连接容器槽位进程的 IZeroControlSession Binder 句柄，可在不修改 APK 的情况下实时执行跨进程内存特征码搜索、安全内存读写 (process_vm_readv/writev) 与私有目录文件流读写。"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        rpcCard.addView(desc)

        val patternInput = EditText(this).apply {
            setText("7F 45 4C 46")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "输入十六进制特征码 (支持 ? 或 ?? 通配符)"
        }
        rpcCard.addView(patternInput)

        val actionRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actionRow1.addView(createActionButton("跨进程扫描 libc.so 特征码并读取内存", "#059669") {
            val client = acquireControlClient()
            val pattern = patternInput.text.toString().trim()
            val hits = client.scanPattern("libc.so", pattern, 5)
            val sb = StringBuilder("=== 跨进程控制会话 (PID: ${client.pid}) ===\n")
            sb.append("特征码 [").append(pattern).append("] 命中数量: ").append(hits.size).append('\n')
            if (hits.isNotEmpty()) {
                val firstAddr = hits[0]
                val dump = client.readMemory(firstAddr, 16)
                val hexDump = dump.joinToString(" ") { String.format("%02X", it) }
                sb.append("首地址 0x").append(java.lang.Long.toHexString(firstAddr))
                    .append(" 读取 16 字节: ").append(hexDump).append('\n')
            }
            appendConsole(sb.toString())
        })

        actionRow1.addView(createActionButton("跨进程读写目标私有目录", "#0284C7") {
            val client = acquireControlClient()
            val testContent = "Written via Shizuku-style ZeroControlClient at ${System.currentTimeMillis()}\n"
            client.writePrivateFile(
                "files/remote_control_test.txt",
                testContent.toByteArray(StandardCharsets.UTF_8),
                false
            )
            val files = client.listPrivateFiles("files")
            val readBack = String(
                client.readPrivateFile("files/remote_control_test.txt"),
                StandardCharsets.UTF_8
            )
            appendConsole(
                "=== 跨进程私有目录操作 (PID: ${client.pid}) ===\n" +
                        "files/ 目录文件列表: $files\n" +
                        "读取回显内容: $readBack"
            )
        })

        rpcCard.addView(actionRow1)
        contentFrame.addView(rpcCard)
        contentFrame.addView(buildConsoleCard())
    }

    // =========================================================================
    // TAB 5: Stealth & Anti-Detection Audit
    // =========================================================================
    private fun renderStealthAuditTab() {
        contentFrame.removeAllViews()

        val auditCard = createCardLayout("容器隐蔽性与反检测全项自检中心 (6-Point Clean-Room Audit)")
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(10))
        }
        btnRow.addView(createActionButton("在当前进程执行隐蔽性自检", "#D97706") {
            val hostApk = File(applicationInfo.sourceDir)
            val cleanGuestLoader = ZeroInstrumentation.createCleanGuestClassLoader(hostApk, null)
            val report = StealthSelfChecker.generateFullReport(
                this,
                activeTargetPackage,
                hostApk,
                cleanGuestLoader
            )
            appendConsole(report)
        })
        btnRow.addView(createActionButton("跨进程拉取槽位 :p0 隐蔽性自检报告", "#0284C7") {
            val client = acquireControlClient()
            appendConsole(client.runStealthSelfCheckReport())
        })
        btnRow.addView(createActionButton("验证平级 ClassLoader 单向隔离", "#059669") {
            val hostApk = File(applicationInfo.sourceDir)
            val cleanGuestLoader = ZeroInstrumentation.createCleanGuestClassLoader(hostApk, null)
            val siblingLoader = SiblingModuleLoader.SiblingBridgeClassLoader(
                classLoader,
                cleanGuestLoader
            )
            val guestBlocked = StealthSelfChecker.isClassHiddenFromGuestLoader(
                cleanGuestLoader,
                "de.robv.android.xposed.XposedBridge"
            )
            val moduleCanAccessXposed = try {
                siblingLoader.loadClass("de.robv.android.xposed.XposedBridge") != null
            } catch (_: Throwable) {
                false
            }
            appendConsole(
                "=== 平级 ClassLoader 单向隔离验证 ===\n" +
                        "1. 目标 App ClassLoader 查找 XposedBridge 被拦截: $guestBlocked (期望: true)\n" +
                        "2. 模块 SiblingClassLoader 访问 XposedBridge 成功: $moduleCanAccessXposed (期望: true)"
            )
        })
        auditCard.addView(btnRow)
        contentFrame.addView(auditCard)
        contentFrame.addView(buildConsoleCard())

        val hostApk = File(applicationInfo.sourceDir)
        val cleanGuestLoader = ZeroInstrumentation.createCleanGuestClassLoader(hostApk, null)
        appendConsole(
            StealthSelfChecker.generateFullReport(
                this,
                activeTargetPackage,
                hostApk,
                cleanGuestLoader
            )
        )
    }

    private fun openSystemFilePicker(requestCode: Int, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
            }
            startActivityForResult(intent, requestCode)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开系统文件选择器: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            return
        }
        val uri: Uri = data.data!!
        try {
            when (requestCode) {
                REQ_IMPORT_GUEST_APK -> {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val imported = packageEngine.importUnmodifiedApkStream(stream)
                        activeTargetPackage = imported.packageName
                        activeUserId = 0
                        renderVirtualAppsTab()
                        appendConsole("已从存储成功导入原装 APK: ${imported.appLabel} (${imported.packageName})")
                    }
                }
                REQ_IMPORT_MODULE_APK -> {
                    val tmpFile = copyUriToTempFile(uri, "mod_import.apk")
                    val saved = packageEngine.importModuleApk(tmpFile, tmpFile.name)
                    tmpFile.delete()
                    renderModuleSandboxTab()
                    appendConsole("已成功导入模块 APK: ${saved.absolutePath}")
                }
                REQ_IMPORT_NATIVE_SO -> {
                    val tmpFile = copyUriToTempFile(uri, "plugin_import.so")
                    val saved = packageEngine.importNativePluginSo(tmpFile, tmpFile.name)
                    tmpFile.delete()
                    renderModuleSandboxTab()
                    appendConsole("已成功导入纯 Native 插件 (.so): ${saved.absolutePath}")
                }
            }
        } catch (t: Throwable) {
            appendConsole("导入文件失败: ${t.message}")
        }
    }

    private fun copyUriToTempFile(uri: Uri, prefixName: String): File {
        val outFile = File(cacheDir, "${System.currentTimeMillis()}_$prefixName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(16384)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                }
            }
        }
        return outFile
    }

    private fun acquireControlClient(): ZeroControlClient {
        return try {
            val uri = Uri.parse("content://$packageName.zerobox.slot.p0")
            val bundle = contentResolver.call(
                uri,
                SlotProvider.METHOD_GET_CONTROL_SESSION,
                null,
                null
            )
            val binder = bundle?.getBinder(SlotProvider.KEY_CONTROL_BINDER)
            if (binder != null) {
                ZeroControlClient.fromBinder(binder)
            } else {
                val localSession = ZeroRuntimeSession.getCurrentOrCreateDefault(this)
                ZeroControlClient.fromBinder(localSession.controlAgentBinder)
            }
        } catch (_: Throwable) {
            val localSession = ZeroRuntimeSession.getCurrentOrCreateDefault(this)
            ZeroControlClient.fromBinder(localSession.controlAgentBinder)
        }
    }

    private fun buildConsoleCard(): View {
        val card = createCardLayout("实时运行输出与诊断日志 (Live Diagnostics)")
        consoleOutputView = TextView(this).apply {
            text = "Ready. 请点击上方功能按钮导入应用、启动分身或执行跨进程调试指令。"
            setTextColor(Color.parseColor("#A7F3D0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.parseColor("#090D16"))
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        card.addView(consoleOutputView)
        return card
    }

    private fun appendConsole(msg: String) {
        if (::consoleOutputView.isInitialized) {
            consoleOutputView.text = msg
        }
    }

    private fun createCardLayout(titleText: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            background = bg
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }

            val header = TextView(this@MainActivity).apply {
                text = titleText
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            }
            addView(header)
        }
    }

    private fun createActionButton(
        label: String,
        hexColor: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = dp(6).toFloat()
            }
            background = bg
            val padH = dp(12)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(8)
                bottomMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createToggleSwitch(
        label: String,
        initialState: Boolean,
        onChanged: (Boolean) -> Unit
    ): CheckBox {
        return CheckBox(this).apply {
            text = label
            isChecked = initialState
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
