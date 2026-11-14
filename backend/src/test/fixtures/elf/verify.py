#!/usr/bin/env python3
"""使用真实 NDK 和 Linux LLVM 校验四 ABI 夹具，不保存设备材料。"""

import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


def command(args, stdin=None):
    """执行有限夹具命令并返回文本，失败时不将其当作通过。"""
    return subprocess.run(args, input=stdin, text=True, capture_output=True, check=True, timeout=30).stdout


def build_id(readelf, elf):
    """从 ELF note 读取完整 GNU Build ID。"""
    result = command([str(readelf), "-n", str(elf)])
    match = re.search(r"Build ID:\s*([0-9a-fA-F]+)", result)
    if not match:
        raise ValueError("夹具缺少完整 Build ID")
    return match.group(1).lower()


def symbol(nm, elf, name):
    """读取指定函数的地址和长度，用于确定真实指令范围。"""
    result = command([str(nm), "-S", "--defined-only", "--format=posix", str(elf)])
    for row in result.splitlines():
        fields = row.split()
        if len(fields) >= 4 and fields[0] == name:
            return int(fields[2], 16), int(fields[3], 16)
    raise ValueError("夹具缺少目标函数")


def symbolize(image, root, abi, name, addresses):
    """在非 root 隔离镜像中向 LLVM 标准输入批量发送真实地址。"""
    mount = f"{root}:/var/lib/apm/symbols:ro"
    arguments = ["docker", "run", "--rm", "-i", "-v", mount,
                 "-e", "APM_SYMBOL_DIRECTORY=/var/lib/apm/symbols",
                 "--entrypoint", "/usr/local/bin/apm-native-symbolizer", image,
                 "--output-style=JSON", "--inlines", "--demangle", "--no-debuginfod",
                 f"--obj=/var/lib/apm/symbols/{abi}/{name}"]
    output = command(arguments, "".join(f"0x{address:x}\n" for address in addresses))
    return [json.loads(line) for line in output.splitlines() if line.strip()]


def verify_external_debug_link(image, tools, source, address):
    """确认有效的外部调试链接不能穿过受控目录，且对照未隔离工具确实可读。"""
    with tempfile.TemporaryDirectory(prefix="apm-debuglink-") as temporary:
        parent = Path(temporary).resolve()
        allowed = parent / "allowed"
        allowed.mkdir()
        outside = parent / "external.debug"
        target = allowed / "target.so"
        shutil.copyfile(source / "libapm_fixture.so", outside)
        command([str(tools / "llvm-objcopy"), f"--add-gnu-debuglink={outside}",
                 str(source / "libapm_stripped.so"), str(target)])
        (allowed / outside.name).symlink_to(outside)
        host = command([str(tools / "llvm-symbolizer"), "--output-style=JSON", "--no-debuginfod",
                        f"--obj={target}", f"0x{address:x}"])
        if not any(item.get("Line", 0) > 0 for item in json.loads(host)[0]["Symbol"]):
            raise ValueError("外部调试链接对照无效，不能证明隔离效果")
        arguments = ["docker", "run", "--rm", "-v", f"{allowed}:/var/lib/apm/symbols:ro",
                     "-v", f"{outside}:{outside}:ro", "-e", "APM_SYMBOL_DIRECTORY=/var/lib/apm/symbols",
                     "--entrypoint", "/usr/local/bin/apm-native-symbolizer", image,
                     "--output-style=JSON", "--no-debuginfod", "--obj=/var/lib/apm/symbols/target.so", f"0x{address:x}"]
        isolated = json.loads(command(arguments))[0]["Symbol"]
        if any(item.get("Line", 0) > 0 for item in isolated):
            raise ValueError("LLVM 不应读取受控目录之外的调试文件")


def verify_agp_zip(image, tools, archive_path):
    """读取实际 AGP 产物，用其真实函数地址验证四 ABI 和 Linux LLVM。"""
    # 期望的 ABI 与 ELF 机器类型，测试不通过目录名推导文件身份。
    expected = {"armeabi-v7a": "ARM", "arm64-v8a": "AArch64", "x86": "Intel 80386", "x86_64": "X86-64"}
    with tempfile.TemporaryDirectory(prefix="apm-agp-symbols-") as temporary:
        # LLVM 的只读挂载根目录。
        root = Path(temporary).resolve()
        with zipfile.ZipFile(archive_path) as archive:
            for abi, machine in expected.items():
                # 仅取受控的示例库名称，不采用归档路径写入任意位置。
                name = "libapm_crash_demo.so.sym"
                entry = f"{abi}/{name}"
                if archive.getinfo(entry).file_size > 128 * 1024 * 1024:
                    raise ValueError(f"{abi} 实际 AGP 测试材料超过单文件预算")
                directory = root / abi
                directory.mkdir()
                target = directory / name
                target.write_bytes(archive.read(entry))
                # 核对真实 ELF 机器与 Build ID，再读取目标函数地址。
                header = command([str(tools / "llvm-readelf"), "-h", str(target)])
                if machine.lower() not in header.lower():
                    raise ValueError(f"{abi} 实际 ELF 机器类型不匹配")
                build_id(tools / "llvm-readelf", target)
                address, _ = symbol(tools / "llvm-nm", target, "apm_demo_segv")
                result = symbolize(image, root, abi, name, [address])[0]["Symbol"]
                if not any(item.get("FunctionName") == "apm_demo_segv" for item in result):
                    raise ValueError(f"{abi} AGP 符号包未还原预期函数")
                # 没有行号段时不可伪造源码行号。
                sections = command([str(tools / "llvm-readelf"), "-S", str(target)])
                if ".debug_line" not in sections and any(item.get("Line", 0) > 0 for item in result):
                    raise ValueError(f"{abi} 无行号材料时不应显示源码行号")
        print("实际 AGP 符号包：四 ABI 身份和函数已验证")


def main():
    """核对每个 ABI 的文件身份、函数、源码行和完整内联链。"""
    if len(sys.argv) not in (3, 4) or not os.environ.get("ANDROID_NDK_HOME"):
        raise SystemExit("用法：设置 ANDROID_NDK_HOME 后传入夹具目录、后端镜像名及可选 AGP ZIP")
    root = Path(sys.argv[1]).resolve(strict=True)
    image = sys.argv[2]
    host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    tools = Path(os.environ["ANDROID_NDK_HOME"]) / "toolchains/llvm/prebuilt" / host / "bin"
    readelf, nm = tools / "llvm-readelf", tools / "llvm-nm"
    for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
        directory = root / abi
        full = directory / "libapm_full.so.sym"
        stripped = directory / "libapm_stripped.so"
        function_only = directory / "libapm_fixture.so.sym"
        full_debug = directory / "libapm_fixture.so.dbg"
        inline = directory / "libapm_inline.so"
        identity = build_id(readelf, full)
        if identity != build_id(readelf, stripped) or identity != build_id(readelf, function_only) or identity != build_id(readelf, full_debug):
            raise ValueError(f"{abi} 对照文件的 Build ID 不一致")
        address, _ = symbol(nm, full, "apm_fixture_add")
        if abi == "arm64-v8a":
            verify_external_debug_link(image, tools, directory, address)
        full_result = symbolize(image, root, abi, full.name, [address])[0]["Symbol"]
        debug_result = symbolize(image, root, abi, full_debug.name, [address])[0]["Symbol"]
        only_result = symbolize(image, root, abi, function_only.name, [address])[0]["Symbol"]
        stripped_result = symbolize(image, root, abi, stripped.name, [address])[0]["Symbol"]
        if not any(item.get("FunctionName") == "apm_fixture_add" and item.get("Line", 0) > 0 for item in full_result):
            raise ValueError(f"{abi} 完整符号未取得预期函数和行号")
        if not any(item.get("FunctionName") == "apm_fixture_add" and item.get("Line", 0) > 0 for item in debug_result):
            raise ValueError(f"{abi} .so.dbg 未取得预期函数和行号")
        if not any(item.get("FunctionName") == "apm_fixture_add" and item.get("Line", 0) == 0 for item in only_result):
            raise ValueError(f"{abi} 仅函数符号未取得预期函数或错误地产生行号")
        if any(item.get("Line", 0) > 0 for item in stripped_result):
            raise ValueError(f"{abi} 剥离对照不应包含行号")
        start, size = symbol(nm, inline, "apm_fixture_inline")
        if size < 1 or size > 4096:
            raise ValueError(f"{abi} 内联夹具函数大小不合理")
        results = symbolize(image, root, abi, inline.name, range(start, start + size))
        if not any([item.get("FunctionName") for item in result["Symbol"]][:2]
                   == ["apm_fixture_inner", "apm_fixture_inline"] for result in results):
            raise ValueError(f"{abi} 未取得完整且有序的内联链")
        print(f"{abi}：Build ID、函数、行号、内联链和剥离对照通过")
    if len(sys.argv) == 4:
        verify_agp_zip(image, tools, Path(sys.argv[3]).resolve(strict=True))


if __name__ == "__main__":
    main()
