param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$packageName = 'com.example.desktoppet'
$mainActivity = "$packageName/.MainActivity"
$apkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $sdkRoots = @()
    if ($env:ANDROID_HOME) { $sdkRoots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }

    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -like 'sdk.dir=*' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkRoots += $sdkLine.Substring('sdk.dir='.Length).Trim().Replace('\:', ':')
        }
    }

    $sdkRoots += 'C:\Users\lenovo\AppData\Local\Android\Sdk'
    foreach ($sdkRoot in $sdkRoots | Select-Object -Unique) {
        $candidate = Join-Path $sdkRoot 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw '没有找到 adb。请先在 Android Studio 的 SDK Manager 中安装 Android SDK Platform-Tools。'
}

$adb = Resolve-Adb
$deviceLines = & $adb devices | Select-Object -Skip 1
$serials = @()
foreach ($line in $deviceLines) {
    if ($line -match '^([^\s]+)\s+device$') { $serials += $Matches[1] }
}

if ($serials.Count -eq 0) {
    throw @'
没有检测到已连接的手机。
请在红米的“开发者选项”中开启“无线调试”，然后在 Android Studio 选择“Pair Devices Using Wi-Fi”完成一次配对。
手机和电脑需要连接同一个 Wi-Fi。
'@
}
if ($serials.Count -gt 1) {
    throw "检测到多个设备：$($serials -join ', ')。请暂时断开其他设备后重试。"
}
$serial = $serials[0]
Write-Host "已连接手机：$serial" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Write-Host '正在编译 Debug APK…' -ForegroundColor Cyan
    & (Join-Path $projectRoot 'gradlew.bat') assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败，退出码：$LASTEXITCODE" }
}

if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "没有找到 APK：$apkPath"
}

Write-Host '正在覆盖安装到手机（保留应用数据和已授予权限）…' -ForegroundColor Cyan
& $adb -s $serial install -r -t $apkPath
if ($LASTEXITCODE -ne 0) { throw "APK 安装失败，退出码：$LASTEXITCODE" }

Write-Host '正在启动费雾…' -ForegroundColor Cyan
& $adb -s $serial shell am start -S -n $mainActivity
if ($LASTEXITCODE -ne 0) { throw "应用启动失败，退出码：$LASTEXITCODE" }

Write-Host '完成：新版本已经安装并在手机上打开。' -ForegroundColor Green
