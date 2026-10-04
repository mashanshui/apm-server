"""受控 HTTP 传输验证凭据隔离、稳定请求以及错误脱敏。"""

import json

import httpx
import pytest

from apm_agent_worker.platform import PlatformClient, WorkerError


@pytest.mark.parametrize("credential", ["", "application-key", "apm_qt_" + "a" * 43])
def test_wrong_credential_calls_no_transport(credential):
    """错误类型在本地拒绝，不能扩大为 Session 或查询身份。"""
    # 错误凭据不能触发任何平台连接。
    with pytest.raises(WorkerError, match="WORKER_CREDENTIAL_MISSING"):
        PlatformClient("http://localhost:8080", credential)


def test_claim_retry_preserves_request_and_hides_network_detail():
    """响应丢失后调用方复用同一 ID，网络异常不包含真实凭据。"""
    # 捕获的内容仅为当前无敏感合成请求。
    requests = []
    # 第一次模拟连接已发送但响应丢失。
    def handler(request):
        requests.append(json.loads(request.content))
        if len(requests) == 1:
            raise httpx.ReadTimeout("private network diagnostics", request=request)
        assert request.headers["Authorization"] == "Bearer apm_aw_" + "a" * 43
        return httpx.Response(200, json={"run": {"runId": "fixed"}, "leaseToken": "synthetic"})
    # 同一客户端不自动发第二次不明确领取。
    client = PlatformClient("http://localhost:8080", "apm_aw_" + "a" * 43, httpx.MockTransport(handler))
    try:
        with pytest.raises(WorkerError, match="^NETWORK_ERROR$"):
            client.claim("task", "stable")
        assert client.claim("task", "stable")["run"]["runId"] == "fixed"
        assert requests == [{"requestId": "stable"}, {"requestId": "stable"}]
    finally:
        client.close()


def test_server_errors_and_redirects_do_not_leak_or_follow():
    """服务错误及重定向仅返回稳定码，不回显服务端消息。"""
    # 不接受外部跳转和响应里的敏感内容。
    def handler(request):
        if request.url.path.endswith("redirect"):
            return httpx.Response(302, headers={"Location": "https://other.invalid/private"})
        return httpx.Response(409, json={"code": "ANALYSIS_LEASE_INVALID", "message": "private-token"})
    client = PlatformClient("http://localhost:8080", "apm_aw_" + "a" * 43, httpx.MockTransport(handler))
    try:
        with pytest.raises(WorkerError, match="^ANALYSIS_LEASE_INVALID$"):
            client.inspect("expired")
        with pytest.raises(WorkerError, match="^PLATFORM_REQUEST_REJECTED$"):
            client.inspect("redirect")
    finally:
        client.close()
