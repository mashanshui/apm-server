# 云服务器 SSH 连接说明

连接授权以[仓库协作规则](../AGENTS.md#云服务器访问)为准。以下参数与历史记录从根规则迁入，本次未重新连接验证。

## 连接参数

- 地址：`124.221.252.121`，端口：`22`，用户：`ubuntu`。
- 本机私钥路径：`C:\Users\shanshui\.ssh\apm_cloud`（PowerShell 中使用 `$env:USERPROFILE\.ssh\apm_cloud`）。私钥仅保存在本机，不得复制到仓库或输出其内容。
- ED25519 主机指纹：`SHA256:1lXqfjFvy8p/wDN7mHfRBZRH0wN5u2hecNjZ990nnfs`。
- 客户端公钥指纹：`SHA256:Ravp2ttYoJzC3yicVwT4LbqwL0DIsuci5ddMtaOV5T0`。
- 主机密钥已保存在本机 `~/.ssh/known_hosts`；必须保持主机密钥校验，指纹不匹配时停止连接并核实原因。

从本机 PowerShell 执行远程命令的示例：

```powershell
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o IdentitiesOnly=yes -i "$env:USERPROFILE\.ssh\apm_cloud" ubuntu@124.221.252.121 "id; hostname"
```

2026-09-12 已验证免交互密钥登录成功，远程用户为 `ubuntu`，主机名为 `VM-0-4-ubuntu`。该验证仅覆盖 SSH 登录及基本系统信息读取，不代表项目已经部署或服务已验收；`sudo` 能力尚未验证。更换本机环境后，先检查私钥是否存在和主机信任记录是否已配置，不要自动覆盖或重新生成现有密钥。
