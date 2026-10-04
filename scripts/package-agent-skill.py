"""导出自包含 Skill；只收录明确列出的公开文件，不遍历本地配置。"""

import argparse
import json
import zipfile
from pathlib import Path

# 仅当前宿主流程所需模块；旧独立执行器、测试和验收数据不进入交付包。
RUNTIME_MODULES = ("__init__", "__main__", "cli", "config", "host", "analysis", "lease", "platform",
                   "redaction", "source", "state")
# 必需的说明、入口和锁定依赖，禁止通过宽泛递归包含秘密。
PUBLIC_FILES = ("SKILL.md", "README.md", ".gitignore", "agents/openai.yaml", "scripts/apm_analysis.py",
                "worker/README.md", "worker/.gitignore", "worker/pyproject.toml", "worker/uv.lock")


def package(skill: Path, output: Path) -> int:
    """校验完整公开文件集后导出 ZIP，避免链接将私有文件带入包。"""
    # Skill 根目录允许调用者使用项目发现链接；包内文件及父目录不得是链接。
    root = skill.resolve(strict=True)
    # 列表显式固定，目录里后来加入的配置不会自动被收录。
    files = (*PUBLIC_FILES, *(f"worker/src/apm_agent_worker/{name}.py" for name in RUNTIME_MODULES))
    for relative in files:
        # 校验每一级目录，防止目录链接指向 Skill 以外。
        path = root / relative
        if any(part.is_symlink() for part in (path, *path.parents) if part != root and root in part.parents):
            raise ValueError(f"交付文件不能是链接：{relative}")
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError(f"交付文件缺失或越界：{relative}")
    # 输出是可再生成的构建产物，默认由根 .gitignore 排除。
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for relative in files:
            archive.write(root / relative, f"apm-crash-analyze/{relative}")
        # 固定生成空配置，不读取磁盘上的用户配置；链接或真实凭据也无法进入 ZIP。
        configuration = zipfile.ZipInfo("apm-crash-analyze/config.local.json")
        configuration.create_system = 3
        configuration.external_attr = 0o100600 << 16
        archive.writestr(configuration, json.dumps({"platformUrl": "", "workerCredential": ""}, indent=2) + "\n")
    return len(files) + 1


def main() -> None:
    """从任意当前目录执行打包，默认定位此脚本所在仓库。"""
    # 目录位置来自脚本自身，不依赖当前工作目录。
    repository = Path(__file__).resolve().parents[1]
    # 可指定输出位置，发布包不自动提交到仓库。
    parser = argparse.ArgumentParser(description="打包 apm-crash-analyze Skill 及 Python 运行时")
    parser.add_argument("--output", type=Path, default=repository / "build/agent-skills/apm-crash-analyze.zip")
    arguments = parser.parse_args()
    # 只报告公开包路径和文件数，不读取私有配置。
    count = package(repository / "agent-skills/apm-crash-analyze", arguments.output)
    print(f"已生成 {arguments.output.resolve()}，包含 {count} 个公开文件。")


if __name__ == "__main__":
    main()
