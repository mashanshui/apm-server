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

import android.os.Build;
import android.util.ArrayMap;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** 仅保留 Matrix ANR 判定所使用的前台判断。 */
enum AppForegroundUtil {
    INSTANCE;

    /** 保留 Matrix 初始化状态。 */
    private boolean isInit = false;

    /** 保持 Matrix 的初始化语义。 */
    public void init() {
        if (isInit) {
            return;
        }
        this.isInit = true;
    }

    /** 使用 Matrix 原有 Activity 反射判断。 */
    public static boolean isInterestingToUser() {
        return isActivityInterestingToUser();
    }

    /** 判断是否存在未暂停的 Activity。 */
    private static boolean isActivityInterestingToUser() {
        try {
            /** Matrix 反射使用的 ActivityThread 类型。 */
            Class activityThreadClass = Class.forName("android.app.ActivityThread");
            /** 当前进程的 ActivityThread。 */
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            /** Matrix 反射使用的 Activity 列表字段。 */
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);

            /** 当前进程中的 Activity 记录。 */
            Map<Object, Object> activities;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                activities = (HashMap<Object, Object>) activitiesField.get(activityThread);
            } else {
                activities = (ArrayMap<Object, Object>) activitiesField.get(activityThread);
            }
            if (activities.size() < 1) {
                return false;
            }
            for (Object activityRecord : activities.values()) {
                /** 当前 Activity 记录类型。 */
                Class activityRecordClass = activityRecord.getClass();
                /** Matrix 用于判断前台状态的 paused 字段。 */
                Field pausedField = activityRecordClass.getDeclaredField("paused");

                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    return true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
