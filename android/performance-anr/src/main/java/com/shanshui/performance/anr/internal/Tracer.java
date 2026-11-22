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

import android.util.Log;

/** 保留 Matrix Tracer 的启动和关闭顺序。 */
abstract class Tracer {
    /** Matrix 原有的运行状态。 */
    private volatile boolean isAlive = false;

    /** 执行子类启动逻辑。 */
    protected void onAlive() {
        Log.i("PerformanceAnr", "Matrix 采集器进入启动流程");
    }

    /** 执行子类关闭逻辑。 */
    protected void onDead() {
        Log.i("PerformanceAnr", "Matrix 采集器进入关闭流程");
    }

    /** 按 Matrix 的同步状态切换启动采集。 */
    public final synchronized void onStartTrace() {
        if (!isAlive) {
            this.isAlive = true;
            onAlive();
        }
    }

    /** 按 Matrix 的同步状态切换关闭采集。 */
    public final synchronized void onCloseTrace() {
        if (isAlive) {
            this.isAlive = false;
            onDead();
        }
    }

    /** 返回 Matrix Tracer 的状态，不代表已验证所有 hook。 */
    public boolean isAlive() {
        return isAlive;
    }
}
