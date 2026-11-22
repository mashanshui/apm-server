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

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.util.Pair;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;



import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignalAnrTracer extends Tracer {
    /** 日志标签。 */
    private static final String TAG = "SignalAnrTracer";

    /** 进程错误状态检查线程名称。 */
    private static final String CHECK_ANR_STATE_THREAD_NAME = "Check-ANR-State-Thread";
    /** ANR 采样线程名称。 */
    private static final String ANR_DUMP_THREAD_NAME = "ANR-Dump-Thread";
    /** 进程错误状态的轮询间隔。 */
    private static final int CHECK_ERROR_STATE_INTERVAL = 500;
    /** Matrix 原有的最大等待时长。 */
    private static final int ANR_DUMP_MAX_TIME = 20000;
    /** 等待 Java 采样的超时时间。 */
    private static long anrReportTimeout = ANR_DUMP_MAX_TIME;
    /** 原实现允许的错误状态检查次数。 */
    private static final int CHECK_ERROR_STATE_COUNT =
            ANR_DUMP_MAX_TIME / CHECK_ERROR_STATE_INTERVAL;
    /** 前台消息过期阈值。 */
    private static final long FOREGROUND_MSG_THRESHOLD = -2000;
    /** 后台消息过期阈值。 */
    private static final long BACKGROUND_MSG_THRESHOLD = -10000;
    /** 当前采样时的前后台状态。 */
    private static boolean currentForeground = false;
    /** Matrix 使用的外部 SIGQUIT trace 路径。 */
    private static String sAnrTraceFilePath = "";
    /** Matrix 使用的手动 trace 路径。 */
    private static String sPrintTraceFilePath = "";
    /** 接收 Matrix 原始判定回调的适配器。 */
    private static SignalAnrDetectedListener sSignalAnrDetectedListener;
    /** SDK 本地报告适配器，在 Matrix 原有 trace 回调完成后接收文件路径。 */
    private static TraceFileListener sTraceFileListener;
    /** 用于查询进程错误状态的应用。 */
    private static Application sApplication;
    /** Matrix 原有的进程级初始化标志。 */
    private static boolean hasInit = false;
    /** Matrix 原有的实例存在标志。 */
    public static boolean hasInstance = false;
    /** 主线程消息相对当前时间的差值。 */
    private static long anrMessageWhen = 0L;
    /** 主线程消息的原始文本。 */
    private static String anrMessageString = "";
    /** 采样时读取的 cgroup。 */
    private static String cgroup = "";
    /** SIGQUIT 分支的主线程 Java 栈。 */
    private static String stackTrace = "";
    /** debugger 分支的主线程 Java 栈。 */
    private static String nativeBacktraceStackTrace = "";
    /** Matrix 最近一次回调时间。 */
    private static long lastReportedTimeStamp = 0;
    /** Matrix 开始采样的时间。 */
    private static long onAnrDumpedTimeStamp = 0;


    private static class SimpleDeadLockDetector {
        static class ThreadNode {
            /** Matrix 解析出的 ART 锁线程 ID。 */
            int threadId;
            /** 该线程的原始 trace 文本。 */
            String info;
            /** 当前等待锁的类型。 */
            String lockObjCls;
            /** 持有等待锁的线程 ID。 */
            int peerId = -1;
            /** 深度优先遍历状态。 */
            int visit = 0;  // 0 not visited, 1 visiting, 2 visited
        }

        /** Matrix 原有线程行匹配表达式。 */
        private final Pattern threadPattern = Pattern.compile("^\"(.*?)\" .*? tid=(\\d+) \\w+$");
        /** Matrix 原有锁等待行匹配表达式。 */
        private final Pattern lockHeldPattern = Pattern.compile("^  - .*?\\(a (.*?)\\) held by thread (\\d+)$");
        /** 当前线程的原始文本。 */
        private final StringBuilder currentSb = new StringBuilder();
        /** Matrix 原有等待线程映射。 */
        private final HashMap<Integer, ThreadNode> threadsWaitingForHeldLock = new HashMap<>();
        /** 深度优先搜索中的等待线程路径。 */
        private LinkedList<ThreadNode> waitingList = new LinkedList<>();
        /** Matrix 实际记录的主线程文本。 */
        private String mainThreadInfo = "";
        /** 当前是否处于线程信息段。 */
        private boolean threadInfoBegin = false;
        /** 正在解析的线程节点。 */
        private ThreadNode currentThreadInfo = new ThreadNode();

        /** 按 Matrix 原逻辑处理一行 trace。 */
        public void parseLine(String line) {

            if (line.isEmpty()) {
                // thread info end
                threadInfoBegin = false;

                if (currentSb.length() > 0 && currentThreadInfo.peerId >= 0) {
                    /** 本次解析完成的线程文本。 */
                    String threadInfo = currentSb.toString();
                    if (currentThreadInfo.threadId == 1) {
                        // "currentThreadId" is a thin lock thread id. This is a small integer used by the
                        // thin lock implementation. This is not to be confused with the native thread's tid,
                        // nor is it the value returned by java.lang.Thread.getId --- this is a distinct value,
                        // used only for locking. usually, 0 is reserved to mean "invalid", 1 for main thread.
                        mainThreadInfo = threadInfo;
                    }

                    currentThreadInfo.info = threadInfo;
                    threadsWaitingForHeldLock.put(currentThreadInfo.threadId, currentThreadInfo);
                    currentThreadInfo = new ThreadNode();
                }

            } else if (!threadInfoBegin) {
                /** 线程行匹配结果。 */
                Matcher m = threadPattern.matcher(line);
                if (m.find()) {
                    // new thread info begin
                    threadInfoBegin = true;

                    currentSb.setLength(0);
                    currentSb.append(line).append('\n');
                    try {
                        currentThreadInfo.threadId = Integer.parseInt(Objects.requireNonNull(m.group(2)));
                    } catch (Exception e) {
                        MatrixLog.e(TAG, e.toString());
                    }
                }
            } else {
                /** 锁等待行匹配结果。 */
                Matcher m = lockHeldPattern.matcher(line);
                if (m.find()) {
                    try {
                        currentThreadInfo.lockObjCls = m.group(1);
                        currentThreadInfo.peerId = Integer.parseInt(Objects.requireNonNull(m.group(2)));
                    } catch (Exception e) {
                        MatrixLog.e(TAG, e.toString());
                    }
                }
                currentSb.append(line).append('\n');
            }
        }

        /** 结束当前段并按 Matrix 原逻辑检查锁环。 */
        public boolean hasDeadLock() {
            parseLine("");  // ensure thread info parse complete
            return checkDeadLock();
        }

        /** 返回 Matrix 保存的主线程文本。 */
        @NonNull
        public String getMainThreadInfo() {
            return mainThreadInfo;
        }

        /** 返回锁等待路径中的第一个线程文本。 */
        @NonNull
        public String getLockHeldThread1Info() {
            if (waitingList == null || waitingList.size() == 0) {
                return "";
            }
            /** 等待路径中的线程 ID。 */
            int threadId = waitingList.get(0).threadId;
            /** 当前 ID 对应的线程节点。 */
            ThreadNode node = threadsWaitingForHeldLock.get(threadId);
            return node == null ? "" : node.info;
        }

        /** 返回锁等待路径中的最后一个线程文本。 */
        @NonNull
        public String getLockHeldThread2Info() {
            if (waitingList == null || waitingList.size() == 0) {
                return "";
            }
            /** 等待路径中的线程 ID。 */
            int threadId = waitingList.get(waitingList.size() - 1).threadId;
            /** 当前 ID 对应的线程节点。 */
            ThreadNode node = threadsWaitingForHeldLock.get(threadId);
            return node == null ? "" : node.info;
        }

        private static class Pair<F, S> implements Map.Entry<F, S> {
            /** 等待线程 ID 集合。 */
            F f;
            /** 等待锁类型集合。 */
            S s;

            /** 保存 Matrix 原有的等待线程与锁类型。 */
            Pair(F f, S s) {
                this.f = f;
                this.s = s;
            }

            /** 返回等待线程集合。 */
            @Override
            public F getKey() {
                return f;
            }

            /** 返回锁类型集合。 */
            @Override
            public S getValue() {
                return s;
            }

            /** 按 Map.Entry 契约设置值。 */
            @Override
            public S setValue(S value) {
                return s = value;
            }

            /** 返回 Matrix 原有的节点对字符串。 */
            @Override
            public String toString() {
                return "Pair{" + "f=" + f + ", s=" + s + '}';
            }
        }

        /** 按 Matrix 原顺序返回等待线程及锁类型。 */
        @NonNull
        public Map.Entry<int[], String[]> getWaitingThreadsInfo() {
            if (waitingList.size() == 0) {
                return new Pair<>(null, null);
            } else {
                /** 按 Matrix 原顺序收集的线程 ID。 */
                int[] threadsId = new int[waitingList.size()];
                /** 按 Matrix 原顺序收集的锁类型。 */
                String[] locksType = new String[waitingList.size()];
                /** 等待路径数组下标。 */
                int idx = 0;
                for (ThreadNode threadNode : waitingList) {
                    threadsId[idx] = threadNode.threadId;
                    locksType[idx] = threadNode.lockObjCls;
                    ++idx;
                }
                return new Pair<>(threadsId, locksType);
            }
        }

        /** 遍历 Matrix 原有的等待关系并查找锁环。 */
        private boolean checkDeadLock() {
            waitingList.clear();
            for (Map.Entry<Integer, ThreadNode> nodeEntry : threadsWaitingForHeldLock.entrySet()) {
                /** 当前遍历的等待节点。 */
                ThreadNode node = nodeEntry.getValue();
                if (node.visit == 0) {
                    /** 深度优先搜索找到的锁环入口。 */
                    ThreadNode ret;
                    if ((ret = dfsSearch(node)) != null) {
                        // retrieve cycle from path and save it in waitingList
                        while (waitingList.size() > 0 && waitingList.getFirst() != ret) {
                            waitingList.removeFirst();
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        // Return the entry point if a cycle is found, else return null.
        /** 按 Matrix 原有深度优先搜索查找锁环。 */
        private ThreadNode dfsSearch(ThreadNode node) {
            waitingList.addLast(node);
            node.visit = 1;

            /** 当前等待节点对应的持锁节点。 */
            ThreadNode peerNode = threadsWaitingForHeldLock.get(node.peerId);
            if (peerNode != null) {
                if (peerNode.visit == 1) {
                    return peerNode;
                }

                /** 深度优先搜索找到的锁环入口。 */
                ThreadNode ret;
                if (peerNode.visit == 0 && (ret = dfsSearch(peerNode)) != null) {
                    return ret;
                }
            }

            node.visit = 2;
            waitingList.removeLast();
            return null;
        }
    }

    /** 按 Matrix 原顺序初始化信号采集。 */
    @Override
    protected void onAlive() {
        super.onAlive();
        if (!hasInit) {
            nativeInitSignalAnrDetective(sAnrTraceFilePath, sPrintTraceFilePath);
            AppForegroundUtil.INSTANCE.init();
            hasInit = true;
        }

    }

    /** 按 Matrix 原顺序关闭信号采集。 */
    @Override
    protected void onDead() {
        super.onDead();
        nativeFreeSignalAnrDetective();
    }

    /** 使用宿主应用构造 Matrix 采集器。 */
    public SignalAnrTracer(Application application) {
        hasInstance = true;
        sApplication = application;
    }

    /** 使用宿主应用和两个活动 trace 文件构造采集器。 */
    public SignalAnrTracer(Application application, String anrTraceFilePath, String printTraceFilePath) {
        hasInstance = true;
        sAnrTraceFilePath = anrTraceFilePath;
        sPrintTraceFilePath = printTraceFilePath;
        sApplication = application;
    }

    /** 设置 Matrix 等待 Java 采样的时间。 */
    public static void setAnrReportTimeout(long timeout) {
        anrReportTimeout = timeout;
    }

    /** 设置 Matrix 原有判定回调。 */
    public void setSignalAnrDetectedListener(SignalAnrDetectedListener listener) {
        sSignalAnrDetectedListener = listener;
    }

    /** 设置 Matrix trace 文件完成后的 SDK 适配器。 */
    public void setTraceFileListener(TraceFileListener listener) {
        sTraceFileListener = listener;
    }

    /** 按 Matrix 原逻辑读取当前进程 cgroup。 */
    public static String readCgroup() {
        /** 读取到的 cgroup 文本。 */
        StringBuilder ret = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/cgroup")))) {
            /** 当前读取的原始文本行。 */
            String line;
            while ((line = reader.readLine()) != null) {
                ret.append(line).append("\n");
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return ret.toString();
    }

    /** 按 Matrix 原阈值或错误状态确认结果。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private static void confirmRealAnr(final boolean isSigQuit) {
        MatrixLog.i(TAG, "confirmRealAnr, isSigQuit = " + isSigQuit);
        /** Matrix 消息阈值判断结果。 */
        boolean needReport = isMainThreadBlocked();
        if (needReport) {
            report(false, isSigQuit);
        } else {
            new Thread(new Runnable() {
                /** 执行 Matrix 原有后台采样或错误状态轮询。 */
                @Override
                public void run() {
                    checkErrorStateCycle(isSigQuit);
                }
            }, CHECK_ANR_STATE_THREAD_NAME).start();
        }
    }

    /** 执行 Matrix 原有的主线程采样和等待。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Keep
    private synchronized static void onANRDumped() {
        /** 等待 Matrix Java 采样的同步门闩。 */
        final CountDownLatch anrDumpLatch = new CountDownLatch(1);
        new Thread(new Runnable() {
            /** 执行 Matrix 原有后台采样或错误状态轮询。 */
            @Override
            public void run() {
                onAnrDumpedTimeStamp = System.currentTimeMillis();
                MatrixLog.i(TAG, "onANRDumped");
                stackTrace = Utils.getMainThreadJavaStackTrace();
                cgroup = readCgroup();
                MatrixLog.i(TAG, "onANRDumped, read cgroup duration = %d", (System.currentTimeMillis() - onAnrDumpedTimeStamp));
                currentForeground = AppForegroundUtil.isInterestingToUser();
                MatrixLog.i(TAG, "onANRDumped, isInterestingToUser duration = %d", (System.currentTimeMillis() - onAnrDumpedTimeStamp));
                confirmRealAnr(true);
                anrDumpLatch.countDown();
            }
        }, ANR_DUMP_THREAD_NAME).start();

        try {
            anrDumpLatch.await(anrReportTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            //empty here
        }
    }

    /** 按 Matrix 原解析器处理已截取 trace。 */
    @Keep
    private static void onANRDumpTrace() {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(sAnrTraceFilePath)), "UTF-8"))) {
                /** 当前读取的原始文本行。 */
                String line;
                /** Matrix 原有死锁解析器。 */
                SimpleDeadLockDetector detector = new SimpleDeadLockDetector();
                while ((line = reader.readLine()) != null) {
                    detector.parseLine(line);
                }
                if (sSignalAnrDetectedListener != null) {
                    if (detector.hasDeadLock()) {
                        sSignalAnrDetectedListener.onDeadLockAnrDetected(
                                detector.getMainThreadInfo(), detector.getLockHeldThread1Info(),
                                detector.getLockHeldThread2Info(), detector.getWaitingThreadsInfo());
                    } else if (detector.getMainThreadInfo().contains("android.os.MessageQueue.nativePollOnce")) {
                        sSignalAnrDetectedListener.onMainThreadStuckAtNativePollOnce(detector.getMainThreadInfo());
                    }
                }
            } catch (Throwable t) {
                MatrixLog.e(TAG, "printFileByLine failed e : " + t.getMessage());
            }
        } catch (Throwable t) {
            MatrixLog.e(TAG, "onANRDumpTrace error: %s", t.getMessage());
        }
        if (sTraceFileListener != null) {
            sTraceFileListener.onAnrTraceFile(sAnrTraceFilePath);
        }
    }

    /** 处理 Matrix 的手动 trace 完成回调。 */
    @Keep
    private static void onPrintTrace() {
        try {
            MatrixLog.i(TAG, "手动 trace 回调完成");
        } catch (Throwable t) {
            MatrixLog.e(TAG, "onPrintTrace error: %s", t.getMessage());
        }
        if (sTraceFileListener != null) {
            sTraceFileListener.onPrintTraceFile(sPrintTraceFilePath);
        }
    }

    /** 按 Matrix 原去重逻辑处理 debugger 信号。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Keep
    private static void onNativeBacktraceDumped() {
        MatrixLog.i(TAG, "happens onNativeBacktraceDumped");
        if (System.currentTimeMillis() - lastReportedTimeStamp < ANR_DUMP_MAX_TIME) {
            MatrixLog.i(TAG, "report SIGQUIT recently, just return");
            return;
        }
        nativeBacktraceStackTrace = Utils.getMainThreadJavaStackTrace();

        confirmRealAnr(false);
    }

    /** 向适配器转发 Matrix 实际发生的原始回调。 */
    private static void report(boolean fromProcessErrorState, boolean isSigQuit) {
        try {
            if (sSignalAnrDetectedListener != null) {
                if (isSigQuit) {
                    sSignalAnrDetectedListener.onAnrDetected(stackTrace, anrMessageString, anrMessageWhen, fromProcessErrorState, cgroup);
                } else {
                    sSignalAnrDetectedListener.onNativeBacktraceDetected(nativeBacktraceStackTrace, anrMessageString, anrMessageWhen, fromProcessErrorState);
                }
                return;
            }

        } finally {
            lastReportedTimeStamp = System.currentTimeMillis();
        }
    }


    /** 按 Matrix 原消息时间阈值判断主线程。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private static boolean isMainThreadBlocked() {
        try {
            /** 主线程消息队列。 */
            MessageQueue mainQueue = Looper.getMainLooper().getQueue();
            /** Matrix 通过反射读取的队列字段。 */
            Field field = mainQueue.getClass().getDeclaredField("mMessages");
            field.setAccessible(true);
            /** 主线程消息队列中的首条消息。 */
            final Message mMessage = (Message) field.get(mainQueue);
            if (mMessage != null) {
                anrMessageString = mMessage.toString();
                /** 消息预定执行时间。 */
                long when = mMessage.getWhen();
                if (when == 0) {
                    return false;
                }
                /** 消息预定时间与当前运行时间的差值。 */
                long time = when - SystemClock.uptimeMillis();
                anrMessageWhen = time;
                /** 按前后台状态选择的 Matrix 阈值。 */
                long timeThreshold = BACKGROUND_MSG_THRESHOLD;
                if (currentForeground) {
                    timeThreshold = FOREGROUND_MSG_THRESHOLD;
                }
                return time < timeThreshold;
            } else {
                MatrixLog.i(TAG, "mMessage is null");
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }


    /** 按 Matrix 原次数和间隔轮询进程错误状态。 */
    private static void checkErrorStateCycle(boolean isSigQuit) {
        /** 已进行的进程错误状态检查次数。 */
        int checkErrorStateCount = 0;
        while (checkErrorStateCount < CHECK_ERROR_STATE_COUNT) {
            try {
                checkErrorStateCount++;
                /** 当前轮进程错误状态的判断结果。 */
                boolean myAnr = checkErrorState();
                if (myAnr) {
                    report(true, isSigQuit);
                    break;
                }

                Thread.sleep(CHECK_ERROR_STATE_INTERVAL);
            } catch (Throwable t) {
                MatrixLog.e(TAG, "checkErrorStateCycle error, e : " + t.getMessage());
                break;
            }
        }
    }

    /** 按 Matrix 原 UID 和进程判断检查系统错误状态。 */
    private static boolean checkErrorState() {
        try {
            MatrixLog.i(TAG, "[checkErrorState] start");
            /** 当前宿主应用。 */
            Application application = sApplication;
            /** 用于查询进程状态的系统服务。 */
            ActivityManager am = (ActivityManager) application
                    .getSystemService(Context.ACTIVITY_SERVICE);

            /** 系统返回的进程错误状态列表。 */
            List<ActivityManager.ProcessErrorStateInfo> procs = am.getProcessesInErrorState();
            if (procs == null) {
                MatrixLog.i(TAG, "[checkErrorState] procs == null");
                return false;
            }

            for (ActivityManager.ProcessErrorStateInfo proc : procs) {

                if (proc.uid != android.os.Process.myUid()
                        && proc.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                    MatrixLog.i(TAG, "maybe received other apps ANR signal");
                    return false;
                }

                if (proc.pid != android.os.Process.myPid()) continue;

                if (proc.condition != ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                    continue;
                }


                return true;
            }
            return false;
        } catch (Throwable t) {
            MatrixLog.e(TAG, "[checkErrorState] error : %s", t.getMessage());
        }
        return false;
    }

    /** 按 Matrix 原方式请求手动 trace。 */
    public static void printTrace() {
        if (!hasInstance) {
            MatrixLog.e(TAG, "SignalAnrTracer has not been initialize");
            return;
        }
        if (sPrintTraceFilePath.equals("")) {
            MatrixLog.e(TAG, "PrintTraceFilePath has not been set");
            return;
        }
        nativePrintTrace();
    }

    /** 初始化 Matrix 原生信号处理器。 */
    private static native void nativeInitSignalAnrDetective(String anrPrintTraceFilePath, String printTraceFilePath);

    /** 关闭 Matrix 原生信号处理器。 */
    private static native void nativeFreeSignalAnrDetective();

    /** 由 Matrix 原生代码发出手动 SIGQUIT。 */
    private static native void nativePrintTrace();

    public interface SignalAnrDetectedListener {
        /** 接收 Matrix 的 SIGQUIT ANR 判定结果。 */
        void onAnrDetected(String stackTrace, String mMessageString, long mMessageWhen, boolean fromProcessErrorState, String cpuset);

        /** 接收 debugger 分支的主线程 Java 栈。 */
        void onNativeBacktraceDetected(String backtrace, String mMessageString, long mMessageWhen, boolean fromProcessErrorState);

        /** 接收 Matrix 原有锁环解析结果。 */
        void onDeadLockAnrDetected(String mainThreadStackTrace, String lockHeldThread1, String lockHeldThread2, Map.Entry<int[], String[]> waitingList);

        /** 接收 Matrix 原有 nativePollOnce 判断。 */
        void onMainThreadStuckAtNativePollOnce(String mainThreadStackTrace);
    }

    /** 在原始回调结束后，将 Matrix 已写出的 trace 路径交给 SDK。 */
    public interface TraceFileListener {
        /** 外部 SIGQUIT trace 已触发 Matrix 回调。 */
        void onAnrTraceFile(String path);

        /** 手动 trace 已触发 Matrix 回调。 */
        void onPrintTraceFile(String path);
    }
}
