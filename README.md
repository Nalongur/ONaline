# ONaline

ONaline 是一款面向两个人的 Android 私密协作画布。与传统聊天列表不同，双方在同一个空间里自由放置、编辑和整理文字块。每位已信任联系人对应一个独立空间，适合共同记录想法、计划和片段。

> **项目状态：开发中的原型（Android 0.7.1）。** 代码开放供阅读、构建和协作；尚未发布经过正式签名和完整设备验收的安装包。请勿将当前构建视为已完成安全审计的通信产品。

## 主要功能

- **双人画布**：文字块可自由定位、重叠、拖动、缩放、合并与撤销删除；支持手绘虚线框。
- **可信联系人**：通过一次性二维码或配对码建立联系，核对安全码后可再次发起连接；支持本地备注、置顶、排序和移除。
- **实时协作与离线编辑**：WebSocket Relay 转发加密事件；断线期间的操作进入本地队列，重连后继续同步。文字使用字符操作 CRDT 处理并发修改。
- **隐私保护**：Android Keystore 保存设备身份；空间事件采用 AES-256-GCM 端到端加密。Relay 只保存和转发加密信封，不接收空间密钥或画布明文。
- **本地与导出**：支持加密 `.pcanvas` 归档、PDF/PNG 导出、只读恢复预览以及设备凭据确认。提供应用锁、截图保护、简化后台通知和本地数据管理。
- **界面设置**：中文/英文、主题、高对比度、减少动态效果及文字大小调整。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | Kotlin、Jetpack Compose Android 应用与测试 |
| `relay-server/` | Node.js WebSocket Relay 与自动化测试 |
| `design/app-interaction-logic-tree-v1.2.md` | 产品交互逻辑与状态设计 |

## 本地运行

需要 JDK 17、Android SDK，以及 Node.js 和 npm。Android 最低版本为 API 26。Windows PowerShell 示例：

```powershell
cd relay-server
npm.cmd ci
npm.cmd start
```

另开终端，在项目根目录构建并安装调试版：

```powershell
.\gradlew.bat :app:assembleDebug
# 将 app/build/outputs/apk/debug/app-debug.apk 安装到 Android 设备或模拟器
```

Android 模拟器默认连接 `ws://10.0.2.2:9876/v1/ws`。真机需要在应用设置里填写可访问的 Relay 地址；生产环境应部署有有效 TLS 证书的 `wss://` 服务，并规划持久化数据的管理。调试构建允许明文 WebSocket，正式构建要求 WSS。Relay 的部署说明见 [relay-server/README.md](relay-server/README.md)。

## 验证与限制

```powershell
.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug assembleRelease
cd relay-server
npm.cmd test
```

截至 2026 年 9 月，开发记录显示单元测试、Relay 测试及双模拟器的配对、实时输入、断线重连与部分画布操作已经验证。完整设备矩阵、长时间协作回归、正式签名发布和独立安全审计仍待完成。`assembleRelease` 产生的未签名 APK 不是可直接分发的正式版本。

## 参与项目

欢迎通过 GitHub Issues 反馈可复现的问题或提出改进建议。报告问题时请提供设备型号、Android 版本、复现步骤和预期/实际结果；请勿上传配对码、空间密钥、私人画布或 Relay 数据。
