# 费雾模型中转服务

该服务运行在电脑上。安卓端只请求本服务；第三方模型 API Key 由本地管理网页维护，加密保存在 `server/data/provider.json`，不会进入 APK、Git 或手机日志。

## 目录职责

```text
server/
├── .env.example                 # 可提交的配置模板，不包含真实密钥
├── pyproject.toml               # Python 依赖、命令和检查工具
├── setup.ps1                    # 首次生成三个彼此独立的安全令牌
├── start.ps1                    # Windows 启动入口
├── src/feiwu_bridge/
│   ├── config.py                # 环境配置与启动校验
│   ├── schemas.py               # 手机请求和响应的数据边界
│   ├── security.py              # 手机访问令牌与限流
│   ├── provider.py              # 第三方 OpenAI 兼容 API 客户端
│   ├── store.py                 # 加密保存可动态切换的模型配置
│   ├── assistant.py             # 提示词、上下文和结构化结果
│   ├── main.py                  # HTTP 路由与应用生命周期
│   └── web/                     # 本地模型管理页面
└── tests/                       # 不调用真实 API 的自动化测试
```

## 首次配置

需要 Python 3.11+ 和 [uv](https://docs.astral.sh/uv/)。在 `server` 目录执行：

```powershell
.\setup.ps1
```

脚本会生成三个用途不同的值：

- `FEIWU_CLIENT_TOKEN`：安卓访问中转服务。
- `FEIWU_ADMIN_TOKEN`：登录本地管理网页。
- `FEIWU_CONFIG_ENCRYPTION_KEY`：加密电脑上保存的第三方 API Key。

DeepSeek 当前使用 OpenAI 兼容的 `https://api.deepseek.com/chat/completions`。模型名称可能随服务升级变化，因此始终以配置文件和官方文档为准，不写死在业务代码里。

真实的 `.env` 和 `server/data/` 已被 `.gitignore` 排除。不要把这些文件或其中的值发给他人，也不要写入源码。

## 启动

```powershell
.\start.ps1
```

启动后打开 [http://127.0.0.1:8765/admin](http://127.0.0.1:8765/admin)，使用 `FEIWU_ADMIN_TOKEN` 登录。页面支持：

- 在 DeepSeek 与其他 OpenAI 兼容服务之间切换。
- 图形化修改 API 地址、对话路径和模型名。
- 更新或清除第三方 API Key；页面不会回显已经保存的 Key。
- 保存后立即生效，无需修改代码或重启服务。
- 发起一次真实的模型连接测试。

管理 API 默认只接受本机回环地址访问，即使局域网内其他设备拿到了管理令牌也不能修改供应商配置。不要把 `FEIWU_ADMIN_ALLOW_REMOTE` 改为 `true`，除非已经为服务配置了 HTTPS 和额外的网络访问控制。

服务默认监听 `0.0.0.0:8765`。健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```

在 Windows 防火墙中只允许 TCP 8765 通过“专用网络”，不要向公网开放。然后在安卓应用中填写：

- 服务地址：`http://电脑局域网IP:8765`
- 访问令牌：与 `FEIWU_CLIENT_TOKEN` 相同

注意：手机访问令牌、网页管理令牌和 DeepSeek API Key 是三个不同的秘密，不能混用。Windows 防火墙只允许 TCP 8765 通过“专用网络”，不要向公网开放。

## 验证

```powershell
uv sync --extra dev
uv run pytest
uv run ruff check .
```

测试使用模拟 HTTP，不消耗第三方额度，也不会读取真实 API Key。

