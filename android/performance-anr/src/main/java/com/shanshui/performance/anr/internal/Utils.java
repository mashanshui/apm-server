/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.shanshui.performance.anr.internal;

import android.os.Looper;

/** Matrix ANR 所需的主线程栈读取方法。 */
final class Utils {
    /** 工具类不创建实例。 */
    private Utils() {}

    /** 按 Matrix 原始格式返回主线程 Java 栈。 */
    static String getMainThreadJavaStackTrace() {
        /** 按 Matrix 原格式拼接的主线程栈。 */
        StringBuilder stackTrace = new StringBuilder();
        for (StackTraceElement stackTraceElement : Looper.getMainLooper().getThread().getStackTrace()) {
            stackTrace.append(stackTraceElement.toString()).append("\n");
        }
        return stackTrace.toString();
    }
}
