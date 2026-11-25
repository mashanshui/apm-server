#include "../../main/cpp/TraceCaptureGate.h"

#include <cassert>

/** 验证唯一捕获、重叠写入和迟到写入都不串档。 */
int main() {
    /** 独立的捕获门禁。 */
    TraceCaptureGate gate;
    /** 第一次完整捕获可以安全关联。 */
    uint64_t first = gate.begin();
    assert(gate.observe(first));
    assert(!gate.hasPending());

    /** 两次重叠捕获的两个写入均不能关联。 */
    uint64_t second = gate.begin();
    uint64_t third = gate.begin();
    assert(!gate.observe(second));
    assert(gate.hasPending());
    assert(!gate.observe(third));
    assert(!gate.hasPending());

    /** 观察完所有冲突写入后可以恢复唯一捕获。 */
    uint64_t fourth = gate.begin();
    assert(gate.observe(fourth));

    /** 一次写入一直未到达时，后续写入仍不能冒充新捕获。 */
    uint64_t delayed = gate.begin();
    uint64_t newer = gate.begin();
    assert(!gate.observe(newer));
    assert(gate.hasPending());
    assert(!gate.observe(delayed));
    assert(!gate.hasPending());

    /** 关闭重开时旧写入即使迟到，也不能发布到新的监听器。 */
    uint64_t beforeRestart = gate.begin();
    gate.abandonAssociations();
    assert(!gate.observe(beforeRestart));
    assert(!gate.hasPending());
    /** 旧写入消费后新捕获可重新关联。 */
    uint64_t afterRestart = gate.begin();
    assert(gate.observe(afterRestart));

    /** 无法确定旧写入是否丢失时，新旧写入均不能被错误关联。 */
    uint64_t missing = gate.begin();
    gate.abandonAssociations();
    /** 与缺失写入重叠的新捕获。 */
    uint64_t next = gate.begin();
    assert(!gate.observe(next));
    assert(gate.hasPending());
    assert(!gate.observe(missing));
    assert(!gate.hasPending());
    return 0;
}
