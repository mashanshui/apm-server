"""验证独立交付边界与启动器参数语义，防止配置泄漏和路径失效。"""

import ast
import importlib.util
import json
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

# 单份源码位于 Skill 内，开发测试从真实路径定位共享脚本。
ROOT = Path(__file__).resolve().parents[4]
# 当前 Skill 的完整源目录。
SKILL = ROOT / "agent-skills/apm-crash-analyze"


def load_module(name, path):
    """独立导入脚本，不依赖当前工作目录或额外 Python 路径。"""
    # 按可信文件位置构造模块，脚本无导入时执行命令。
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# 打包和启动入口均为标准库脚本。
PACKAGER = load_module("skill_packager", ROOT / "scripts/package-agent-skill.py")
LAUNCHER = load_module("skill_launcher", SKILL / "scripts/apm_analysis.py")


def public_source(directory):
    """仅建立受控公开文件，并额外放入不应交付的敏感哨兵。"""
    # 文件列表采用真实交付源码，检查包内容时不触及真实秘密。
    names = (*PACKAGER.PUBLIC_FILES, *(f"worker/src/apm_agent_worker/{name}.py" for name in PACKAGER.RUNTIME_MODULES))
    for name in names:
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes((SKILL / name).read_bytes())
    for name in (".env", "config.local.json", "worker/repositories.local.json", "worker/.venv/private", "worker/validation.md",
                 "worker/src/apm_agent_worker/opencode.py", "worker/tests/private.json"):
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("SYNTHETIC_PRIVATE_SENTINEL")
    return names


def test_zip_excludes_unlisted_private_and_legacy_files(tmp_path):
    """新文件不能因递归打包进入包；交付也不依赖旧执行器。"""
    # 中文空格安装路径检验路径处理。
    source = tmp_path / "中文 安装"
    names = public_source(source)
    output = tmp_path / "skill.zip"
    assert PACKAGER.package(source, output) == len(names) + 1
    with zipfile.ZipFile(output) as archive:
        assert set(archive.namelist()) == {f"apm-crash-analyze/{name}" for name in (*names, "config.local.json")}
        assert all(b"SYNTHETIC_PRIVATE_SENTINEL" not in archive.read(name) for name in archive.namelist())
        assert json.loads(archive.read("apm-crash-analyze/config.local.json")) == {"platformUrl": "", "workerCredential": ""}
        assert archive.getinfo("apm-crash-analyze/config.local.json").external_attr >> 16 & 0o777 == 0o600
    # 静态核对当前全部模块的包内依赖，避免漏打运行链路模块。
    for name in PACKAGER.RUNTIME_MODULES:
        tree = ast.parse((source / f"worker/src/apm_agent_worker/{name}.py").read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("apm_agent_worker."):
                assert node.module.split(".")[1] in PACKAGER.RUNTIME_MODULES


@pytest.mark.parametrize("target", ["README.md", "worker"])
def test_packager_rejects_file_or_parent_symlink(tmp_path, target):
    """文件或父目录链接均不能把包外内容带入交付。"""
    # 用合成文件测试链接，避免读取用户配置。
    source = tmp_path / "skill"
    public_source(source)
    path = source / target
    moved = tmp_path / "outside"
    path.rename(moved)
    path.symlink_to(moved, target_is_directory=moved.is_dir())
    with pytest.raises(ValueError, match="不能是链接"):
        PACKAGER.package(source, tmp_path / "skill.zip")
    assert not (tmp_path / "skill.zip").exists()


def test_packager_missing_required_file_fails_before_output(tmp_path):
    """缺失运行时不能产生可误用的残缺 ZIP。"""
    source = tmp_path / "skill"
    public_source(source)
    (source / "worker/uv.lock").unlink()
    with pytest.raises(ValueError, match="缺失或越界"):
        PACKAGER.package(source, tmp_path / "skill.zip")
    assert not (tmp_path / "skill.zip").exists()


def test_launcher_preserves_arguments_cwd_environment_and_exit(monkeypatch, tmp_path):
    """中文路径、空格与 Shell 字符必须原样传递，不能解释为命令。"""
    # 合成参数和凭据，不连接平台。
    arguments = ["submit", "11111111-1111-4111-8111-111111111111", "--result", "中文 空格 $(touch nope).json"]
    seen = {}
    monkeypatch.setattr(sys, "argv", [str(SKILL / "scripts/apm_analysis.py"), *arguments])
    monkeypatch.setattr(LAUNCHER.shutil, "which", lambda _: "/可信 路径/uv")
    monkeypatch.setenv("APM_WORKER_CREDENTIAL", "SYNTHETIC_SECRET")
    monkeypatch.chdir(tmp_path)

    def run(command, **options):
        """记录调用语义，模拟 Worker 的非零返回。"""
        seen.update(command=command, options=options, cwd=Path.cwd())
        return SimpleNamespace(returncode=7)

    monkeypatch.setattr(LAUNCHER.subprocess, "run", run)
    assert LAUNCHER.main() == 7
    assert seen["command"][-len(arguments):] == arguments
    assert {"--locked", "--no-dev", "--no-env-file", "--no-config"}.issubset(seen["command"])
    assert seen["cwd"] == tmp_path
    assert "cwd" not in seen["options"] and "shell" not in seen["options"]
    assert seen["options"]["env"]["APM_WORKER_CREDENTIAL"] == "SYNTHETIC_SECRET"
    assert seen["options"]["env"]["UV_PROJECT_ENVIRONMENT"] == str(SKILL / "worker/.venv")


def test_launcher_missing_uv_is_clear_and_does_not_run(monkeypatch, capsys):
    """宿主缺少 uv 时给出明确错误，不自动下载安装脚本。"""
    monkeypatch.setattr(LAUNCHER.shutil, "which", lambda _: None)
    assert LAUNCHER.main() == 127
    assert "未找到 uv" in capsys.readouterr().err
