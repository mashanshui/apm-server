"""只定位当前项目并核对提交引用，不遍历或复制全量源码。"""

import os
import stat
import subprocess
import threading
import time
from pathlib import Path, PurePosixPath


class SourceBlocked(ValueError):
    """源码前提不满足，仅返回稳定码和无内容的资源计量。"""

    def __init__(self, code: str, *, resource: str | None = None,
                 observed: int | None = None, limit: int | None = None):
        """超限诊断不携带文件名、内容、Git 输出或环境配置。"""
        super().__init__(code)
        self.diagnostic = {}  # 普通前提错误保持原有返回。
        if resource is not None:
            self.diagnostic = {"resource": resource, "observedAtLeast": observed, "limit": limit}


def git(repository: Path, arguments: list[str], limit: int = 4 * 1024 * 1024, deadline: float | None = None) -> bytes:
    """仅运行可信只读 Git 子命令，禁用用户全局配置、钩子和交互凭据。"""
    remaining = 30 if deadline is None else min(30, deadline - time.monotonic())  # 每条命令复用整体剩余预算。
    if remaining <= 0:
        raise SourceBlocked("SOURCE_READ_TIMEOUT")
    # Git 不获得模型和平台凭据，不通过 shell 拼接命令。
    environment = {key: value for key, value in os.environ.items()
                   if key in {"PATH", "SYSTEMROOT", "TMPDIR", "LANG"}}
    environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                       GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    # 所有 caller 参数在本模块固定；不执行项目脚本、过滤器或网络 fetch。
    with subprocess.Popen(["git", "--no-optional-locks", "-c", "core.hooksPath=" + os.devnull,
                           "-c", "core.fsmonitor=false", "-C", str(repository), *arguments],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment) as process:
        # 读取期间也实施期限，不能只为读取结束后的 wait 设置超时。
        expired = threading.Event()
        # 子进程超时后关闭管道，防止阻塞在 stdout.read。
        def stop() -> None:
            """独立终止可信 Git 读取，不留下后台进程。"""
            expired.set()
            if process.poll() is None:
                process.kill()
        # 每条只读命令最多三十秒。
        watchdog = threading.Timer(remaining, stop)
        watchdog.start()
        try:
            assert process.stdout is not None
            # 只缓存最大 limit+1 个字节，不使用无界 communicate。
            output = process.stdout.read(limit + 1)
            if len(output) > limit:
                raise SourceBlocked("SOURCE_TOO_LARGE", resource="gitOutputBytes", observed=len(output), limit=limit)
            # watchdog 覆盖读取和退出两个阶段。
            code = process.wait()
            if expired.is_set():
                raise SourceBlocked("SOURCE_READ_TIMEOUT")
            if code:
                raise SourceBlocked("SOURCE_MISSING")
            return output
        finally:
            watchdog.cancel()
            if process.poll() is None:
                process.kill()
                process.wait()


# 显式排除工具自身、构建环境和秘密；不能依赖用户恰好写了忽略规则。
EXCLUDED_PARTS = {".git", ".agents", ".codex", ".venv", "venv", "__pycache__", "node_modules", "build", "dist", "target", ".gradle", ".idea", "agent-skills", "agent-worker"}
# 不允许修改或外发签名与配置秘密文件。
SECRET_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore"}
# 明确的二进制产物不属于可回传的文本引用；不限制宿主仓库附件容量。
BINARY_SUFFIXES = {".aar", ".jar", ".apk", ".aab", ".dex", ".class", ".so", ".dll", ".dylib",
                   ".o", ".a", ".hprof", ".zip", ".gz", ".7z", ".rar", ".tar", ".bz2", ".xz",
                   ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".mp4", ".mp3",
                   ".woff", ".woff2", ".ttf", ".otf"}


def relative_path(value: str) -> PurePosixPath:
    """相对路径白名单，不解引用链接或规范化危险输入。"""
    if not value or value.startswith("/") or "\\" in value or ":" in value or "\0" in value \
            or any(part in {"", ".", ".."} for part in value.split("/")):
        raise SourceBlocked("SOURCE_PATH_INVALID")
    return PurePosixPath(value)


def excluded_path(value: str) -> bool:
    """报告路径排除规则，不代表能够限制宿主自身读写。"""
    path = relative_path(value)  # 已核验的普通相对路径。
    return any(part in EXCLUDED_PARTS for part in path.parts) or path.suffix.lower() in SECRET_SUFFIXES | BINARY_SUFFIXES \
        or path.name.startswith(".env") or path.name.lower() in {"local.properties", "credentials.json", "repositories.json", "config.local.json"}


def project_root(project: Path | None = None) -> Path:
    """默认当前目录，显式项目必须是绝对路径；不读取提交或远端。"""
    selected = Path.cwd() if project is None else project  # 宿主明确选定的起点。
    if not selected.is_absolute():
        raise SourceBlocked("PROJECT_PATH_INVALID")
    try:
        if not selected.is_dir():
            raise SourceBlocked("PROJECT_NOT_FOUND")
        root = Path(git(selected, ["rev-parse", "--show-toplevel"]).decode("utf-8").strip())  # Git 仅定位根目录。
        if root.is_symlink() or not root.is_dir():
            raise SourceBlocked("SOURCE_PATH_INVALID")
        return root
    except (OSError, UnicodeError, SourceBlocked) as failure:
        if isinstance(failure, SourceBlocked) and str(failure) != "SOURCE_MISSING":
            raise
        raise SourceBlocked("PROJECT_NOT_FOUND") from None


def current_snippet(project: Path, name: str, start: int, end: int) -> str | None:
    """只读取引用位置，链接/特殊文件/扫描超限或变动时保留不可核验。"""
    relative = relative_path(name)  # 已核验的相对位置，不接受宿主覆盖项目根。
    descriptor = None  # 当前目录描述符，始终关闭。
    file_descriptor = None  # 引用文件只读打开，不能触发 FIFO。
    try:
        descriptor = os.open(project, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in relative.parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        file_descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        before = os.fstat(file_descriptor)  # 仅核对本次检查期间的文件身份。
        if not stat.S_ISREG(before.st_mode):
            return None
        scanned = 0  # 引用核对单次最多扫描八 MiB，不限制宿主自行读取。
        lines = []  # 只保留请求的最多二百行。
        selected_bytes = 0  # 引用检查不累计缓存大于八 KiB 的文本。
        with os.fdopen(file_descriptor, 'rb') as stream:
            file_descriptor = None
            for number in range(1, end + 1):
                line = stream.readline(8193)  # 单行也有上限，防止长行内存膨胀。
                scanned += len(line)
                if not line or len(line) > 8192 or scanned > 8 * 1024 * 1024:
                    return None
                if number >= start:
                    selected_bytes += len(line)
                    if selected_bytes > 8192:
                        return None
                    lines.append(line.rstrip(b'\n').rstrip(b'\r').decode('utf-8'))
            after = os.fstat(stream.fileno())  # 并发改动不能冒充匹配成功。
        if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            return None
        return '\n'.join(lines)
    except (OSError, UnicodeError):
        return None
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        if descriptor is not None:
            os.close(descriptor)
