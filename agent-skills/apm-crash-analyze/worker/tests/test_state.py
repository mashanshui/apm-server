"""恢复记录权限和单任务互斥，模型环境不访问这些材料。"""

import os

import pytest

from apm_agent_worker.platform import WorkerError
from apm_agent_worker.state import RunState


# 当前合成任务的规范 UUID。
TASK = "11111111-1111-4111-8111-111111111111"


def test_state_is_private_atomic_and_locked(tmp_path):
    """落盘记录可重启读取，另一进程不能同时运行相同任务。"""
    state = RunState(tmp_path / "state", "http://localhost:8080", TASK)
    try:
        state.save(phase="CLAIM_REQUEST", requestId="stable")
        assert state.path.stat().st_mode & 0o777 == 0o600
        assert state.path.parent.stat().st_mode & 0o777 == 0o700
        with pytest.raises(WorkerError, match="LOCAL_TASK_BUSY"):
            RunState(tmp_path / "state", "http://localhost:8080", TASK)
    finally:
        state.close()
    restored = RunState(tmp_path / "state", "http://localhost:8080", TASK)
    try:
        assert restored.data == {"phase": "CLAIM_REQUEST", "requestId": "stable"}
        assert not list(restored.path.parent.glob("*.tmp"))
    finally:
        restored.close()


def test_state_rejects_unprotected_recovery_file(tmp_path):
    """短期租约也必须只对当前用户可读，不能加载公开恢复记录。"""
    state = RunState(tmp_path / "state", "http://localhost:8080", TASK)
    state.save(phase="CLAIMED")
    path = state.path
    state.close()
    path.chmod(0o644)
    with pytest.raises(WorkerError, match="STATE_UNSAFE"):
        RunState(tmp_path / "state", "http://localhost:8080", TASK)


def test_oversize_state_preserves_recoverable_original(tmp_path):
    """超限写入不能破坏已有可恢复状态或进程内事实。"""
    state = RunState(tmp_path/'state','http://localhost:8080',TASK)
    try:
        state.save(phase='HOST_RUNNING',runId='original')
        before = state.path.read_bytes()  # 真实已落盘记录。
        with pytest.raises(WorkerError,match='STATE_TOO_LARGE'):
            state.save(exclusions='x'*(2*1024*1024))
        assert state.path.read_bytes()==before
        assert state.data=={'phase':'HOST_RUNNING','runId':'original'}
    finally:
        state.close()
