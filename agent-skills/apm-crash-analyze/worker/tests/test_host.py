"""单任务/证据/租约/幂等回传；宿主源码读写由宿主负责。"""
import hashlib
import json
import os
from pathlib import Path
from datetime import datetime, timedelta, timezone
import pytest
from apm_agent_worker import host
from apm_agent_worker.platform import WorkerError
from apm_agent_worker.state import RunState
from test_source import workspace
from test_analysis import model_result

# 合成协议标识，不关联真实用户或设备。
TASK = '11111111-1111-4111-8111-111111111111'
RUN = '22222222-2222-4222-8222-222222222222'

class Platform:
    """有界假平台返回真实协议形状，记录控制面副作用。"""
    def __init__(self):
        # 正式字段之外带登记版本，防止源码绑定比较遗漏实际协议。
        self.evidence_data = {"evidenceId": TASK, "schemaVersion": 2,
                              "event": {"eventId": "event", "appId": "app"}, "fragments": [{"id": "raw-crash"}]}
        self.raw = json.dumps(self.evidence_data).encode()
        self.calls = []
        self.cancelled = False
        self.network_failures = 0
        self.tampered = False
        self.saved = None

    def run(self):
        """服务端时钟和当前租约，不携带长期身份。"""
        now = datetime.now(timezone.utc)
        return {"runId": RUN, "taskId": TASK, "leaseGeneration": 1, "state": "RUNNING", "taskState": "CANCELLING" if self.cancelled else "RUNNING",
                "serverNow": now.isoformat(), "leaseExpiresAt": (now + timedelta(seconds=90)).isoformat(),
                "deadlineAt": (now + timedelta(seconds=600)).isoformat(), "stopConfirmed": False, "hostStopState": "UNKNOWN"}

    def inspect(self, task):
        """准备只取得绑定元信息。"""
        return {"task": {"state": "READY", "evidenceSha256": hashlib.sha256(self.raw).hexdigest(), "evidenceId": TASK, "eventId": "event", "appId": "app"}}

    def claim(self, task, request):
        """稳定领取不生成不同运行。"""
        self.calls.append(("claim", request))
        return {"run": self.run(), "leaseToken": "synthetic"}

    def status(self, run):
        """终态恢复读取原分配，不重新分析。"""
        return {**self.run(), "state": "SUCCEEDED", "taskState": "SUCCEEDED"}

    def heartbeat(self, run, lease):
        """用当前状态阻断取消后材料访问。"""
        return self.run()

    def evidence(self, *args):
        """独立摘要校验不能信任已解析字段。"""
        return b'{}' if self.tampered else self.raw

    def request(self, method, path, body, limit):
        """复制原始回传用于比较重试摘要与停止事实。"""
        self.calls.append((path, body.copy()))
        if path.endswith('/complete'):
            assert body['localToolsStopped'] and not body['stopConfirmed']
            if self.network_failures:
                self.network_failures -= 1
                raise WorkerError("NETWORK_ERROR")
            self.saved = json.loads(body['resultJson'])
        return {**self.run(), "state": "SUCCEEDED" if path.endswith('/complete') else "FAILED", "localToolsStopped": True}


@pytest.fixture
def ready(tmp_path):
    """真实 Git 项目只定位根，不复制任何源码。"""
    root = workspace(tmp_path)  # 当前项目与 Skill/状态相互独立。
    state = RunState(tmp_path / 'state', 'http://localhost:8080', TASK)
    platform = Platform()  # 仅替换平台，源码和状态使用实际文件。
    prepared = host.prepare(platform, state, TASK, root)
    assert prepared['sourceMode'] == 'HOST_DIRECT'
    state.save(guardianStarted=True, guardianPid=os.getpid(), guardianSeen=host.time.monotonic(), guardianWall=host.time.time())
    try:
        yield platform, state, root
    finally:
        state.close()


def test_prepare_never_walks_or_copies_project(tmp_path, monkeypatch):
    """带一 GiB 附件的项目只执行根定位，索引和工作区保持原值。"""
    root = workspace(tmp_path)  # 大附件无须被枚举、解码或读取。
    with (root / 'oversized.vendor').open('wb') as stream:
        stream.truncate(1024 * 1024 * 1024)
    index = (root / '.git/index').read_bytes()  # 原始暂存区。
    monkeypatch.setattr(Path, 'rglob', lambda *args: pytest.fail('unexpected source walk'))
    state = RunState(tmp_path / 'state', 'http://localhost:8080', TASK)
    try:
        prepared = host.prepare(Platform(), state, TASK, root)
        assert prepared['repairAuthorized'] is False
        assert all(key not in state.data for key in ('sourceDirectory', 'snapshotId', 'reads', 'sourceExclusions'))
        assert index == (root / '.git/index').read_bytes()
    finally:
        state.close()


def test_repeat_prepare_and_submit_binding(ready):
    """准备恢复不重复分配，宿主片段回传只做当前位置核对。"""
    platform, state, root = ready
    assert host.prepare(platform, state, TASK, root)['runId'] == RUN
    assert len(platform.calls) == 1
    result = host.submit(platform, state, json.dumps(model_result()), 'Codex')
    assert result['status'] == 'succeeded'
    assert platform.saved['schemaVersion'] == 4
    assert platform.saved['runId'] == RUN
    assert platform.saved['sourceRefs'][0]['currentCheck'] == 'CURRENT_MATCH'
    assert platform.saved['repair']['metadataSource'] == 'HOST_REPORTED'
    assert 'snapshotId' not in platform.saved
    assert 'workspaceUnchanged' not in platform.saved['verification']
    assert state.data['localToolsStopped'] and state.data['lease'] is None
    assert host.prepare(platform, state, TASK, root)['recovered']


def test_report_response_unknown_reuses_exact_bytes(ready):
    """网络未知仅对账原结果，不重新读取源码、修改或分析。"""
    platform, state, root = ready
    platform.network_failures = 2
    with pytest.raises(WorkerError, match='RESULT_PENDING'):
        host.submit(platform, state, json.dumps(model_result()))
    raw = state.data['resultJson']  # 原结果字节私有保留。
    (root / 'Example.kt').write_text('later user edit')
    assert host.submit(platform, state, 'invalid new result')['status'] == 'succeeded'
    attempts = [body for path, body in platform.calls if path.endswith('/complete')]
    assert len(attempts) == 3
    assert all(item['resultJson'] == raw for item in attempts)


def test_readonly_cannot_be_upgraded(ready):
    """原 Run 修复意图不可升级，宿主扩权不能通过报告绕过。"""
    platform, state, root = ready
    with pytest.raises(WorkerError, match='CONFIG_MISMATCH'):
        host.prepare(platform, state, TASK, root, True)
    report = model_result()  # 非修复任务提交修改必须拒绝。
    report['repair'] = {'status': 'APPLIED', 'files': [{'path': 'Example.kt', 'summary': 'changed'}], 'reason': ''}
    with pytest.raises(WorkerError, match='REPAIR_NOT_AUTHORIZED'):
        host.submit(platform, state, json.dumps(report))
    assert platform.saved is None


@pytest.mark.parametrize('failure', ['cancel', 'dead', 'stale', 'clock', 'network'])
def test_lease_and_guardian_gate_submit(ready, monkeypatch, failure):
    """状态未知、失联或取消拒绝报告，不声称能中止宿主其他工具。"""
    platform, state, _ = ready
    if failure == 'cancel': platform.cancelled = True
    elif failure == 'dead': monkeypatch.setattr(host.os, 'kill', lambda *args: (_ for _ in ()).throw(ProcessLookupError()))
    elif failure == 'stale': state.save(guardianSeen=host.time.monotonic() - 46)
    elif failure == 'clock': state.save(guardianWall=host.time.time() - 40)
    else: monkeypatch.setattr(platform, 'heartbeat', lambda *args: (_ for _ in ()).throw(WorkerError('NETWORK_ERROR')))
    with pytest.raises(WorkerError):
        host.submit(platform, state, json.dumps(model_result()))
    assert platform.saved is None


def test_stop_keeps_host_unknown(ready):
    """只关闭任务工具，不伪造宿主停止或源码已回滚。"""
    platform, state, root = ready
    original = (root / 'Example.kt').read_bytes()  # 宿主业务源码不受 stop 修改。
    result = host.stop(platform, state)
    assert result['localToolsStopped'] and result['hostStopState'] == 'UNKNOWN'
    assert not result['stopConfirmed']
    assert original == (root / 'Example.kt').read_bytes()


def test_lost_claim_uses_same_request(ready, monkeypatch):
    """领取回执丢失后保持原请求 ID，不创建源码材料或新分配。"""
    platform, state, root = ready
    state.save(phase='HOST_CLAIM_REQUEST', runId=None, lease=None, guardianStarted=False)
    platform.calls.clear()
    original = platform.claim  # 原合成分配。
    attempts = []  # 仅观察稳定请求，不记录秘密。
    def claim(task, request):
        """第一次响应丢失，第二次返回原 Run。"""
        attempts.append(request)
        result = original(task, request)
        if len(attempts) == 1: raise WorkerError('NETWORK_ERROR')
        return result
    monkeypatch.setattr(platform, 'claim', claim)
    with pytest.raises(WorkerError, match='NETWORK_ERROR'):
        host.prepare(platform, state, TASK, root)
    assert host.prepare(platform, state, TASK, root)['runId'] == RUN
    assert attempts[0] == attempts[1]


def test_legacy_active_state_not_silently_upgraded(ready):
    """旧活动记录保留，必须先用原流程结束或停止核验。"""
    platform, state, root = ready
    state.save(protocol=None, snapshotId='historical')
    with pytest.raises(WorkerError, match='LOCAL_PROTOCOL_MISMATCH'):
        host.prepare(platform, state, TASK, root)
    with pytest.raises(WorkerError, match='LOCAL_PROTOCOL_MISMATCH'):
        host.submit(platform, state, json.dumps(model_result()))
    with pytest.raises(WorkerError, match='LOCAL_PROTOCOL_MISMATCH'):
        host.active(platform, state)
    with pytest.raises(WorkerError, match='LOCAL_PROTOCOL_MISMATCH'):
        host.stop(platform, state)
    state.save(phase='HOST_RESULT_PENDING')
    with pytest.raises(WorkerError, match='LOCAL_PROTOCOL_MISMATCH'):
        host.submit(platform, state, '')
    assert state.data['snapshotId'] == 'historical'
    assert platform.saved is None and not state.data.get('localToolsStopped')


def test_evidence_mismatch_stops_before_delivery(tmp_path):
    """冻结证据原字节摘要不符时失败，不将错误材料交给宿主。"""
    root = workspace(tmp_path)  # 当前项目仅用于定位。
    platform = Platform()
    platform.tampered = True
    state = RunState(tmp_path / 'state', 'http://localhost:8080', TASK)
    try:
        with pytest.raises(WorkerError, match='REFERENCE_INVALID'):
            host.prepare(platform, state, TASK, root)
        assert state.data['localToolsStopped']
        assert state.data['phase'] == 'HOST_STOPPED'
    finally:
        state.close()
