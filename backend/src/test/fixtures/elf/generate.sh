#!/usr/bin/env bash
# 从 Android NDK 在临时目录生成四种 ABI 的测试文件，不将产物写入仓库。
set -euo pipefail

# NDK 路径由调用者显式提供，避免依赖本机 SDK 默认位置。
: "${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"
# 输出路径必须由调用者指定，避免意外覆盖仓库中的文件。
: "${1:?请传入空的临时输出目录}"
fixture_output=$1
fixture_source=$(cd "$(dirname "$0")" && pwd)/minimal.c
fixture_inline_source=$(cd "$(dirname "$0")" && pwd)/inline.c

# NDK 的预编译工具链按宿主平台命名。
case "$(uname -s)-$(uname -m)" in
  Darwin-*) fixture_host=darwin-x86_64 ;;
  Linux-x86_64) fixture_host=linux-x86_64 ;;
  *) printf '不支持的 NDK 宿主平台\n' >&2; exit 1 ;;
esac
fixture_tools="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$fixture_host/bin"
if [[ ! -d "$fixture_output" ]] || [[ -n "$(ls -A "$fixture_output")" ]]; then
  printf '输出目录必须已存在且为空\n' >&2
  exit 1
fi

# 每种 ABI 分别使用 NDK 对应目标编译器，保留符号、调试信息及 GNU Build ID。
for fixture_pair in \
  'armeabi-v7a:armv7a-linux-androideabi21' \
  'arm64-v8a:aarch64-linux-android21' \
  'x86:i686-linux-android21' \
  'x86_64:x86_64-linux-android21'; do
  fixture_abi=${fixture_pair%%:*}
  fixture_target=${fixture_pair#*:}
  mkdir -p "$fixture_output/$fixture_abi"
  "$fixture_tools/${fixture_target}-clang" -shared -fPIC -g -Wl,--build-id=sha1 \
    "$fixture_source" -o "$fixture_output/$fixture_abi/libapm_fixture.so"
  # 优化构建保留完整 DWARF 内联记录，单独提供仅函数符号的同 Build ID 对照。
  "$fixture_tools/${fixture_target}-clang" -shared -fPIC -O2 -g -Wl,--build-id=sha1 \
    "$fixture_inline_source" -o "$fixture_output/$fixture_abi/libapm_inline.so"
  "$fixture_tools/llvm-strip" --strip-debug -o "$fixture_output/$fixture_abi/libapm_function_only.so" \
    "$fixture_output/$fixture_abi/libapm_fixture.so"
  # 用相同内容的 .so.sym 验证后缀不影响 ELF 与 LLVM；仅函数版本用于生成模拟归档。
  cp "$fixture_output/$fixture_abi/libapm_function_only.so" "$fixture_output/$fixture_abi/libapm_fixture.so.sym"
  cp "$fixture_output/$fixture_abi/libapm_fixture.so" "$fixture_output/$fixture_abi/libapm_full.so.sym"
  # 与 AGP FULL 一致，只提取调试信息并保留原 ELF Build ID。
  "$fixture_tools/llvm-objcopy" --only-keep-debug "$fixture_output/$fixture_abi/libapm_fixture.so" \
    "$fixture_output/$fixture_abi/libapm_fixture.so.dbg"
  # 保留只有动态导出表的剥离版本，用于验证不能冒充完整符号。
  "$fixture_tools/llvm-strip" --strip-all -o "$fixture_output/$fixture_abi/libapm_stripped.so" \
    "$fixture_output/$fixture_abi/libapm_fixture.so"
  # 使用独立的 NDK 读取器记录完整 Build ID，供解析器测试对照。
  "$fixture_tools/llvm-readelf" -n "$fixture_output/$fixture_abi/libapm_fixture.so" \
    | sed -n 's/.*Build ID: //p' > "$fixture_output/$fixture_abi/build-id.txt"
  [[ -s "$fixture_output/$fixture_abi/build-id.txt" ]] || { printf '夹具缺少 Build ID\n' >&2; exit 1; }
  printf '已生成 %s ELF 夹具\n' "$fixture_abi"
done

# 将四 ABI 的测试 ELF 打包成与 AGP 目录结构一致的 ZIP；这不是 AGP 构建产物。
python3 - "$fixture_output" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
with zipfile.ZipFile(root / 'fixture-native-debug-symbols.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
        archive.write(root / abi / 'libapm_fixture.so.sym', f'{abi}/libapm_fixture.so.sym')
with zipfile.ZipFile(root / 'fixture-native-debug-symbols-full.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
        archive.write(root / abi / 'libapm_fixture.so.dbg', f'{abi}/libapm_fixture.so.dbg')
PY

# 额外生成 64 字节 Build ID，验证模块身份不截断。
fixture_long_id=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
"$fixture_tools/aarch64-linux-android21-clang" -shared -fPIC -g "-Wl,--build-id=0x$fixture_long_id" \
  "$fixture_source" -o "$fixture_output/arm64-v8a/libapm_long_id.so"
printf '%s\n' "$fixture_long_id" > "$fixture_output/arm64-v8a/long-build-id.txt"
printf '已生成完整长 Build ID 夹具\n'
