# 费雾（Android 桌宠初版）

这是一个不依赖账号和服务器的原生 Android 桌宠原型，最低支持 Android 8.0（API 26）。

## 已实现

- “费雾”悬浮桌宠：透明背景、呼吸/翅膀/眨眼动画、拖动、点击互动。
- 长按桌宠直接打开“新建待办”。
- 待办：标题、备注、日期时间、每天/每周重复、完成和删除。
- 笔记：本地保存和删除。
- 提醒：系统通知；桌宠存活时同步显示气泡。
- 手机重启后重新安排所有未完成提醒。
- Android 12+ 精确提醒授权入口、Android 13+ 通知权限入口。
- 红米/小米/POCO 设备识别、自启动管理入口和后台保护提示。

## Android Studio 部署

1. 安装最新版稳定版 Android Studio，首次启动时安装 Android SDK Platform 35 和 Build Tools。
2. 在 Android Studio 选择 **Open**，打开本目录 `D:\Office_File\demo\DesktopPet`。
3. 等待 Gradle Sync 完成。
4. 在红米手机打开“设置 → 我的设备 → 全部参数”，连续点击 OS/MIUI 版本，开启开发者模式。
5. 打开“设置 → 更多设置 → 开发者选项”，启用 **USB 调试** 和 **USB 安装**。
6. USB 连接电脑，在手机弹窗中允许调试；Android Studio 顶部选择手机并点击 Run。

也可以执行：

```powershell
.\gradlew.bat assembleDebug
```

APK 输出位置：`app\build\outputs\apk\debug\app-debug.apk`。

## 推荐的日常开发方式：无线直装

Android 11 及以上可以通过 Wi-Fi 直接调试，不需要反复传 APK：

1. 手机和电脑连接同一个 Wi-Fi。
2. 红米手机进入“开发者选项”，开启“无线调试”。
3. Android Studio 的设备菜单选择 **Pair Devices Using Wi-Fi**。
4. 用二维码或手机显示的六位配对码完成一次配对。
5. 以后改完代码，直接点击 Android Studio 的 Run；它会自动编译、覆盖安装并启动。

配对成功后，也可以直接双击项目根目录的 `一键部署到手机.bat`。脚本会自动：

- 编译 Debug APK；
- 覆盖安装并保留当前待办、笔记和权限；
- 启动手机上的“费雾”。

如果已经手动编译完成，可以在 PowerShell 中跳过构建：

```powershell
.\deploy-phone.ps1 -SkipBuild
```

普通方法体修改可使用 Android Studio 的 **Apply Code Changes**；资源和界面修改可使用 **Apply Changes and Restart Activity**。修改清单、添加类字段或修改服务声明时，仍需点 Run 重新部署。

## 红米/HyperOS 首次体验设置

1. 打开“费雾”，点“允许通知”。
2. 点“开启桌宠”，在系统页面允许“显示在其他应用上层”，返回后再点一次。
3. 点“红米/小米自启动管理”，打开“费雾”的自启动。
4. 在应用详情的“省电策略”中选择“不限制”。
5. 如需尽量准点提醒，点“允许精确提醒”并开启。

如果执行系统的“强行停止”，安卓会暂停该应用的桌宠和提醒；重新打开应用后恢复。

## 说明

- 数据保存在应用本地 SQLite 数据库中，卸载应用会清除。
- 未授予精确提醒权限时，系统仍会提醒，但在深度省电状态下可能有几分钟延迟。
- 不同厂商会自行限制后台进程；即使桌宠服务被清理，已安排的提醒仍独立工作。
