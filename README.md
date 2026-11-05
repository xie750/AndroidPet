# 费雾（Android 桌面宠物）

“费雾”是一个原生 Android 桌面宠物应用。它提供悬浮桌宠、形象切换、待办与笔记、系统提醒，以及完全离线的中文语音控制；核心功能不依赖账号或远程服务器，也可按需连接用户自己的模型服务。

## 功能特性

- 悬浮桌宠：透明背景、局部抚摸与戳碰、带惯性的拖动和落地反馈，以及长按快捷新建待办。
- 多套形象：内置 Q 版人物、雾团精灵、像素雾狐、手绘雾猫、Live2D 风格人物和 3D 潮玩形象。
- 官方形象：支持六套内置形象即时切换和桌宠大小调整，不开放用户自定义导入。
- 状态动画：雾团精灵具有被摸、被戳、说话和蜷睡专属姿态；同时支持观察、拖动、落地、监听、思考、提醒和完成庆祝等状态。
- 音乐陪伴：获得系统音乐识别权限后，播放音乐会自动展开黑胶唱机场景，响应播放、暂停、切歌与停止状态。
- 自主生命感：减少机械循环晃动，闲置时会偶尔观察周围，较长时间无人互动后会自然蜷睡。
- 待办与笔记：本地创建、完成、删除和持久化保存。
- 系统提醒：支持指定时间、每天重复、每周重复和重启后恢复提醒。
- 离线语音：桌宠开启后自动监听；说出“你好费雾”后，可创建待办、保存笔记、查询今日待办或进行简单对话；每次回复结束后有 12 秒连续对话时间，无需重复唤醒。
- 自然音色：可选择自然、温柔、活泼、纯离线或系统默认风格；不同风格优先选择不同的已安装中文 TTS 声线，并用明显的语速和音高差异保障可辨识度。
- 可选模型增强：本地规则无法理解时，可连接同一局域网内的 Ollama/OpenAI 兼容服务，让表达方式更自由。
- 厂商适配：提供小米、红米和 POCO 的自启动与后台保护入口。

## 技术栈与运行要求

| 项目 | 说明 |
| --- | --- |
| 开发语言 | Java |
| UI | Android 原生 View，界面由代码构建 |
| 构建系统 | Gradle 8.11.1 / Android Gradle Plugin 8.9.2 |
| 编译 SDK | Android API 35 |
| 最低版本 | Android 8.0（API 26） |
| 本地数据 | SQLite |
| 离线语音 | Vosk 中文模型 |
| 主要服务 | 悬浮窗前台服务、麦克风前台服务、AlarmManager |

## 项目目录

项目采用标准 Android/Gradle 目录，并在应用包内按功能分层：

```text
DesktopPet/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/desktoppet/
│   │   │   ├── data/                 # SQLite 数据访问
│   │   │   ├── model/                # 领域数据模型
│   │   │   ├── feature/
│   │   │   │   ├── pet/              # 桌宠视图、形象和悬浮服务
│   │   │   │   ├── reminder/         # 系统提醒调度与接收
│   │   │   │   └── voice/            # 离线识别与语音指令处理
│   │   │   ├── receiver/              # 系统广播接收器
│   │   │   └── ui/                    # Activity 和应用界面
│   │   ├── assets/model-cn/           # Vosk 中文离线语音模型
│   │   ├── res/
│   │   │   ├── drawable/              # 图标与矢量资源
│   │   │   ├── drawable-nodpi/        # 不随屏幕密度缩放的桌宠形象
│   │   │   └── values/                # 主题等公共资源
│   │   └── AndroidManifest.xml
│   └── build.gradle                   # 应用模块配置与依赖
├── server/                            # 电脑端模型中转服务与管理网页
│   ├── src/feiwu_bridge/              # API、加密配置、模型客户端和 Web UI
│   ├── tests/                         # 不消耗第三方额度的服务端测试
│   ├── setup.ps1 / start.ps1          # 首次配置与启动入口
│   └── pyproject.toml                 # Python 依赖和工具配置
├── artifacts/                         # 本地 APK/AAB 归档，不提交产物
├── gradle/wrapper/                    # Gradle Wrapper
├── scripts/windows/                   # Windows 编译、安装和启动脚本
├── .gitignore                         # Git 排除规则
├── build.gradle                       # 项目级插件配置
├── gradle.properties                  # 项目级 Gradle 参数
├── settings.gradle                    # 仓库与模块声明
├── gradlew / gradlew.bat              # 跨平台 Gradle 入口
└── README.md
```

### 代码层职责

- `data`：数据库结构及增删改查，不负责界面展示。
- `model`：在数据、业务和 UI 之间传递的领域对象。
- `feature/pet`：桌宠渲染、形象目录、交互状态和悬浮窗生命周期。
- `feature/reminder`：提醒的创建、取消、通知展示和重复调度。
- `feature/voice`：离线模型加载、语音转文字、唤醒与指令解析。
- `receiver`：处理开机完成和应用更新等 Android 系统事件。
- `ui`：页面布局、用户输入和各功能入口的协调。
- `server`：在电脑上安全保存第三方模型配置，为安卓提供稳定的智能对话协议和图形化管理页面。

应用 ID 和根命名空间仍为 `com.example.desktoppet`；分包只改善代码组织，不会改变已安装应用的身份或本地数据位置。

## 开始开发

### 1. 准备环境

安装最新版稳定版 Android Studio，并通过 SDK Manager 安装：

- Android SDK Platform 35
- Android SDK Build-Tools
- Android SDK Platform-Tools
- JDK 17（Android Studio 自带版本即可）

### 2. 打开项目

在 Android Studio 中选择 **Open**，打开仓库根目录，等待 Gradle Sync 完成。

首次同步时，Gradle 会下载构建工具及依赖。Android Studio 会在根目录生成只属于当前电脑的 `local.properties`，该文件已加入 `.gitignore`。

### 3. 编译 Debug 版本

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

macOS 或 Linux：

```bash
./gradlew assembleDebug
```

默认输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如需长期保存某个安装包，可复制到 `artifacts/apk/`。该目录和所有 APK/AAB 文件均不会进入 Git。

## 安装到手机

### Android Studio

1. 在手机“开发者选项”中开启 USB 调试，或在 Android 11+ 开启无线调试。
2. 将手机连接到电脑，并在手机上允许调试。
3. 在 Android Studio 顶部选择设备，点击 **Run**。

### Windows 一键部署

完成 ADB 连接后，可双击：

```text
scripts/windows/一键部署到手机.bat
```

脚本会自动编译 Debug APK、覆盖安装并启动应用，同时保留应用数据和已经授予的权限。

也可以在 PowerShell 中运行：

```powershell
.\scripts\windows\deploy-phone.ps1
```

若 APK 已经编译完成，可跳过构建：

```powershell
.\scripts\windows\deploy-phone.ps1 -SkipBuild
```

## 首次使用与权限

1. 打开“费雾”，允许通知权限。
2. 点击“开启桌宠”，允许“显示在其他应用上层”。
3. 首次开启桌宠时允许麦克风权限；之后桌宠启动会自动启动语音监听，仍可在主页面或通知栏随时关闭。
4. 如需尽量准时提醒，允许精确闹钟权限。
5. 如需音乐陪伴，在“伙伴 → 音乐陪伴”中开启系统音乐识别权限。
6. 小米、红米或 POCO 用户建议开启自启动，并将省电策略设为“不限制”。

如果手动执行系统的“强行停止”，Android 会暂停桌宠和提醒；重新打开应用后恢复。

## 离线语音控制

语音模型位于 `app/src/main/assets/model-cn/`，会被打包进 APK。它会显著增加安装包体积，但识别过程不上传录音，也不依赖系统是否预装语音识别服务。

模型加载完成并出现“离线语音已就绪”后，可尝试：

- “你好费雾，提醒我明天下午三点交水费。”
- “你好费雾，提醒我十分钟后喝水。”
- “你好费雾，记一下周末买猫粮。”
- “你好费雾，今天有什么待办？”
- “你好费雾，你会做什么？”
- “你好费雾，现在几点了？”

主页面的“回复音色”可以切换声音风格。实际可用声线由手机安装的 TTS 引擎决定；“自然”会优先选择质量较高的中文声线，可能需要网络，“纯离线”只选择无需联网的声线。若手机只有一个中文合成声线，各风格主要表现为语速和音高变化。

首次说出“你好费雾”后，费雾会在每次回复播放结束时重新开启 12 秒追问窗口。窗口内可以直接继续提问，不必重复唤醒词；说“谢谢”“不用了”“结束对话”或“再见”会立即结束连续对话，之后仍会在后台等待下一次唤醒。

### 智能对话服务

待办、笔记和提醒先由手机本地规则处理；普通提问、知识问答、建议和闲聊会自动交给模型回答。模型还能保留当前语音服务内最近四轮对话，让追问更连贯。模型返回的操作仍由手机本地代码校验和执行。

项目现在包含独立的电脑端中转服务，位于 `server/`。第三方 API Key 只保存在电脑端，安卓只保存电脑服务地址和独立的访问令牌。模型服务商、接口地址、模型名称和 API Key 都可以在浏览器管理页面中切换，无需修改代码。

电脑端首次运行：

```powershell
Set-Location server
.\setup.ps1
.\start.ps1
```

随后打开 `http://127.0.0.1:8765/admin` 配置 DeepSeek 或其他 OpenAI 兼容模型。详细说明见 `server/README.md`。

安卓端填写：

- 服务地址：`http://电脑局域网IP:8765`
- 访问令牌：电脑 `server/.env` 中的 `FEIWU_CLIENT_TOKEN`

手机中的 `localhost` / `127.0.0.1` 指手机自己，不能用来访问电脑。手机和电脑需要处于可互相访问的网络中；当前局域网 HTTP 方案适合个人开发和可信家庭网络，不要将端口暴露到公网。

## 数据与隐私

- 待办和笔记保存在应用私有 SQLite 数据库中。
- 语音识别在设备本地完成，不需要账号或业务服务器。
- 卸载应用会清除本地数据库和应用设置。
- 音乐陪伴只在本机读取系统媒体会话的播放状态与歌曲信息，不保存其他通知内容。

## Git 提交规范

以下内容不应提交：

- `local.properties`、`.env` 等本机或私有配置。
- `.idea/`、`*.iml` 等 IDE 状态。
- `.gradle/`、`build/`、`app/build/` 等缓存和构建目录。
- APK、AAB、签名文件、证书和密钥。
- 日志、临时文件、TXT 输出、本地数据库和性能分析文件。

以下配置是项目可复现构建所必需的，应继续提交：

- `build.gradle`、`settings.gradle`、`gradle.properties`。
- `gradle/wrapper/` 和 Gradle Wrapper 脚本。
- `AndroidManifest.xml`、资源文件及运行时使用的模型配置。

## 常见问题

### 找不到 ADB

在 Android Studio 的 SDK Manager 中安装 Platform-Tools，或把 `platform-tools` 加入系统 `PATH`。Windows 部署脚本也会读取 `local.properties` 中的 `sdk.dir`。

### 桌宠或语音服务被系统清理

允许通知和悬浮窗权限；在厂商系统中开启自启动并关闭针对本应用的电池优化。系统“强行停止”后必须手动重新打开应用。

### 提醒出现延迟

确认已授予精确闹钟权限。未授权时仍会安排提醒，但在深度省电状态下可能延迟数分钟。

### APK 体积较大

主要体积来自内置 Vosk 中文离线模型和多套 PNG 形象。这是完全离线语音能力带来的预期结果。
