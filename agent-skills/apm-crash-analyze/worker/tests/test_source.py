"""宿主自行读取，Python 只定位项目和按引用检查；真实 Git/文件边界。"""
import os
import subprocess
from pathlib import Path
import pytest
from apm_agent_worker.source import SourceBlocked, project_root, current_snippet


def workspace(tmp_path):
    """建立中文空格 Git 项目，不创建提交或远端。"""
    root = tmp_path / '当前 项目'  # 用户实际工作区。
    root.mkdir()
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    (root / 'Example.kt').write_text('fun parse() {\nthrow IllegalStateException()\n}\n')
    subprocess.run(['git', '-C', str(root), 'add', 'Example.kt'], check=True)
    return root


def test_project_root_without_commit_remote_or_file_walk(tmp_path):
    """大附件不影响根定位，用户原索引和未提交文件保持不变。"""
    root = workspace(tmp_path)
    index = (root / '.git/index').read_bytes()  # 核对工具没有改动索引。
    with (root / 'oversized.archive').open('wb') as stream:
        stream.truncate(1024 * 1024 * 1024)
    assert project_root(root) == root
    assert current_snippet(root, 'Example.kt', 2, 2) == 'throw IllegalStateException()'
    assert index == (root / '.git/index').read_bytes()


@pytest.mark.parametrize('kind', ['link', 'parent-link', 'fifo', 'missing', 'invalid-utf8', 'long-line'])
def test_unavailable_references_are_not_trusted(tmp_path, kind):
    """不跟随链接、不挂起于 FIFO，不把不可读或超限位置标成匹配。"""
    root = workspace(tmp_path)
    name = 'Example.kt'  # 默认真实文件被不同失败条件替换。
    if kind == 'link':
        (root / name).unlink(); (root / name).symlink_to(tmp_path / 'outside')
    elif kind == 'parent-link':
        (root / 'src').symlink_to(tmp_path); name = 'src/private.kt'
    elif kind == 'fifo':
        (root / name).unlink(); os.mkfifo(root / name)
    elif kind == 'missing':
        (root / name).unlink()
    elif kind == 'invalid-utf8':
        (root / name).write_bytes(b'\xff\xff')
    else:
        (root / name).write_text('x' * 9000)
    assert current_snippet(root, name, 1, 1) is None


def test_line_range_only_current_file_not_entire_repository(tmp_path):
    """直接读取当前引用范围，允许文件本身超过原十六 MiB 上限。"""
    root = workspace(tmp_path)
    with (root / 'Example.kt').open('wb') as stream:
        stream.write(b'line one\nline two\n')
        stream.truncate(32 * 1024 * 1024)
    assert current_snippet(root, 'Example.kt', 1, 2) == 'line one\nline two'
    assert current_snippet(root, 'Example.kt', 3, 3) is None


def test_project_missing_or_relative_fails(tmp_path):
    """项目外不猜测源码位置，也不根据 Skill 位置挑选项目。"""
    with pytest.raises(SourceBlocked, match='PROJECT_PATH_INVALID'):
        project_root(Path('relative'))
    with pytest.raises(SourceBlocked, match='PROJECT_NOT_FOUND'):
        project_root(tmp_path)
