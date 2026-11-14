#!/bin/sh
# Linux 子进程必须限制地址空间和 CPU 时间；启动失败直接返回非零。
set -eu
# 受控目录必须显式配置；沙箱初始化失败会以 125 退出，不启动无隔离还原。
: "${APM_SYMBOL_DIRECTORY:?缺少受控符号目录}"
export APM_SYMBOL_DIRECTORY
unset LD_PRELOAD
exec /usr/bin/prlimit --as=536870912 --cpu=3 -- /usr/lib/llvm-19/bin/llvm-symbolizer "$@"
