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
import java.util.UUID;
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
    /** Matrix 原有的最大等待时长。 */
    private static final int ANR_DUMP_MAX_TIME = 20000;
    /** 等待 Java 采样的超时时间。 */
    private static long anrReportTimeout = ANR_DUMP_MAX_TIME;
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
    /** Matrix 最近一次回调时间。 */
    private static long lastReportedTimeStamp = 0;
    /** 同时在判定窗口内的采集会使系统说明归属不明确。 */
    private static final Map<String, Capture> activeCaptures = new HashMap<>();

    /** 每次信号独立保存判定上下文，避免异步轮询互相覆盖。 */
    private static final class Capture {
        /** 与 Native trace 共用的采集标识。 */ final String id = UUID.randomUUID().toString();
        /** 捕获开始时所属的判定监听器。 */ final SignalAnrDetectedListener listener = sSignalAnrDetectedListener;
        /** 捕获开始时所属的 trace 监听器。 */ final TraceFileListener traceListener = sTraceFileListener;
        /** 信号采集开始的墙上时间。 */ final long occurredAt = System.currentTimeMillis();
        /** 关联窗口使用不受墙上时钟调整影响的时间。 */ final long startedElapsed = SystemClock.elapsedRealtime();
        /** 判定时的主线程栈。 */ String stack = "";
        /** 队首消息，不可读时为空。 */ String message;
        /** 队首消息时间差，不可读时为空。 */ Long messageWhen;
        /** 判定时前后台状态。 */ boolean foreground;
        /** 本次 cgroup 文本。 */ String cgroup = "";
        /** 有其他捕获重叠时不再关联系统说明。 */ boolean ambiguous;
    }


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
        hasInit = false;
        hasInstance = false;
        sSignalAnrDetectedListener = null;
        sTraceFileListener = null;
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
    private static void confirmRealAnr(final Capture capture, final boolean isSigQuit) {
        MatrixLog.i(TAG, "confirmRealAnr, isSigQuit = " + isSigQuit);
        /** Matrix 消息阈值判断结果。 */
        boolean needReport = isMainThreadBlocked(capture);
        if (needReport) {
            report(capture, false, isSigQuit, null);
        } else {
            new Thread(new Runnable() {
                /** 执行 Matrix 原有后台采样或错误状态轮询。 */
                @Override
                public void run() {
                    checkErrorStateCycle(capture, isSigQuit);
                }
            }, CHECK_ANR_STATE_THREAD_NAME).start();
        }
    }

    /** 执行 Matrix 原有的主线程采样和等待。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Keep
    private static String onANRDumped() {
        /** 本次采集的不可变身份及独立判定数据。 */
        final Capture capture = new Capture();
        registerCapture(capture);
        /** 等待 Matrix Java 采样的同步门闩。 */
        final CountDownLatch anrDumpLatch = new CountDownLatch(1);
        new Thread(new Runnable() {
            /** 执行 Matrix 原有后台采样或错误状态轮询。 */
            @Override
            public void run() {
                try {
                    capture.stack = Utils.getMainThreadJavaStackTrace();
                    capture.cgroup = readCgroup();
                    capture.foreground = AppForegroundUtil.isInterestingToUser();
                    currentForeground = capture.foreground;
                    confirmRealAnr(capture, true);
                    MatrixLog.i(TAG, "ANR Java 采集判定已发起");
                } finally {
                    anrDumpLatch.countDown();
                }
            }
        }, ANR_DUMP_THREAD_NAME).start();

        try {
            anrDumpLatch.await(anrReportTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return capture.id;
    }

    /** 保留有限判定窗口并标记重叠采集，不能猜测系统说明的归属。 */
    private static synchronized void registerCapture(Capture capture) {
        /** 遍历既有采集并清理已经结束的关联窗口。 */
        var iterator = activeCaptures.values().iterator();
        while (iterator.hasNext()) {
            if (capture.startedElapsed - iterator.next().startedElapsed > ANR_DUMP_MAX_TIME) iterator.remove();
        }
        if (!activeCaptures.isEmpty()) {
            capture.ambiguous = true;
            for (Capture previous : activeCaptures.values()) previous.ambiguous = true;
        }
        if (activeCaptures.size() >= 64) activeCaptures.clear();
        activeCaptures.put(capture.id, capture);
    }

    /** 系统说明只在本次捕获未与其他信号重叠时可关联。 */
    public static synchronized boolean isCaptureUnambiguous(String captureId) {
        Capture capture = activeCaptures.get(captureId);
        return capture != null && !capture.ambiguous && SystemClock.elapsedRealtime() - capture.startedElapsed <= ANR_DUMP_MAX_TIME;
    }

    /** 查询本次 trace 所属的采集，不能把迟到结果交给新会话。 */
    private static synchronized Capture findCapture(String captureId) {
        return activeCaptures.get(captureId);
    }

    /** 按 Matrix 原解析器处理已截取 trace。 */
    @Keep
    private static void onANRDumpTrace(String captureId, String path, boolean truncated) {
        /** 根据采集身份固定旧会话监听器。 */
        Capture capture = findCapture(captureId);
        SignalAnrDetectedListener listener = capture == null ? null : capture.listener;
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(path)), "UTF-8"))) {
                /** 当前读取的原始文本行。 */
                String line;
                /** Matrix 原有死锁解析器。 */
                SimpleDeadLockDetector detector = new SimpleDeadLockDetector();
                while ((line = reader.readLine()) != null) {
                    detector.parseLine(line);
                }
                if (listener != null) {
                    if (detector.hasDeadLock()) {
                        listener.onDeadLockAnrDetected(
                                detector.getMainThreadInfo(), detector.getLockHeldThread1Info(),
                                detector.getLockHeldThread2Info(), detector.getWaitingThreadsInfo());
                    } else if (detector.getMainThreadInfo().contains("android.os.MessageQueue.nativePollOnce")) {
                        listener.onMainThreadStuckAtNativePollOnce(detector.getMainThreadInfo());
                    }
                }
            } catch (Throwable t) {
                MatrixLog.e(TAG, "trace 解析失败：%s", t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            MatrixLog.e(TAG, "trace 回调失败：%s", t.getClass().getSimpleName());
        }
        if (capture != null && capture.traceListener != null) {
            capture.traceListener.onAnrTraceFile(captureId, path, truncated);
        } else if (!new File(path).delete()) {
            MatrixLog.e(TAG, "未消费的 ANR trace 清理失败");
        }
    }

    /** 处理 Matrix 的手动 trace 完成回调。 */
    @Keep
    private static void onPrintTrace(String path, boolean truncated) {
        try {
            MatrixLog.i(TAG, "手动 trace 回调完成");
        } catch (Throwable t) {
            MatrixLog.e(TAG, "onPrintTrace error: %s", t.getMessage());
        }
        if (sTraceFileListener != null) {
            sTraceFileListener.onPrintTraceFile(path, truncated);
        } else if (!new File(path).delete()) {
            MatrixLog.e(TAG, "未消费的手动 trace 清理失败");
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
        Capture capture = new Capture();
        capture.stack = Utils.getMainThreadJavaStackTrace();
        capture.foreground = currentForeground;
        confirmRealAnr(capture, false);
    }

    /** 向适配器转发 Matrix 实际发生的原始回调。 */
    private static void report(Capture capture, boolean fromProcessErrorState, boolean isSigQuit,
                               ActivityManager.ProcessErrorStateInfo error) {
        try {
            if (capture.listener != null) {
                if (isSigQuit) {
                    String info = error != null && isCaptureUnambiguous(capture.id) ? error.longMsg : null;
                    capture.listener.onAnrDetected(capture.id, capture.occurredAt, capture.stack, capture.message,
                            capture.messageWhen, capture.foreground, fromProcessErrorState, capture.cgroup, info);
                } else {
                    capture.listener.onNativeBacktraceDetected(capture.stack, capture.message,
                            capture.messageWhen == null ? 0L : capture.messageWhen, fromProcessErrorState);
                }
                return;
            }

        } finally {
            lastReportedTimeStamp = System.currentTimeMillis();
        }
    }


    /** 按 Matrix 原消息时间阈值判断主线程。 */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private static boolean isMainThreadBlocked(Capture capture) {
        try {
            /** 主线程消息队列。 */
            MessageQueue mainQueue = Looper.getMainLooper().getQueue();
            /** Matrix 通过反射读取的队列字段。 */
            Field field = mainQueue.getClass().getDeclaredField("mMessages");
            field.setAccessible(true);
            /** 主线程消息队列中的首条消息。 */
            final Message mMessage = (Message) field.get(mainQueue);
            if (mMessage != null) {
                capture.message = mMessage.toString();
                /** 消息预定执行时间。 */
                long when = mMessage.getWhen();
                if (when == 0) {
                    return false;
                }
                /** 消息预定时间与当前运行时间的差值。 */
                long time = when - SystemClock.uptimeMillis();
                capture.messageWhen = time;
                return MatrixAnrDecision.messageBlocked(time, capture.foreground);
            } else {
                MatrixLog.i(TAG, "mMessage is null");
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }


    /** 按 Matrix 原次数和间隔轮询进程错误状态。 */
    private static void checkErrorStateCycle(Capture capture, boolean isSigQuit) {
        /** 保持最多 40 次、每次间隔 500ms 的原轮询。 */
        ActivityManager.ProcessErrorStateInfo match = MatrixAnrDecision.poll(SignalAnrTracer::checkErrorState, Thread::sleep);
        if (match != null) {
            MatrixLog.i(TAG, "系统进程错误状态已确认");
            report(capture, true, isSigQuit, match);
        } else {
            MatrixLog.i(TAG, "系统进程错误状态未命中");
        }
    }

    /** 按 Matrix 原 UID 和进程判断检查系统错误状态。 */
    private static ActivityManager.ProcessErrorStateInfo checkErrorState() {
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
                return null;
            }

            for (ActivityManager.ProcessErrorStateInfo proc : procs) {

                if (proc.uid != android.os.Process.myUid()
                        && proc.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                    MatrixLog.i(TAG, "maybe received other apps ANR signal");
                    return null;
                }

                if (proc.pid != android.os.Process.myPid() || proc.uid != android.os.Process.myUid()) continue;

                if (proc.condition != ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                    continue;
                }


                return proc;
            }
            return null;
        } catch (Throwable t) {
            MatrixLog.e(TAG, "进程错误状态查询失败：%s", t.getClass().getSimpleName());
        }
        return null;
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

    /** 旧信号或 trace 未结束时不能把其结果交给新会话。 */
    public static boolean canRestart() {
        return nativeCanRestart();
    }

    /** 查询 Native 信号和 trace 工作是否全部完成。 */
    private static native boolean nativeCanRestart();

    /** 由 Matrix 原生代码发出手动 SIGQUIT。 */
    private static native void nativePrintTrace();

    public interface SignalAnrDetectedListener {
        /** 接收 Matrix 的 SIGQUIT ANR 判定结果。 */
        void onAnrDetected(String captureId, long occurredAt, String stackTrace, String message, Long messageWhen,
                           boolean foreground, boolean fromProcessErrorState, String cgroup, String anrInfo);

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
        void onAnrTraceFile(String captureId, String path, boolean truncated);

        /** 手动 trace 已触发 Matrix 回调。 */
        void onPrintTraceFile(String path, boolean truncated);
    }
}
