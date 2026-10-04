"""宿主直接分析报告；只做提交时匹配，不证明读取或修改过程。"""

import hashlib
import json
from pathlib import Path
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from apm_agent_worker.platform import WorkerError
from apm_agent_worker.redaction import redact
from apm_agent_worker.source import excluded_path, current_snippet, SourceBlocked
import re


# 所有未知项与建议均为有界普通文本，显示层仍需安全转义。
SmallText = Annotated[str, StringConstraints(min_length=1, max_length=1000)]


class StrictModel(BaseModel):
    """拒绝额外字段和隐式数字/布尔转换。"""
    model_config = ConfigDict(extra="forbid", strict=True)


class Candidate(StrictModel):
    """多个候选仍必须引用当前单事件证据。"""
    # 候选的有界标题。
    title: str = Field(min_length=1, max_length=500)
    # 推断和未知边界。
    reason: str = Field(min_length=1, max_length=4000)
    # 当前快照中的片段 ID。
    evidenceRefs: list[Annotated[str, StringConstraints(min_length=1, max_length=64)]] = Field(min_length=1, max_length=20)


class RequestedSource(StrictModel):
    """宿主提供相对位置和实际使用片段，匹配状态由 Python 生成。"""
    # 用户明确项目内的相对路径。
    path: str = Field(min_length=1, max_length=512)
    # 一起始行。
    startLine: int = Field(ge=1)
    # 包含的结束行，跨度另由文件核验校验。
    endLine: int = Field(ge=1, le=1000000)
    # 宿主实际使用的片段，Python 不伪称来自已冻结源码。
    snippet: str = Field(max_length=8192)


class VerifiedCommand(StrictModel):
    """宿主报告的实际命令与退出码，工具负责限量脱敏。"""
    command: str = Field(min_length=1,max_length=1000)  # 已执行命令。
    exitCode: int  # 实际返回值，不能填 null 冒充执行。
    summary: str = Field(max_length=2000)  # 不接收完整原始日志。


class HostVerification(StrictModel):
    """输入仅含宿主命令事实，工具添加自报标记。"""
    status: Literal['PASSED','FAILED','NOT_RUN']  # 命令总体状态。
    commands: list[VerifiedCommand] = Field(max_length=10)  # 有限命令。
    reason: str = Field(max_length=2000)  # 未执行或失败原因。


class HostEdit(StrictModel):
    """修改事实由宿主报告，不携带无法核验的写前/写后摘要。"""
    path: str = Field(min_length=1, max_length=512)  # 当前项目相对文件。
    summary: str = Field(max_length=1000)  # 实际修改的简短说明。


class HostRepair(StrictModel):
    """宿主描述修改结果；Python 不执行或独立验证补丁。"""
    status: Literal['NOT_REQUESTED', 'NOT_APPLICABLE', 'APPLIED', 'PARTIAL', 'CONFLICT', 'FAILED']  # 修改状态。
    files: list[HostEdit] = Field(max_length=20)  # 有界回传，非宿主修改能力上限。
    reason: str = Field(max_length=2000)  # 未修改/失败等必要说明。


class ModelAnalysis(StrictModel):
    """宿主分析、引用、修改和执行自报；工具填充来源和引用核对。"""
    # 宿主直接读取协议版本。
    schemaVersion: int = Field(strict=True, ge=4, le=4)
    # 必须属于当前任务的证据 UUID。
    evidenceId: UUID
    # 服务端当前分配，不能拿其他 Run 的结果提交。
    runId: UUID
    # 证据不足可以是正常业务结论。
    conclusion: Literal["ROOT_CAUSE_CANDIDATE", "INSUFFICIENT_EVIDENCE"]
    # 人可读摘要，不宣称修复成功。
    summary: str = Field(min_length=1, max_length=4000)
    # 根因候选最多五个。
    candidates: list[Candidate] = Field(max_length=5)
    # 待受信包装器核验的引用最多二十个。
    sourceRefs: list[RequestedSource] = Field(max_length=20)
    # 不能确定的事项。
    unknowns: list[SmallText] = Field(max_length=20)
    # 风险说明。
    risks: list[SmallText] = Field(max_length=20)
    # 未执行的修复建议。
    fixSuggestions: list[SmallText] = Field(max_length=20)
    # 未执行的验证步骤。
    validationSuggestions: list[SmallText] = Field(max_length=20)
    # 可选实际命令自报，不能填充 execution 或计量事实。
    verification: HostVerification | None = None
    # 实际修改由宿主自报，首次 prepare 必须明确授权。
    repair: HostRepair | None = None



def safe_path(value: str) -> None:
    """只接受可公开的普通项目相对路径，不能借报告回传秘密文件。"""
    try:
        if excluded_path(value):
            raise SourceBlocked('SOURCE_PATH_INVALID')
    except SourceBlocked:
        raise WorkerError('REFERENCE_INVALID') from None


def public_text(value: str) -> str:
    """脱敏明确凭据与本机绝对路径，不能识别所有未命名秘密。"""
    return re.sub(r'(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\/]|/)(?:[^\s"<>]+)', '[LOCAL_PATH]', redact(value))


def validated(text: str, evidence: dict[str, Any], project: Path, run_id: str, repair_authorized: bool = False) -> dict[str, Any]:
    """绑定事件与 Run；片段仅提交时检查，修改和验证均明确宿主自报。"""
    try:
        result = ModelAnalysis.model_validate_json(text)
    except ValidationError:
        raise WorkerError('FORMAT_INVALID') from None
    if str(result.evidenceId) != evidence['evidenceId'] or str(result.runId) != run_id:
        raise WorkerError('REFERENCE_INVALID')
    fragments = {item['id'] for item in evidence['fragments']}  # 服务端冻结的真实片段集合。
    if any(not set(item.evidenceRefs) <= fragments for item in result.candidates):
        raise WorkerError('REFERENCE_INVALID')
    if (result.conclusion == 'ROOT_CAUSE_CANDIDATE' and not result.candidates) or (result.conclusion == 'INSUFFICIENT_EVIDENCE' and not result.unknowns):
        raise WorkerError('REFERENCE_INVALID')
    output = result.model_dump(mode='json')  # 固定 ID 及语义由严格 Schema 校验。
    references = []  # 宿主片段与提交时位置检查分别保存。
    for ref in result.sourceRefs:
        safe_path(ref.path)
        if ref.endLine < ref.startLine or ref.endLine - ref.startLine >= 200 or len(ref.snippet.encode()) > 8192 or len(ref.snippet.split('\n')) != ref.endLine - ref.startLine + 1:
            raise WorkerError('REFERENCE_INVALID')
        snippet = redact(ref.snippet)  # 原片段不进入公开诊断，显示只使用脱敏文本。
        current = current_snippet(project, ref.path, ref.startLine, ref.endLine)  # 只检查当前引用范围。
        check = 'UNAVAILABLE' if current is None else ('CURRENT_MATCH' if redact(current) == snippet else 'CURRENT_DIFFERENT')
        references.append({**ref.model_dump(), 'snippet': snippet, 'snippetSha256': hashlib.sha256(snippet.encode()).hexdigest(),
                           'metadataSource': 'HOST_REPORTED', 'currentCheck': check})
    output['sourceRefs'] = references
    repair = result.repair or HostRepair(status='NOT_APPLICABLE' if repair_authorized else 'NOT_REQUESTED', files=[], reason='宿主未报告修改' if repair_authorized else '仅分析')
    if not repair_authorized and (repair.status != 'NOT_REQUESTED' or repair.files):
        raise WorkerError('REPAIR_NOT_AUTHORIZED')
    if len({item.path for item in repair.files}) != len(repair.files) or (repair.status in {'NOT_REQUESTED', 'NOT_APPLICABLE'} and repair.files) or (repair.status in {'APPLIED', 'PARTIAL'} and not repair.files):
        raise WorkerError('FORMAT_INVALID')
    for item in repair.files:
        safe_path(item.path)
    output['repair'] = {**repair.model_dump(), 'reason': public_text(repair.reason), 'metadataSource': 'HOST_REPORTED',
                        'files': [{'path': item.path, 'summary': public_text(item.summary)} for item in repair.files]}
    verification = result.verification or HostVerification(status='NOT_RUN', commands=[], reason='宿主未报告执行验证')
    if (verification.status == 'NOT_RUN' and (verification.commands or not verification.reason.strip())) or (verification.status == 'PASSED' and (not verification.commands or any(item.exitCode != 0 for item in verification.commands))) or (verification.status == 'FAILED' and not any(item.exitCode != 0 for item in verification.commands)):
        raise WorkerError('FORMAT_INVALID')
    output['verification'] = {**verification.model_dump(), 'metadataSource': 'HOST_REPORTED', 'reason': public_text(verification.reason),
                              'commands': [{'command': public_text(item.command), 'exitCode': item.exitCode, 'summary': public_text(item.summary)} for item in verification.commands]}
    # 所有自由文本递归脱敏，结构标识和源码片段保留各自语义。
    def clean(value):
        """仅清理语义文本，不能重新解释宿主授权或执行命令。"""
        if isinstance(value, str):
            return public_text(value)
        if isinstance(value, list):
            return [clean(item) for item in value]
        if isinstance(value, dict):
            return {key: clean(item) for key, item in value.items()}
        return value
    for key in ('summary', 'candidates', 'unknowns', 'risks', 'fixSuggestions', 'validationSuggestions'):
        output[key] = clean(output[key])
    return output
