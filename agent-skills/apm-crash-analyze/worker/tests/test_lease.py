"""租约在暂停、延迟和错误状态下必须失败关闭。"""

import pytest

from apm_agent_worker.lease import LeaseGuard
from apm_agent_worker.platform import WorkerError


def run_state():
    """返回九十秒有效期的合成数据库回执。"""
    return {"state": "RUNNING", "taskState": "RUNNING", "serverNow": "2026-10-01T00:00:00Z",
            "leaseExpiresAt": "2026-10-01T00:01:30Z", "deadlineAt": "2026-10-01T00:10:00Z"}


def test_lease_subtracts_transport_and_stop_margin():
    """通信耗时和十五秒余量都不能被误算成可用执行时间。"""
    # 请求发出后耗时十秒，返回时剩余六十五秒。
    now = [10.0]
    guard = LeaseGuard(lambda: now[0], lambda: now[0])
    assert guard.update(run_state(), 0) == 65
    now[0] = 76
    with pytest.raises(WorkerError, match="LEASE_EXPIRED"):
        guard.remaining()


def test_clock_divergence_and_cancellation_stop_execution():
    """系统睡眠或暂停恢复不确定时不得继续派发。"""
    monotonic = [0.0]
    wall = [0.0]
    guard = LeaseGuard(lambda: monotonic[0], lambda: wall[0])
    guard.update(run_state(), 0)
    wall[0] = 30
    with pytest.raises(WorkerError, match="LEASE_EXPIRED"):
        guard.remaining()
    # 新建守卫也不能接受取消回执为续租成功。
    guard = LeaseGuard(lambda: monotonic[0], lambda: wall[0])
    with pytest.raises(WorkerError, match="CANCELLED"):
        guard.update({**run_state(), "taskState": "CANCELLING"}, 0)

