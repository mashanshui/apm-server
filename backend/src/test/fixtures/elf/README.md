# ELF 测试夹具

夹具源码不包含业务代码。生成步骤需要本机 Android NDK；只在临时目录输出 SO，不要把生成文件提交到仓库。

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358"
fixture_dir="$(mktemp -d)"
bash backend/src/test/fixtures/elf/generate.sh "$fixture_dir"
```

脚本生成 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四种 ABI，保留函数符号、调试信息和 GNU Build ID，并额外生成剥离版本及 `llvm-readelf` 的 Build ID 对照文件。可用 NDK 的 `llvm-readelf -h -n -S -s` 检查 ELF 类型、机器、Build ID、`.symtab`、`.debug_info`、`.debug_line` 及 `apm_fixture_add`；具体输出依 NDK 版本变化。运行期不会依赖 NDK 或执行这些 SO。使用完后删除临时目录。

运行解析器测试时显式提供夹具目录；未设置时四 ABI 及损坏夹具测试会跳过，有界读取测试仍会执行：

```bash
APM_ELF_FIXTURES="$fixture_dir" bash backend/gradlew -p backend test --tests com.shanshui.apmserver.symbol.internal.elf.ElfInspectorTests --tests com.shanshui.apmserver.symbol.internal.elf.ElfFileReaderTests
```
