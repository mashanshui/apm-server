"""读取固定 Skill 配置；只向用户返回字段状态和稳定错误，不输出秘密。"""

import json
import os
import re
import stat
from dataclasses import dataclass, field
from pathlib import Path

from apm_agent_worker.platform import WorkerError, base_url

# 配置仅位于本运行时所属 Skill，不根据源码、堆栈或当前目录寻找。
CONFIG_PATH = Path(__file__).resolve().parents[3] / "config.local.json"
# 两个必需字段，无项目路径、模型或命令配置。
FIELDS = ("platformUrl", "workerCredential")
# 配置很小，拒绝无界读取或误选大型材料。
MAX_CONFIG_BYTES = 8192


@dataclass(frozen=True)
class Configuration:
    """仅在 Python 内存传递凭据，避免对象诊断显示秘密。"""

    # 已校验的平台根地址。
    url: str
    # 凭据不进入对象 repr 或 CLI 参数。
    credential: str = field(repr=False)
    # 诊断只显示文件或环境来源。
    source: str = "file"


class ConfigurationError(RuntimeError):
    """只携带公开诊断；不保存 JSON 内容、原始异常或凭据。"""

    def __init__(self, code: str, message: str, *, missing: list[str] | None = None,
                 source: str = "file", configured: tuple[bool, bool] = (False, False)) -> None:
        """构造可直接输出的配置提示，不要求用户在聊天中填写秘密。"""
        super().__init__(code)
        # 固定路径方便用户找到本地编辑位置。
        self.diagnostic = {"status": "failed", "code": code, "message": message,
                           "configPath": str(CONFIG_PATH), "configSource": source,
                           "platform_url_configured": configured[0],
                           "worker_credential_configured": configured[1]}
        if missing:
            self.diagnostic["missingFields"] = missing


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    """重复字段拒绝，避免编辑者与解析器对实际凭据来源理解不同。"""
    # 收集键值但不把它放入异常。
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate field")
        value[key] = item
    return value


def file_values() -> dict[str, str]:
    """有界读取普通配置文件，拒绝链接、特殊文件及宽松凭据权限。"""
    # 最后一段不得是链接；非阻塞模式避免 FIFO 等特殊文件阻塞。
    descriptor = None
    try:
        descriptor = os.open(CONFIG_PATH, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        # 打开的同一文件描述符用于权限与内容校验，避免检查后替换路径。
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid():
            raise ConfigurationError("CONFIG_FILE_UNSAFE", "配置必须是当前用户拥有的普通文件，不能是链接或特殊文件。")
        if metadata.st_size > MAX_CONFIG_BYTES:
            raise ConfigurationError("CONFIG_INVALID", "配置超过 8 KiB，请只保留 platformUrl 和 workerCredential。")
        # 同样对读取量设限，文件增长不能绕过初始大小检查。
        raw = os.read(descriptor, MAX_CONFIG_BYTES + 1)
        if len(raw) > MAX_CONFIG_BYTES:
            raise ValueError("oversized configuration")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
        if not isinstance(value, dict) or set(value) - set(FIELDS) \
                or any(not isinstance(item, str) for item in value.values()):
            raise ValueError("unsupported configuration")
        if value.get("workerCredential", "").strip() and metadata.st_mode & 0o077:
            raise ConfigurationError("CONFIG_PERMISSIONS", "配置含凭据，须仅当前用户可访问；请在本地将此文件权限设为 600。")
        return value
    except FileNotFoundError:
        return {}  # 缺失与空模板均引导用户配置，不创建任务。
    except ConfigurationError:
        raise
    except (OSError, ValueError, UnicodeError, RecursionError):
        raise ConfigurationError("CONFIG_INVALID", "无法读取配置：请检查文件是否为普通文件及合法 UTF-8 JSON，且只包含两个字符串字段。") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def load_configuration() -> Configuration:
    """先校验完整配置，再允许任何网络、任务领取或源码采集。"""
    # 环境覆盖必须成对提供，不把旧地址和另一份凭据混合。
    environment = (os.environ.get("APM_ANALYSIS_URL", "").strip(),
                   os.environ.get("APM_WORKER_CREDENTIAL", "").strip())
    source = "environment" if any(environment) else "file"
    values = dict(zip(FIELDS, environment)) if any(environment) else file_values()
    # 删除编辑时附带的首尾空白，内容不写入日志或状态。
    url, credential = (values.get(key, "").strip() for key in FIELDS)
    missing = [key for key, item in zip(FIELDS, (url, credential)) if not item]
    if missing:
        message = "请在本地配置文件中填写缺少字段，Worker 凭据请从应用设置创建，不要发送到聊天。"
        if source == "environment":
            message = "环境变量须同时提供 APM_ANALYSIS_URL 和 APM_WORKER_CREDENTIAL；或清除两项后使用本地配置文件。"
        raise ConfigurationError("CONFIG_MISSING", message, missing=missing, source=source,
                                 configured=(bool(url), bool(credential)))
    try:
        # 延续现有平台根地址规则，额外校验端口及控制字符。
        from urllib.parse import urlsplit
        if len(url) > 2048 or any(ord(character) < 32 for character in url):
            raise ValueError("invalid url")
        normalized = base_url(url)
        urlsplit(normalized).port
    except (ValueError, WorkerError):
        raise ConfigurationError("PLATFORM_URL_INVALID", "platformUrl 必须是 HTTP/HTTPS 平台根地址，不能含账号、查询参数或额外路由。", source=source) from None
    if not re.fullmatch(r"apm_aw_[A-Za-z0-9_-]{43}", credential):
        raise ConfigurationError("WORKER_CREDENTIAL_MISSING", "workerCredential 格式无效，请使用应用设置创建的完整 Worker 凭据。", source=source)
    return Configuration(normalized, credential, source)
