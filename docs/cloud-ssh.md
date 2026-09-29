# 云服务器 SSH 连接说明

连接授权以[仓库协作规则](../AGENTS.md#云服务器访问)为准。以下参数与历史记录从根规则迁入；2026-09-29 已核对服务器主机指纹，并用新 Mac 客户端密钥完成免交互 SSH 登录。

## 连接参数

- 地址：`124.221.252.121`，端口：`22`，用户：`ubuntu`。
- 历史 Windows 私钥路径：`C:\Users\shanshui\.ssh\apm_cloud`。macOS 部署脚本默认查找 `$HOME/.ssh/apm_cloud`；当前新私钥路径为 `$HOME/.ssh/apm_cloud_mac`，部署时需传 `--identity-file "$HOME/.ssh/apm_cloud_mac"`。私钥不得复制到仓库或输出其内容。
- ED25519 主机指纹：`SHA256:1lXqfjFvy8p/wDN7mHfRBZRH0wN5u2hecNjZ990nnfs`。
- 历史 Windows 客户端公钥指纹：`SHA256:Ravp2ttYoJzC3yicVwT4LbqwL0DIsuci5ddMtaOV5T0`；新 Mac 客户端公钥指纹：`SHA256:3mtRw2DwaVo87FGjdcC3edaiLAapfICrX+QwziiWM1o`。
- 2026-09-24 当前 macOS 未查到该主机的 `known_hosts` 记录；首次连接前需通过可信渠道核对上述主机指纹，再写入 `~/.ssh/known_hosts`。部署脚本保持严格主机密钥校验，指纹不匹配时停止并核实原因。
- 2026-09-29 本机未找到旧私钥，SSH Agent 也没有身份。通过 `ssh-keyscan` 取得的服务器 ED25519 公钥指纹与上述已记录指纹完全一致后，已将该公钥加入本机 `known_hosts`，目录权限为 `700`、文件权限为 `600`。随后新建 `$HOME/.ssh/apm_cloud_mac`，没有覆盖旧文件；用户通过腾讯云轻量应用服务器 OrcaTerm 将新公钥追加到 `ubuntu` 的 `authorized_keys`。本机使用严格主机密钥校验和该新私钥执行免交互 SSH，返回 `ubuntu`、`VM-0-4-ubuntu` 和 `/home/ubuntu`；云端 `sudo -n docker compose ps` 也已通过。

已配置对应私钥的 macOS 可用以下命令验证连接：

```bash
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -i "$HOME/.ssh/apm_cloud_mac" ubuntu@124.221.252.121 'id; hostname'
```

2026-09-29 使用新 Mac 密钥再次验证免交互登录成功，远程用户为 `ubuntu`，主机名为 `VM-0-4-ubuntu`；`sudo -n docker compose ps` 成功。部署及接口冒烟结果见[Agent 查询链路验收记录](agent-query-e2e-validation.md#2026-09-29-云端部署与入口冒烟)。更换本机环境后，先检查私钥是否存在和主机信任记录是否已配置，不要自动覆盖或重新生成现有密钥。
