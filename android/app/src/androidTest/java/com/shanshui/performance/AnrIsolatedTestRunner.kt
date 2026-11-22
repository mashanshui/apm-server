package com.shanshui.performance

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** 让 ANR SDK 组合测试自行控制初始化顺序，不启动示例 App 的其他后台任务。 */
class AnrIsolatedTestRunner : AndroidJUnitRunner() {
    /** 使用普通测试 Application，实际的 SDK 初始化由测试用例完成。 */
    override fun newApplication(classLoader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(classLoader, Application::class.java.name, context)
}
