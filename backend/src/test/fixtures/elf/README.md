# ELF 测试夹具

夹具源码不包含业务代码。生成步骤需要本机 Android NDK；只在临时目录输出 SO，不要把生成文件提交到仓库。

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
fixture_dir="$(mktemp -d)"
bash backend/src/test/fixtures/elf/generate.sh "$fixture_dir"
```

脚本生成 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四种 ABI，包含完整调试信息、内联调用、仅函数符号及剥离对照，并输出 `llvm-readelf` 的 Build ID。可用 NDK 的 `llvm-readelf -h -n -S -s` 检查 ELF 类型、机器、Build ID、`.symtab`、`.debug_info`、`.debug_line` 及 `apm_fixture_add`；具体输出依 NDK 版本变化。运行期不会依赖 NDK 或执行这些 SO。使用完后删除临时目录。

每个 ABI 同时生成仅函数符号的 `libapm_fixture.so.sym`、含调试信息的 `libapm_full.so.sym`，以及使用 NDK `llvm-objcopy --only-keep-debug` 生成的 `libapm_fixture.so.dbg`。前者打包成 `fixture-native-debug-symbols.zip`，后者打包成 `fixture-native-debug-symbols-full.zip`；这两份 ZIP 是测试脚本生成的四 ABI 合成材料，不是 AGP 构建产物。后缀相同不代表源码行号可用；实际能力由 ELF 和 LLVM 输出确定。

运行解析器测试时显式提供夹具目录；未设置时四 ABI 及损坏夹具测试会跳过，有界读取测试仍会执行：

```bash
APM_ELF_FIXTURES="$fixture_dir" bash backend/gradlew -p backend test --tests com.shanshui.apmserver.symbol.internal.elf.ElfInspectorTests --tests com.shanshui.apmserver.symbol.internal.elf.ElfFileReaderTests
```

真实 LLVM 专项使用已构建、带启动器和文件隔离的 Linux 后端镜像，核对四 ABI 的完整 Build ID、函数、行号、剥离对照和有序内联链，并验证 Java 调用、外部调试链接及隔离故障。必须显式提供夹具、NDK 和镜像；缺任何一项时对应 JUnit 用例明确跳过，不计作通过：

```bash
export APM_ELF_FIXTURES="$fixture_dir"
export APM_SYMBOLIZER_IMAGE="apm-native-symbolizer-full-test"
python3 backend/src/test/fixtures/elf/verify.py "$APM_ELF_FIXTURES" "$APM_SYMBOLIZER_IMAGE"
bash backend/gradlew -p backend test --tests '*NativeElfFixtureIntegrationTests'
```

四 ABI 结果来自合成 ELF，不代表设备采集与服务端的完整链路已验证。

真实 HTTP 后台测试默认使用上述合成 FULL ZIP，核对源码位置与首次成功固定；可选将基于同一四 ABI 夹具生成的 AGP `FULL` 原始 ZIP 指向 `APM_AGP_FULL_NATIVE_ZIP`。指定时测试先逐 ABI 校对包内 `libapm_fixture.so.dbg` 与本次生成的夹具字节，再直接上传原包；不提供时不声称验证了实际 AGP FULL 包。

```bash
export APM_AGP_FULL_NATIVE_ZIP="/path/to/native-debug-symbols.zip"
bash backend/gradlew -p backend test --tests '*VersionedSymbolsHttpTests.resolvesFullDebugZipWithSourceLines'
```

如有当前示例应用由 AGP 生成的原始 `native-debug-symbols.zip`，可显式提供它，额外核对每个实际 `.so.sym` 的 ELF 身份，并以 Linux LLVM 还原四 ABI 的 `apm_demo_segv` 实际地址。此专项要求包中含四 ABI 的示例崩溃库；其他应用的符号包不应假定有该函数或四种 ABI。真实 AGP ZIP、临时解压文件和设备数据均不得提交。未提供该变量时，AGP 归档测试跳过，不能把合成夹具结果写成实际产物验收。

```bash
export APM_AGP_NATIVE_ZIP="$PWD/android/app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"
APM_ELF_FIXTURES="$fixture_dir" bash backend/gradlew -p backend test \
  --tests '*NativeArchiveReaderTests' --tests '*NativeElfFixtureIntegrationTests'
```

工具检查只验证显式提供的文件和实际符号信息，不证明该文件与某台设备上报匹配；同构建对应关系仍以完整 Build ID 核对。

要运行原始 AGP ZIP、合成事件与真实双库的联合验收，还须提供同一构建、包含调试行信息的 arm64 未剥离示例库。测试先核对两份 ELF 的完整 Build ID，再通过真实 HTTP 上传并实际替换，最后验证首次成功固定。未提供任一环境变量时该测试跳过：

```bash
export APM_AGP_NATIVE_FULL_SO="/path/to/arm64-v8a/libapm_crash_demo.so"
bash backend/gradlew -p backend test --tests '*VersionedSymbolsHttpTests.resolvesSyntheticEventFromActualAgpZip'
```

测试在隔离 PostgreSQL、ClickHouse 与临时符号目录中运行，需同时满足前述 `APM_ELF_FIXTURES`、`APM_AGP_NATIVE_ZIP`、`ANDROID_NDK_HOME`、`APM_SYMBOLIZER_IMAGE` 和可用 Docker 条件；截图仅使用该合成事件。
