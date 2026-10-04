"""宿主入口不要求独立模型或 Docker，诊断不输出秘密。"""

import pytest
from apm_agent_worker.cli import main, prerequisites


def test_help_has_material_commands_and_no_independent_model(capsys):
    """只暴露当前宿主流程，不误导用户调用旧 run/probe。"""
    with pytest.raises(SystemExit) as info:
        main(["--help"])
    assert info.value.code == 0
    output = capsys.readouterr().out  # 当前入口不包含工具源码读取/编辑。
    assert "prepare" in output
    assert all(name not in output for name in ("search", "revert", "apply"))
    with pytest.raises(SystemExit):
        main(["run", "11111111-1111-4111-8111-111111111111"])


def test_missing_config_is_not_ready(monkeypatch, capsys):
    """缺少平台凭据时不声称分析可执行。"""
    monkeypatch.delenv("APM_WORKER_CREDENTIAL", raising=False)
    monkeypatch.delenv("APM_ANALYSIS_URL", raising=False)
    assert main(["doctor"]) == 1
    assert '"worker_credential_configured": false' in capsys.readouterr().out


def test_no_deepseek_or_docker_required(monkeypatch):
    """宿主配置自身模型，不继承旧强制执行器前提。"""
    monkeypatch.setenv("APM_WORKER_CREDENTIAL", "apm_aw_" + "A" * 43)
    monkeypatch.setenv("APM_ANALYSIS_URL", "http://localhost:8080")
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    assert all(prerequisites().values())


def test_credential_never_appears_in_diagnostic(monkeypatch, capsys):
    """诊断只表示存在性，不打印凭据或旧模型密钥。"""
    credential = "apm_aw_" + "B" * 43  # 合成凭据，不关联实际应用。
    monkeypatch.setenv("APM_WORKER_CREDENTIAL", credential)
    monkeypatch.setenv("APM_ANALYSIS_URL", "http://localhost:8080")
    assert main(["doctor"]) == 0
    assert credential not in capsys.readouterr().out


@pytest.mark.parametrize('arguments', [
    ['prepare', '--project', '/synthetic/mapping.json'],
    ['prepare', 'not-a-uuid', '--project', '/synthetic/mapping.json'],
    ['prepare', 'https://example.invalid/events/one', '--project', '/synthetic/mapping.json'],
    ['prepare', '11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222', '--project', '/synthetic/mapping.json'],
])
def test_invalid_or_ambiguous_task_never_constructs_platform(arguments, monkeypatch):
    """缺失、链接、非法和多个任务在网络或领取之前拒绝。"""
    from apm_agent_worker import cli
    # 构造控制面即失败，证明参数拒绝发生在任何权限或网络动作之前。
    monkeypatch.setattr(cli, 'PlatformClient', lambda *args: pytest.fail('unexpected platform access'))
    with pytest.raises(SystemExit) as info:
        cli.main(arguments)
    assert info.value.code == 2


