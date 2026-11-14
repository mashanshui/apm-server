/** 内联到外层函数，供真实 DWARF 还原验证调用链顺序。 */
static __attribute__((always_inline)) inline int apm_fixture_inner(int value) {
    /** 通过易失变量保留独立指令和内联位置，避免跨架构优化合并整条调用链。 */
    volatile int factor = 3;
    return value * factor + 1;
}

/** 导出物理帧，同时保留可被优化器内联的内部调用。 */
__attribute__((noinline)) int apm_fixture_inline(int value) {
    return apm_fixture_inner(value) + 7;
}
