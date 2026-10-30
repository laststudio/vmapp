package com.zerobox.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Built-in Guest Target Activity that can be launched inside an isolated ZeroBox slot (:p0 ~ :p3)
 * to verify real Activity instantiation, GuestContextWrapper isolation, Seccomp-BPF VFS redirection,
 * SharedPreferences isolation, and in-process module hooks.
 */
class BuiltInGuestTargetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Write a verification file and SharedPreferences using standard Android Context APIs
        val prefs = getSharedPreferences("guest_target_prefs", MODE_PRIVATE)
        val launchCount = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", launchCount).apply()

        val markerFile = File(filesDir, "guest_runtime_verified.txt")
        markerFile.writeText(
            "Guest Activity verified at ${System.currentTimeMillis()} (launch #$launchCount)\n" +
                    "packageName=$packageName\n" +
                    "filesDir=${filesDir.absolutePath}\n",
            StandardCharsets.UTF_8
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
        }

        val badge = TextView(this).apply {
            text = "RUNNING INSIDE ZEROBOX CONTAINER SLOT"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(badge)

        val title = TextView(this).apply {
            text = "目标应用真实 Activity 已在容器内启动"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(title)

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
            }
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val details = TextView(this).apply {
            text = StringBuilder()
                .append("• 当前进程 PID           : ").append(Process.myPid()).append('\n')
                .append("• Context.getPackageName : ").append(packageName).append('\n')
                .append("• 隔离私有目录 (filesDir) : \n  ").append(filesDir.absolutePath).append('\n')
                .append("• 隔离数据库目录         : \n  ").append(getDatabasePath("app.db").parent).append('\n')
                .append("• 原装 APK 路径          : \n  ").append(packageCodePath).append('\n')
                .append("• 当前分身独立启动次数    : #").append(launchCount).append('\n')
                .append("• ClassLoader 类型       : ").append(classLoader.javaClass.name)
                .toString()
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        infoCard.addView(details)
        root.addView(infoCard)

        val closeBtn = Button(this).apply {
            text = "返回 ZeroBox 管理端"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { finish() }
        }
        root.addView(closeBtn)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
