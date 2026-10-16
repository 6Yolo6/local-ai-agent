> 历史集成记录：下文环境来自旧电脑，不能作为当前本机配置直接执行。本机以 [local-setup.md](local-setup.md) 和 [../README.md](../README.md) 为准，本阶段未启用机器人。

# OpenClaw QQ Bot Runbook

本文记录本机 OpenClaw + QQ Bot 的已验证运行方式。

## 当前固定路径

- 项目目录：`D:\Dev\project\local-ai-agent`
- OpenClaw 目录：`D:\OpenClaw\openclaw-cn`
- OpenClaw 配置：`C:\Users\20317.YOLO\.openclaw\openclaw.json`
- OpenClaw Home：`C:\Users\20317.YOLO`
- llama.cpp API：`http://127.0.0.1:8080/v1/models`
- OpenClaw gateway：`ws://127.0.0.1:18789`
- Browser control：`http://127.0.0.1:18791`

## 已验证版本

- OpenClaw：`2026.2.23-cn`
- QQ Bot 插件：`@tencent-connect/openclaw-qqbot@1.6.5`

注意：当前 OpenClaw 版本已验证可用的是 QQ Bot 插件 `1.6.5`。不要直接升级到 `1.7.2`，该版本会触发插件 SDK 子路径兼容问题。

## 启动

在项目目录执行：

```powershell
scripts\start-openclaw-qqbot.ps1
```

或双击/执行：

```bat
scripts\start-openclaw-qqbot.bat
```

脚本会自动设置：

- `OPENCLAW_HOME=C:\Users\20317.YOLO`
- `TEMP=D:\OpenClaw\openclaw-cn\.tmp`
- `TMP=D:\OpenClaw\openclaw-cn\.tmp`

日志位置：

- `D:\OpenClaw\openclaw-cn\.tmp\gateway-run.out.log`
- `D:\OpenClaw\openclaw-cn\.tmp\gateway-run.err.log`
- `D:\OpenClaw\openclaw-cn\.tmp\openclaw\openclaw-YYYY-MM-DD.log`

## 检查

```powershell
scripts\check-openclaw-qqbot.ps1
```

检查项包括：

- llama.cpp `/v1/models`
- OpenClaw gateway 端口 `18789`
- OpenClaw 主配置摘要
- QQ Bot 插件版本
- 最近 gateway 日志中的关键错误

## 停止

```powershell
scripts\stop-openclaw-gateway.ps1
```

脚本只停止 `openclaw.mjs gateway run` 相关进程，以及占用 `18789` 的 gateway 监听进程。

## QQ 频道测试步骤

在已授权机器人的频道或私聊里依次发送：

```text
/bot-ping
```

```text
/bot-version
```

然后发送一条普通 AI 测试消息：

```text
用一句话介绍你自己
```

如果前两条能回，普通消息不回，再查看 `check-openclaw-qqbot.ps1` 输出里的模型/API 错误。

## 当前已知问题：QQ 凭证错误

如果日志出现：

```text
invalid appid or secret
```

说明本地网关、插件、llama.cpp 基本都已启动，但腾讯开放平台拒绝了当前 `AppID:AppSecret`。

处理方式：

1. 打开 QQ 开放平台的机器人配置页。
2. 找到当前机器人对应的 `AppID` 和 `AppSecret`。
3. 如果看不到原 AppSecret，需要重新生成/重置，并立即复制保存。
4. 回到 `D:\OpenClaw\openclaw-cn`，用新凭证重新绑定：

```powershell
$env:OPENCLAW_HOME='C:\Users\20317.YOLO'
node openclaw.mjs channels add --channel qqbot --token "AppID:AppSecret"
```

5. 重启 gateway：

```powershell
D:\Dev\project\local-ai-agent\scripts\stop-openclaw-gateway.ps1
D:\Dev\project\local-ai-agent\scripts\start-openclaw-qqbot.ps1
```

重新检查：

```powershell
D:\Dev\project\local-ai-agent\scripts\check-openclaw-qqbot.ps1
```

成功后日志应不再出现 `invalid appid or secret`，并应能看到 QQ Bot gateway ready / connected 相关信息。
