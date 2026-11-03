# 本地构建产物

此目录用于在本机归档 APK、AAB 等发布文件，目录内容默认不会提交到 Git。

建议按类型或版本继续分目录，例如：

```text
artifacts/
├── apk/
│   ├── FeiWu-v0.3.0-debug.apk
│   └── FeiWu-v0.3.0-release.apk
└── bundle/
    └── FeiWu-v0.3.0-release.aab
```

Gradle 的默认 Debug APK 仍输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```
