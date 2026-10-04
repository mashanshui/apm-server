"""验证配置提示先于任务、秘密隔离、权限及后台身份一致性。"""

import json
import os
from types import SimpleNamespace

import pytest
from apm_agent_worker import cli, config, host
from apm_agent_worker.config import ConfigurationError, load_configuration

# 合成标识和凭据，测试不接触真实应用或设备。
TASK = "11111111-1111-4111-8111-111111111111"
CREDENTIAL = "apm_aw_" + "C" * 43


@pytest.fixture
def local_config(tmp_path, monkeypatch):
    """把唯一固定配置放到隔离位置，清除测试进程继承的真实环境。"""
    # 中文空格路径同时覆盖用户安装位置。
    path = tmp_path / "中文 空格" / "config.local.json"
    path.parent.mkdir()
    monkeypatch.setattr(config, "CONFIG_PATH", path)
    monkeypatch.setattr(cli, "CONFIG_PATH", path)
    monkeypatch.delenv("APM_ANALYSIS_URL", raising=False)
    monkeypatch.delenv("APM_WORKER_CREDENTIAL", raising=False)
    return path


def write_config(path, **changes):
    """写入只有当前用户可访问的合成配置。"""
    # 默认有效字段可按单个失败场景覆盖。
    value = {"platformUrl": "http://127.0.0.1:8080", "workerCredential": CREDENTIAL, **changes}
    path.write_text(json.dumps(value))
    path.chmod(0o600)


@pytest.mark.parametrize("content", [None, '{}', '{"platformUrl":"","workerCredential":""}'])
def test_missing_configuration_prevents_all_task_side_effects(local_config, monkeypatch, capsys, content):
    """空配置必须先提示，不能连接、领取、创建状态或采集材料。"""
    if content is not None:
        local_config.write_text(content)
    monkeypatch.setattr(cli, "PlatformClient", lambda *a: pytest.fail("unexpected network"))
    monkeypatch.setattr(cli, "RunState", lambda *a: pytest.fail("unexpected state"))
    monkeypatch.setattr(host, "prepare", lambda *a: pytest.fail("unexpected source"))
    assert cli.main(["prepare", TASK]) == 1
    result = json.loads(capsys.readouterr().out)
    assert result['code'] == 'CONFIG_MISSING'
    assert result['missingFields'] == ['platformUrl', 'workerCredential']
    assert result['configPath'] == str(local_config)


def test_file_configuration_is_private_and_diagnostic_is_redacted(local_config, capsys):
    """无需环境变量即可使用文件，诊断与对象 repr 不包含凭据。"""
    write_config(local_config)
    current = load_configuration()
    assert current.url == 'http://127.0.0.1:8080' and current.credential == CREDENTIAL
    assert CREDENTIAL not in repr(current)
    assert cli.main(['doctor']) == 0
    output = capsys.readouterr().out
    assert CREDENTIAL not in output and json.loads(output)['configSource'] == 'file'


@pytest.mark.parametrize('content', ['', '[1]', '{"platformUrl":null}', '{"workerCredential":42}',
                                     '{"workerCredential":"SYNTHETIC_PRIVATE",}',
                                     '{"platformUrl":"x","platformUrl":"y"}',
                                     '{"command":"SYNTHETIC_PRIVATE"}', 'x' * 8193])
def test_invalid_json_is_bounded_and_never_echoed(local_config, capsys, content):
    """格式和结构错误仅输出稳定提示，不能反射用户输入。"""
    local_config.write_text(content)
    assert cli.main(['doctor']) == 1
    output = capsys.readouterr().out
    assert json.loads(output)['code'] == 'CONFIG_INVALID'
    assert 'SYNTHETIC_PRIVATE' not in output


def test_secret_permissions_require_current_user_only(local_config, capsys):
    """已有秘密若可被其他用户读取则拒绝，并提示本地改权限。"""
    write_config(local_config)
    local_config.chmod(0o644)
    assert cli.main(['doctor']) == 1
    assert json.loads(capsys.readouterr().out)['code'] == 'CONFIG_PERMISSIONS'
    local_config.chmod(0o600)
    assert load_configuration().credential == CREDENTIAL


@pytest.mark.parametrize('credential', ['apm_qt_synthetic', '<应用 Worker 凭据>', 'apm_aw_short'])
def test_wrong_credential_format_never_connects(local_config, monkeypatch, capsys, credential):
    """查询身份、占位符及残缺 Worker 值在网络前拒绝，不回显输入。"""
    write_config(local_config, workerCredential=credential)
    monkeypatch.setattr(cli, 'PlatformClient', lambda *a: pytest.fail('unexpected network'))
    assert cli.main(['prepare', TASK]) == 1
    output = capsys.readouterr().out
    assert json.loads(output)['code'] == 'WORKER_CREDENTIAL_MISSING'
    assert credential not in output


@pytest.mark.parametrize('kind', ['link', 'fifo'])
def test_special_configuration_is_rejected_without_blocking(local_config, kind):
    """不跟随配置链接、不打开可能挂起的管道。"""
    if kind == 'link':
        target = local_config.parent / 'outside.json'
        write_config(target)
        local_config.symlink_to(target)
    else:
        os.mkfifo(local_config)
    with pytest.raises(ConfigurationError):
        load_configuration()


def test_complete_environment_overrides_file_as_pair(local_config, monkeypatch):
    """保留已有可信环境入口，成对覆盖无需读取文件秘密。"""
    local_config.write_text('invalid configuration')
    monkeypatch.setenv('APM_ANALYSIS_URL', 'http://localhost:8081')
    monkeypatch.setenv('APM_WORKER_CREDENTIAL', 'apm_aw_' + 'D' * 43)
    current = load_configuration()
    assert current.source == 'environment' and current.url == 'http://localhost:8081'


@pytest.mark.parametrize('key', ['APM_ANALYSIS_URL', 'APM_WORKER_CREDENTIAL'])
def test_partial_environment_cannot_mix_with_file(local_config, monkeypatch, key):
    """旧环境地址不能把文件中的凭据发送到不同服务。"""
    write_config(local_config)
    monkeypatch.setenv(key, 'http://localhost:8081' if key.endswith('URL') else CREDENTIAL)
    with pytest.raises(ConfigurationError) as failure:
        load_configuration()
    assert failure.value.diagnostic['code'] == 'CONFIG_MISSING'
    assert failure.value.diagnostic['configSource'] == 'environment'


@pytest.mark.parametrize('value', ['file:///tmp/private', 'http://user:password@localhost',
                                  'http://localhost/path', 'http://localhost:invalid',
                                  'http://localhost?secret=hidden', 'http://localhost\n'])
def test_invalid_platform_address_is_rejected_before_network(local_config, value):
    """地址不能含认证信息或额外路由；尾部编辑换行被正常清除。"""
    write_config(local_config, platformUrl=value)
    if value.endswith('\n'):
        assert load_configuration().url == 'http://localhost'
    else:
        with pytest.raises(ConfigurationError) as failure:
            load_configuration()
        assert failure.value.diagnostic['code'] == 'PLATFORM_URL_INVALID'


def test_guardian_receives_resolved_identity_not_ambient_credential(local_config, monkeypatch):
    """后台心跳使用本次配置，参数、公开状态不带凭据。"""
    write_config(local_config)
    current = load_configuration()
    monkeypatch.setenv('APM_WORKER_CREDENTIAL', 'unrelated-ambient-secret')
    observed = {}  # 捕获 Popen 入参，替代后台进程。
    state = SimpleNamespace(data={'phase': 'HOST_RUNNING'}, path=local_config.parent/'state'/'task.json',
                            save=lambda **values: observed.update(state=values))

    def popen(command, **options):
        """仅记录传递，不启动看护或连接服务。"""
        observed.update(command=command, options=options)
        return SimpleNamespace(pid=12345)

    monkeypatch.setattr(host.subprocess, 'Popen', popen)
    host.start_guardian(state, current.url, TASK, current.credential)
    assert observed['options']['env']['APM_WORKER_CREDENTIAL'] == CREDENTIAL
    assert observed['options']['env']['APM_ANALYSIS_URL'] == current.url
    assert CREDENTIAL not in str(observed['command']) and CREDENTIAL not in str(observed['state'])
