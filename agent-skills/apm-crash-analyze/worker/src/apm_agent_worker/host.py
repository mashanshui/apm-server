"""宿主直接读取/编辑代码；Python 只维护单任务、冻结证据和回传。"""

import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4

from apm_agent_worker.analysis import ModelAnalysis, validated, public_text
from apm_agent_worker.lease import LeaseGuard
from apm_agent_worker.platform import PlatformClient, WorkerError, base_url
from apm_agent_worker.source import project_root
from apm_agent_worker.state import RunState

# 新本地流程标识，不能把旧快照任务静默升级为宿主直接访问。
PROTOCOL = 'HOST_DIRECT_V4'
HostAnalysis = ModelAnalysis  # 宿主只填语义，不填来源、摘要或可核验事实。


def active(platform: PlatformClient, state: RunState, watch: bool = True, guard: LeaseGuard | None = None) -> dict:
    """本任务证据和回传动作重新核验租约，不控制宿主源码工具。"""
    if state.data.get("protocol") != PROTOCOL:
        raise WorkerError("LOCAL_PROTOCOL_MISMATCH")
    if state.data.get("phase") != "HOST_RUNNING" or not state.data.get("lease"):
        raise WorkerError("ANALYSIS_NOT_READY")
    # 前台材料动作不能代替已失联的后台看护继续运行。
    if watch:
        try:
            # 双时钟分歧防止系统暂停后继续使用原材料身份。
            elapsed = time.monotonic() - state.data["guardianSeen"]
            wall_elapsed = time.time() - state.data["guardianWall"]
            if not state.data.get("guardianStarted") or elapsed < 0 or elapsed > 45 or abs(wall_elapsed - elapsed) > 5:
                raise ProcessLookupError()
            os.kill(state.data["guardianPid"], 0)
        except (OSError, KeyError):
            raise WorkerError("LEASE_EXPIRED") from None
    # 请求开始时刻用于扣除网络延迟。
    sent = time.monotonic()
    # 每次续租都由服务端给出状态与数据库时间。
    run = platform.heartbeat(state.data["runId"], state.data["lease"])
    # 保守校验取消、到期以及本次请求耗时。
    guard = guard if guard is not None else LeaseGuard()
    guard.update(run, sent)
    if run.get("runId") != state.data.get("runId") or run.get("taskId") != state.data.get("taskId"):
        raise WorkerError("REFERENCE_INVALID")
    state.save(lastRun=run)
    return run


def close_tools(state: RunState) -> None:
    """关闭本任务证据/回传工具；不声称可以阻断宿主源码或模型工具。"""
    state.save(localToolsStopped=True)


def upload(platform: PlatformClient, state: RunState) -> dict:
    """使用原结果原摘要对账，绝不要求宿主再次分析。"""
    # 恢复时仍先关闭本任务工具，不把网络未知当成成功。
    close_tools(state)
    # 最多两次传输尝试，不创建新的 Run。
    for _ in range(2):
        try:
            # 模型停止未知，Worker 无权限自行确认。
            run = platform.request("POST", f'/api/worker/v1/tasks/runs/{state.data["runId"]}/complete', body={
                "lease": state.data["lease"], "resultJson": state.data["resultJson"],
                "resultSha256": state.data["resultSha256"], "stopConfirmed": False, "localToolsStopped": True}, limit=65536)
            state.save(phase="HOST_DONE", lease=None, resultJson=None, lastRun=run)
            return {"status": "succeeded", "taskId": state.data["taskId"], "runId": run["runId"],
                    "conclusion": state.data["conclusion"], "usage": state.data["usage"],
                    "repair": state.data.get("repairFacts"), "verification": state.data.get("verificationFacts"),
                    "localToolsStopped": True, "hostStopState": run["hostStopState"], "stopConfirmed": run["stopConfirmed"]}
        except WorkerError as failure:
            if str(failure) != "NETWORK_ERROR":
                # 明确拒绝不是未知完成，失败恢复仍不新推理。
                try:
                    stop(platform, state, "LEASE_EXPIRED" if str(failure) in {"LEASE_EXPIRED", "ANALYSIS_LEASE_INVALID"} else "EXECUTOR_FAILED")
                except Exception:
                    pass
                raise
    raise WorkerError("RESULT_PENDING")


def submit(platform: PlatformClient, state: RunState, text: str, host: str | None = None) -> dict:
    """引用仅提交时核对，修改和验证均宿主报告，不生成补丁日志。"""
    if state.data.get('protocol') != PROTOCOL:
        raise WorkerError('LOCAL_PROTOCOL_MISMATCH')
    if state.data.get('phase') == 'HOST_RESULT_PENDING':
        return upload(platform, state)
    active(platform, state)
    if len(text.encode()) > 1024 * 1024 or (host is not None and (not host.strip() or len(host) > 100)):
        raise WorkerError('FORMAT_INVALID')
    output = validated(text, state.data['evidence'], Path(state.data['projectRoot']), state.data['runId'], state.data['repairAuthorized'])
    output['execution'] = {'mode': 'HOST_AGENT', 'host': public_text(host) if host else None, 'hostVersion': None, 'toolVersion': '0.4.0',
                           'providerId': None, 'modelId': None, 'metadataSource': 'HOST_REPORTED' if host else 'UNKNOWN'}
    output['usage'] = dict.fromkeys(('inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens', 'cost'))
    active(platform, state)
    raw = json.dumps(output, ensure_ascii=False, separators=(',', ':'))  # 先保存原字节，再请求平台确认。
    if len(raw.encode()) > 1024 * 1024:
        raise WorkerError('FORMAT_INVALID')
    state.save(phase='HOST_RESULT_PENDING', resultJson=raw, resultSha256=hashlib.sha256(raw.encode()).hexdigest(),
               conclusion=output['conclusion'], usage=output['usage'], repairFacts=output['repair'], verificationFacts=output['verification'])
    close_tools(state)
    return upload(platform, state)


def stop(platform: PlatformClient, state: RunState, code: str = "CANCELLED") -> dict:
    """关闭本地材料并报告宿主未知，不能解锁重试门禁。"""
    if not state.data.get("runId"):
        raise WorkerError("ANALYSIS_NOT_READY")
    if state.data.get("phase") == "HOST_DONE":
        return {"status": "succeeded", "run": platform.status(state.data["runId"])}
    if state.data.get("protocol") != PROTOCOL:
        # 旧版本还有私有源码材料，不能用新流程伪造已完成清理。
        raise WorkerError("LOCAL_PROTOCOL_MISMATCH")
    # 先落盘阻止材料继续交付，再尝试失败回执。
    state.save(phase="HOST_STOP_PENDING", failureCode=code)
    close_tools(state)
    if not state.data.get("lease"):
        # 初次领取回执丢失且服务端已结束时无法补造原租约。
        raise WorkerError("STOP_UNCONFIRMED")
    # 到期仍可以用原身份确认本地工具停止；整体仍未知。
    run = platform.request("POST", f'/api/worker/v1/tasks/runs/{state.data["runId"]}/stopped',
                           body={"lease": state.data["lease"], "errorCode": code}, limit=65536)
    state.save(phase="HOST_STOPPED", lastRun=run)
    return {"status": "failed", "code": code, "taskId": state.data["taskId"], "runId": run["runId"],
            "localToolsStopped": True, "hostStopState": run["hostStopState"], "stopConfirmed": run["stopConfirmed"]}


def prepared(state: RunState, task_id: str) -> dict:
    """返回当前项目和冻结事件，不包含源码快照或全量文件清单。"""
    return {'status': 'prepared', 'taskId': task_id, 'runId': state.data['runId'],
            'evidence': state.data['evidence'], 'projectRoot': state.data['projectRoot'],
            'sourceMode': 'HOST_DIRECT', 'repairAuthorized': state.data['repairAuthorized']}


def prepare(platform: PlatformClient, state: RunState, task_id: str, project: Path | None = None, repair: bool = False) -> dict:
    """只定位项目并领取证据，重复准备保持原 Run；不读取源码文件。"""
    phase = state.data.get('phase')  # 原恢复记录不能按新语义重新分析。
    if phase == 'HOST_DONE':
        state.save(lastRun=platform.status(state.data['runId']))
        return {'status': 'succeeded', 'taskId': task_id, 'runId': state.data['runId'], 'conclusion': state.data['conclusion'],
                'usage': state.data['usage'], 'recovered': True, 'hostStopState': state.data['lastRun']['hostStopState']}
    if phase is not None and state.data.get('protocol') != PROTOCOL:
        raise WorkerError('LOCAL_PROTOCOL_MISMATCH')
    if phase is not None and (repair != state.data.get('repairAuthorized', False) or (project is not None and str(project_root(project)) != state.data.get('projectRoot'))):
        raise WorkerError('CONFIG_MISMATCH')
    if phase == 'HOST_RESULT_PENDING':
        return upload(platform, state)
    if phase == 'HOST_RUNNING':
        active(platform, state)
        return prepared(state, task_id)
    if phase not in {None, 'HOST_CLAIM_REQUEST'}:
        raise WorkerError('STOP_UNCONFIRMED')
    inspection = platform.inspect(task_id)  # 获取应用/事件绑定元数据，不猜测项目位置。
    if phase is None:
        if inspection['task']['state'] != 'READY':
            raise WorkerError('ANALYSIS_NOT_READY')
        repo = project_root(project)  # 只执行 Git 根定位，无清单/提交/远端读取。
        state.save(protocol=PROTOCOL, phase='HOST_CLAIM_REQUEST', taskId=task_id, requestId=str(uuid4()),
                   projectRoot=str(repo), repairAuthorized=repair)
    claimed = platform.claim(task_id, state.data['requestId'])  # 未知回执复用同一请求 ID。
    if claimed['run']['taskId'] != task_id or claimed['run'].get('snapshotId') is not None:
        state.save(phase='HOST_STOP_PENDING')
        close_tools(state)
        raise WorkerError('REFERENCE_INVALID')
    if not claimed.get('leaseToken'):
        state.save(phase='HOST_STOP_PENDING', runId=claimed['run']['runId'], lease=None, lastRun=claimed['run'])
        close_tools(state)
        raise WorkerError('STOP_UNCONFIRMED')
    state.save(runId=claimed['run']['runId'], lease={'generation': claimed['run']['leaseGeneration'], 'token': claimed['leaseToken']}, phase='HOST_RUNNING')
    try:
        active(platform, state, watch=False)
        raw = platform.evidence(state.data['runId'], state.data['lease'])  # 原字节独立校验服务端摘要。
        if hashlib.sha256(raw).hexdigest() != inspection['task']['evidenceSha256']:
            raise WorkerError('REFERENCE_INVALID')
        evidence = json.loads(raw)  # 正文仍受平台响应大小限制。
        if evidence.get('schemaVersion') != 2 or evidence['evidenceId'] != inspection['task']['evidenceId'] or evidence['event']['eventId'] != inspection['task']['eventId'] or evidence['event']['appId'] != inspection['task']['appId']:
            raise WorkerError('REFERENCE_INVALID')
        state.save(evidence=evidence)
        active(platform, state, watch=False)
        return prepared(state, task_id)
    except Exception as failure:
        try:
            stop(platform, state, str(failure) if str(failure) in {'REFERENCE_INVALID', 'LEASE_EXPIRED', 'CANCELLED'} else 'EXECUTOR_FAILED')
        except Exception:
            pass
        raise


def start_guardian(state: RunState, url: str, task_id: str, credential: str | None = None) -> None:
    """仅启动受信 Python 模块；不给看护 DeepSeek 或其他无关秘密。"""
    if state.data.get("guardianStarted") or state.data.get("phase") != "HOST_RUNNING":
        return
    # 环境白名单避免把宿主全部凭据复制给后台进程。
    environment = {key: value for key, value in os.environ.items() if key in {"PATH", "TMPDIR", "LANG"}}
    environment["APM_ANALYSIS_URL"] = url
    # 使用前台已解析的一组配置，后台不重新读取可能被编辑的文件。
    environment["APM_WORKER_CREDENTIAL"] = credential if credential is not None else os.environ.get("APM_WORKER_CREDENTIAL", "")
    # 参数只有任务 UUID 和私有目录，不包含 Token 或租约。
    process = subprocess.Popen([sys.executable, "-m", "apm_agent_worker.host", task_id, str(state.path.parent)],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               start_new_session=True, env=environment)
    state.save(guardianStarted=True, guardianPid=process.pid, guardianSeen=time.monotonic(), guardianWall=time.time())


def guardian(task_id: str, directory: Path) -> None:
    """有限寿命看护，独立于宿主工具调用维护取消和期限。"""
    # 地址和凭据只从受信启动环境接收。
    url = base_url(os.environ["APM_ANALYSIS_URL"])
    platform = PlatformClient(url, os.environ.get("APM_WORKER_CREDENTIAL", ""))
    # 无论配置或状态如何，进程总寿命都受限。
    deadline = time.monotonic() + 660
    # 正常心跳间隔二十秒，终态每两秒内观察到并退出。
    next_heartbeat = 0.0
    # 保留同一双时钟锚点，系统暂停不能通过新回执复活旧看护。
    lease_guard = LeaseGuard()
    try:
        while time.monotonic() < deadline:
            try:
                # 只短暂锁状态；遇到前台命令持锁不启动第二任务。
                state = RunState(directory, url, task_id)
            except WorkerError as failure:
                if str(failure) != "LOCAL_TASK_BUSY":
                    return
                time.sleep(1)
                continue
            try:
                if state.data.get("phase") != "HOST_RUNNING":
                    return
                if time.monotonic() >= next_heartbeat:
                    try:
                        active(platform, state, watch=False, guard=lease_guard)
                        state.save(guardianSeen=time.monotonic(), guardianWall=time.time())
                        next_heartbeat = time.monotonic() + 20
                    except Exception as failure:
                        try:
                            stop(platform, state, str(failure) if str(failure) in {"LEASE_EXPIRED", "CANCELLED", "TIME_LIMIT"} else "NETWORK_ERROR")
                        except Exception:
                            pass
                        return
            finally:
                state.close()
            time.sleep(2)
        # 看护达到自己的硬期限时仍尝试关闭材料，不能静默留下活动视图。
        try:
            state = RunState(directory, url, task_id)
        except WorkerError:
            # 前台持锁时，由每次材料动作的服务端到期校验继续阻断访问。
            return
        try:
            if state.data.get("phase") == "HOST_RUNNING":
                stop(platform, state, "TIME_LIMIT")
        finally:
            state.close()
    finally:
        platform.close()


if __name__ == "__main__":
    guardian(sys.argv[1], Path(sys.argv[2]))
