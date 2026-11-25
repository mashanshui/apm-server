#ifndef PERFORMANCE_ANR_TRACE_CAPTURE_GATE_H
#define PERFORMANCE_ANR_TRACE_CAPTURE_GATE_H

#include <cstdint>
#include <mutex>

/** 跟踪尚未观察到首次写入的转储，重叠时丢弃无法确认归属的片段。 */
class TraceCaptureGate {
public:
    /** 在工作线程准备转交 SIGQUIT 前登记一次捕获。 */
    uint64_t begin() {
        /** 序列号和待写计数必须一起更新。 */
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_ != 0) ambiguous_ = true;
        pending_++;
        active_ = ++sequence_;
        return active_;
    }

    /** 消费一次首次写入；仅唯一且未重叠的捕获允许关联。 */
    bool observe(uint64_t ticket) {
        /** 并发时先固定当前门禁状态。 */
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_ == 0) return false;
        /** 只有门禁无歧义且票据相同时才允许发布。 */
        bool matched = !ambiguous_ && pending_ == 1 && active_ == ticket;
        pending_--;
        if (pending_ == 0) ambiguous_ = false;
        return matched;
    }

    /** 仍有未观察到的转储时继续监听，避免迟到写入串到新捕获。 */
    bool hasPending() {
        /** 待观察状态与登记操作受同一锁保护。 */
        std::lock_guard<std::mutex> lock(mutex_);
        return pending_ != 0;
    }

    /** 会话关闭后允许重新采集，但旧写入尚未消化时不再发布不确定的归属。 */
    void abandonAssociations() {
        /** 与登记和写入消费使用相同互斥锁。 */
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_ != 0) ambiguous_ = true;
    }

private:
    /** 门禁状态只由普通工作线程和 hook 回调访问。 */
    std::mutex mutex_;
    /** 进程内单调递增的捕获票据。 */
    uint64_t sequence_ = 0;
    /** 最近登记的票据。 */
    uint64_t active_ = 0;
    /** 尚未观察到首次写入的数量。 */
    uint64_t pending_ = 0;
    /** 重叠捕获期间的写入不能关联到单个事件。 */
    bool ambiguous_ = false;
};

#endif
