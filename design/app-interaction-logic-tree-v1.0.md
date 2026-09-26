# Private Canvas App 交互逻辑树 v1.0

> 状态：设计基线  
> 日期：2026-09-01  
> 适用范围：Android 双人私密实时协作文字画布  
> UI 语言：全部使用 English  
> 关联视觉稿：private-canvas-ui-concept-v2-shared-blocks.png

## 1. 文档目标

这份文档是后续产品设计、Jetpack Compose 界面、客户端状态管理、WebSocket 协议和测试用例的共同基线。任何交互变化都应先更新本文档版本，再进入实现。

本产品不是消息列表，也不是把聊天气泡放到自由画布上。产品中的核心对象是 Shared Text Block：两个人共同拥有、共同编辑、共同移动和共同整理的空间文字块。

## 2. V1 产品边界

### 2.1 必须具备

- 一个 Space 固定连接两个人。
- 两个人可以同时在线并操作同一 Canvas。
- 两个人可以在同一个 Shared Text Block 内同时输入。
- 支持普通文字、系统 Emoji、换行、选择、复制和粘贴。
- 支持创建、移动、缩放、合并和删除 Shared Text Block。
- 支持 Live Input 与 Hidden Input。
- 支持断线、本地排队、重连和差异同步。
- 聊天内容端到端加密，服务器只保存密文。
- 支持 Encrypted Archive、PDF 和 Image 导出。
- 支持多套主题色，但双方身份光标颜色保持稳定。

### 2.2 V1 不包含

- 群聊或第三位参与者。
- 联系人列表、公开账号搜索、朋友圈或动态。
- 传统消息气泡和已读回执。
- 图片、语音、视频、文件传输。
- 多 Space 列表。
- 公开分享链接。
- 云端明文搜索、内容分析或推荐。
- 广告、行为分析和聊天内容遥测。

## 3. 核心名词

| 名词 | 定义 |
|---|---|
| Space | 只属于两个人的加密空间，也是 V1 唯一会话 |
| Canvas | 无限二维坐标画布，承载所有 Shared Text Block |
| Shared Text Block | 双方共同拥有和编辑的文字对象，不归属于任何单独发送者 |
| Live Cursor | 显示参与者实时编辑位置的临时光标 |
| Live Input | 已由输入法提交的内容立即同步给对方 |
| Hidden Input | 内容先只在本机编辑，确认 Reveal 后原子写入当前 Block |
| Viewport | 当前用户看到的画布坐标范围和缩放比例 |
| Persistent Event | 必须进入加密事件日志并可重放的操作 |
| Ephemeral Event | 只用于实时呈现、不长期保存的光标、视野和输入状态 |
| Local Preference | 仅影响当前设备、不与对方同步的主题、字号等设置 |

## 4. 不可破坏的产品原则

1. Shared Text Block 没有“发送者标签”，不显示 You、Me、Partner。
2. 两个人的最终文字统一使用正文色，身份只通过正在发生的光标和选区短暂表达。
3. Canvas 不自动跟随对方移动，避免抢夺用户视野。
4. 所有高风险操作必须可撤销、二次确认或需要双方批准。
5. 离线时优先保护内容，不为了保持表面一致而丢弃本地输入。
6. Server URL、部署地点和网络方式可迁移，不写死在 App 中。
7. 服务端永远不获得聊天明文和导出明文。
8. App 的所有可见界面文案统一为 English；聊天内容允许任意语言。

## 5. 全局逻辑树

~~~mermaid
flowchart TD
    A[App Launch] --> B{Local database available?}
    B -- No --> B1[Recovery Mode]
    B -- Yes --> C{Identity keys available?}
    C -- No --> C1[First Run]
    C -- Yes --> D{App lock enabled?}
    D -- Yes --> D1[Unlock]
    D -- No --> E
    D1 -->|Success| E{Paired Space exists?}
    D1 -->|Failed| D2[Remain Locked]
    E -- No --> F[Create or Join Private Space]
    E -- Yes --> G[Connect and Sync]
    F --> F1[Create Space]
    F --> F2[Join with Invite]
    F1 --> F3[Show QR and One-time Code]
    F2 --> F4[Verify Invitation]
    F3 --> F5[Pair Second Device]
    F4 --> F5
    F5 --> F6[Compare Safety Code]
    F6 --> G
    G --> H{Connection result}
    H -- Online --> I[Canvas Online]
    H -- Offline --> J[Canvas Offline]
    H -- Key mismatch --> K[Security Block]
    I --> L[Shared Block interactions]
    J --> L
    L --> M[Create / Edit / Move / Resize]
    L --> N[Merge / Delete]
    L --> O[Emoji / Hidden Input]
    L --> P[Locate / Follow / Overview]
    I --> Q[Export / Settings / Devices]
    J --> Q
~~~

## 6. App 路由与页面树

~~~mermaid
mindmap
  root((Private Canvas))
    Launch
      Splash
      Unlock
      Recovery Mode
    Pairing
      Create Space
      Join Space
      QR Scanner
      Invite Code
      Safety Code
    Canvas
      Infinite Viewport
      Shared Blocks
      Live Cursors
      Tool Dock
      Partner Presence
      Overview
    Block Actions
      Edit
      Move
      Resize
      Merge
      Pin
      Delete
    Input
      Live Input
      Hidden Input
      Emoji
      Clipboard
      Undo Redo
    Export
      Encrypted Archive
      PDF
      Image
    Settings
      Appearance
      Privacy
      Connection
      Data
      Paired Devices
      About
~~~

## 7. 启动、解锁与恢复

### 7.1 App Launch

1. 显示无内容的短暂 Splash，不显示最后一次聊天预览。
2. 加载本地加密数据库、设备身份和 Space 元数据。
3. 检查数据库版本，必要时执行本地迁移。
4. 检查 App Lock。
5. 进入 Unlock、Pairing、Canvas 或 Recovery Mode。

### 7.2 Unlock

UI：

- 标题：PRIVATE SPACE
- 主操作：UNLOCK
- 备用操作：USE DEVICE PIN

逻辑：

- 首选 Android BiometricPrompt。
- 生物识别取消后允许设备 PIN，不允许自建低强度四位密码。
- 连续失败时由系统控制冷却。
- 解锁前禁止截图、最近任务预览和聊天通知正文。
- App 进入后台超过设定时间后重新锁定。

锁定延迟选项：

- IMMEDIATELY
- AFTER 1 MINUTE
- AFTER 5 MINUTES
- WHEN APP RESTARTS

### 7.3 Recovery Mode

触发条件：

- 数据库无法解密。
- 数据库损坏。
- 密钥存在但 Space 元数据丢失。
- 磁盘空间不足导致未完成写入。

允许操作：

- RETRY
- RESTORE ENCRYPTED ARCHIVE
- EXPORT DIAGNOSTICS
- RESET THIS DEVICE

限制：

- Diagnostics 不得包含聊天明文、密钥、邀请码或完整服务器凭据。
- RESET THIS DEVICE 必须明确说明会删除本机密钥和本地记录。

## 8. 创建与加入 Space

### 8.1 First Run

页面只提供两个主要入口：

- CREATE A PRIVATE SPACE
- JOIN A PRIVATE SPACE

不要求手机号、通讯录或公开用户名。

### 8.2 Create Space

流程：

1. 本机生成设备身份密钥。
2. 创建随机 Space ID 和 Space 加密材料。
3. 向服务器注册密文 Space。
4. 生成一次性 QR 和短邀请码。
5. 显示 INVITE YOUR PERSON。
6. 等待第二台设备完成握手。
7. 双方显示相同 Safety Code。
8. 用户确认 MATCHES 后进入 Canvas。

邀请码规则：

- 单次使用。
- 默认 15 分钟失效。
- 可手动 CANCEL INVITE。
- 邀请码不包含可长期使用的 Space 主密钥。
- 配对完成后邀请码立即失效。

### 8.3 Join Space

入口：

- SCAN QR CODE
- ENTER INVITE CODE

流程：

1. 读取邀请。
2. 检查格式、过期时间和服务器地址。
3. 显示邀请来源和 Space 指纹摘要。
4. 用户点击 JOIN PRIVATE SPACE。
5. 完成设备密钥交换。
6. 显示 Safety Code。
7. 双方确认后同步加密事件。

### 8.4 Safety Code

- 以易读的数字分组和图形组合显示。
- 双方通过线下或其他可信渠道比较。
- 状态分为 UNVERIFIED 与 VERIFIED。
- 未验证仍可使用，但设置页持续显示低调提醒。
- 设备或密钥变化后必须重新验证。

## 9. Canvas 基础交互

### 9.1 Canvas 默认状态

- App 解锁后直接进入唯一 Canvas，不显示会话列表。
- 顶部仅显示 PRIVATE SPACE、连接状态和 Partner Presence。
- 工具坞默认收起，发生操作时浮现。
- 画布背景保持平坦、低对比和大面积留白。
- 亚克力材质只用于 Block、工具坞、底部面板和临时提示。

### 9.2 画布手势

| 手势 | 空白区域 | Shared Block |
|---|---|---|
| 单击 | 取消选择、关闭软键盘 | 进入编辑状态并放置光标 |
| 双击 | 在点击坐标创建新 Block | 选中 Block 并显示操作工具 |
| 单指拖动 | 平移 Canvas | 编辑态中选择文字；对象态中移动 Block |
| 双指缩放 | 缩放 Canvas | 仍然缩放 Canvas |
| 长按 | 打开快捷创建菜单 | 编辑态选择文字；对象态进入移动模式 |
| 返回手势 | 依优先级关闭当前层级 | 同左 |

缩放范围：

- 最小 25%，用于总览。
- 默认 100%。
- 最大 300%，用于精细编辑。

### 9.3 手势冲突优先级

从高到低：

1. Android 系统返回和系统手势区域。
2. 输入法文字选择、光标拖柄和组合输入。
3. 已显示 Bottom Sheet 或 Dialog。
4. Block 对象模式的移动和缩放手柄。
5. Canvas 双指缩放。
6. Canvas 单指平移。
7. 单击、双击和长按识别。

任何时候都不能因 Canvas 拖动而破坏正在进行的文字选择。

### 9.4 Viewport 恢复

- 每台设备独立保存最后 Viewport。
- 重新进入时先恢复本地位置，不强制跳到对方位置。
- 如果对方正在远处编辑，边缘显示一个带方向的 Presence Marker。
- 点击 Marker 平滑移动到对方所在 Block。

## 10. Shared Text Block 生命周期

~~~mermaid
stateDiagram-v2
    [*] --> Creating
    Creating --> Editing: First character committed
    Creating --> Deleted: Empty and focus lost
    Editing --> Idle: Focus lost
    Idle --> Editing: Tap block
    Editing --> Selected: Open object actions
    Idle --> Selected: Double tap
    Selected --> Moving
    Selected --> Resizing
    Selected --> MergePreview
    Moving --> Selected
    Resizing --> Selected
    MergePreview --> Merged: Release over valid target
    MergePreview --> Selected: Cancel
    Selected --> DeletePending
    DeletePending --> Deleted: Confirmed
    DeletePending --> Selected: Cancel
    Merged --> Editing
    Deleted --> Restored: Undo or conflict recovery
    Restored --> Idle
~~~

### 10.1 Creating

- 双击空白或点击工具坞加号创建。
- 新 Block 出现在点击坐标或当前 Viewport 中心。
- 自动获得文本焦点并弹出键盘。
- 未输入任何内容且失焦时自动移除，不产生持久事件。
- 第一个已提交字符产生 BLOCK_CREATED 与 TEXT_OPERATION。

### 10.2 Editing

- Block 不显示作者姓名。
- 双方可在任意位置放置各自光标。
- 两个光标使用固定颜色：
  - Local Cursor：Muted Blue
  - Partner Cursor：Muted Coral
- 光标旁不长期显示姓名，只在首次进入或颜色无法区分时显示短暂标识。
- 当双方同时处于该 Block，顶部出现 2 LIVE。
- 用户输入停止约 1.5 秒后，临时贡献色淡回统一正文色。

### 10.3 Selected

打开对象操作：

- MOVE
- RESIZE
- MERGE
- PIN / UNPIN
- DELETE

进入 Selected 后，键盘关闭，文字选择解除。

### 10.4 Moving

- 长按 Block 外框并拖动。
- 本机以 60Hz 视觉移动。
- 网络位置更新最多每 100ms 合并发送。
- 对方看到平滑插值，不显示每个离散坐标事件。
- PIN 状态下禁止移动，但仍可编辑。

### 10.5 Resizing

- 通过角落手柄改变 Block 最小宽度和最大行宽。
- Block 高度优先随内容自适应。
- 用户可调整宽度，不允许把文字裁切为不可见。
- 最小点击目标符合 Android 可访问性尺寸。

### 10.6 Merge

流程：

1. 进入对象模式并拖动源 Block。
2. 与目标 Block 重叠达到阈值后，目标显示柔和高光。
3. 显示 MERGE PREVIEW。
4. 松开后合并，原有内容以段落边界进入同一个 Block。
5. 两个 Block 的光标、选区和 Emoji 坐标映射到新 Block。
6. 底部显示 UNDO MERGE。

限制：

- 存在未提交的输入法组合内容时延迟合并。
- 存在 Hidden Input 草稿时提示 REVEAL OR DISCARD DRAFT FIRST。
- 离线时禁用 MERGE，避免结构冲突。
- 合并后发生新编辑，原有简单 Undo 失效，改为从 History 恢复副本。

### 10.7 Delete

- 删除有内容的 Block 必须长按确认。
- 对方正在该 Block 编辑时显示 PARTNER IS EDITING，并要求再次确认或等待其离开。
- 删除后显示短时 UNDO。
- 删除是 Tombstone 事件，不立即物理擦除同步所需数据。
- 所有设备确认后按保留策略压缩和清理密文历史。
- 如果删除与对方离线编辑冲突，不丢弃编辑；重连后生成 RECOVERED BLOCK。

## 11. 双人共同编辑

### 11.1 并发模型

- 文本采用适合协同编辑的 CRDT 或等价成熟算法。
- 每个字符或文本操作具有稳定标识，不依赖设备本地时间排序。
- 两人在不同位置输入时独立插入。
- 两人在同一位置输入时使用确定性顺序，双方最终结果必须一致。
- Undo 只撤销当前用户自己的操作，不撤销对方刚刚输入的内容。
- Redo 同样只针对本地操作栈。

### 11.2 实时发送

- 输入法已经 Commit 的文字才可进入 Live Input。
- 英文字符可按 60 至 100ms 批量同步。
- 中文、日文等输入法候选组合不广播候选键序列，只广播已提交结果。
- 光标和选区是 Ephemeral Event，不写入永久聊天记录。
- 稳定文本操作进入加密事件日志。

### 11.3 同一段文字的删除与替换

- 双方共同拥有内容，因此双方都可修改或删除任意文字。
- 对方删除正在选中的文本时，本机选区折叠到最近有效位置。
- 替换操作表现为一个原子事务，避免对方看到短暂空白状态。
- 大范围删除超过阈值时显示轻量确认，防止误操作。

### 11.4 光标碰撞

- 两个光标位于同一字符位置时左右错开 2 至 4dp。
- 两个选区重叠时使用半透明叠色，但必须保持正文可读。
- Partner Cursor 超过 3 秒无操作后降低透明度。
- Partner 离开 Block 后，光标在 300ms 内淡出。

## 12. Live Input 与 Hidden Input

### 12.1 Live Input

默认模式。

- 已提交字符实时进入 Shared Block。
- Partner 能看到逐步形成的文字。
- 当前模式不显示持续标签，只在切换时提示 LIVE INPUT。

### 12.2 Hidden Input

进入方式：

- 点击工具坞 HIDE INPUT。

行为：

1. 在当前 Block 位置创建仅本机可见的 Local Draft Layer。
2. Partner 只看到 HIDDEN INPUT ACTIVE，不看到内容。
3. 草稿仅保存在本机加密数据库，不上传服务器。
4. 用户可以 REVEAL、KEEP DRAFT 或 DISCARD。
5. REVEAL 将草稿作为一个原子操作插入当前 Block。
6. 断线或 App 关闭后草稿仍可恢复。

限制：

- Hidden Draft 存在时不能 Merge 或 Delete 当前 Block。
- 当前 Block 已被对方删除时，Reveal 会创建 RECOVERED BLOCK。
- Hidden Draft 不包含在对方导出的记录中，直到 Reveal。

## 13. Emoji 与剪贴板

### 13.1 Emoji

- V1 的 Emoji 作为文本内容存在，不建立独立反应系统。
- 可使用系统 Emoji 键盘或工具坞快捷入口。
- Emoji 与普通字符一起参与协同编辑、选择、删除和导出。
- 不引入贴纸、动画表情或第三方资源。

### 13.2 Clipboard

- COPY 将明文写入系统剪贴板前显示一次隐私说明。
- 可选 AUTO-CLEAR CLIPBOARD AFTER 60 SECONDS。
- PASTE 支持纯文本和 Emoji。
- V1 忽略富文本格式、图片和文件。
- 超长粘贴显示预览并要求确认。

## 14. Partner Presence 与视野协作

连接状态：

- ONLINE
- CONNECTING
- OFFLINE — SAVED LOCALLY
- SYNCING
- SECURITY CHECK REQUIRED

Presence 行为：

- Online 时顶部显示低调状态点。
- 对方进入某个 Block 时，该 Block 显示淡色 Presence Ring。
- 对方位于屏幕外时显示方向 Marker。
- 点击 Marker 执行 LOCATE PARTNER。
- 长按 Marker 可启用 FOLLOW LIVE。
- Follow 状态下 Viewport 平滑跟随对方；本机手动拖动画布立即退出 Follow。
- 不提供传统 Online Last Seen、Read Receipt 或消息已读状态。

## 15. Tool Dock

默认按钮：

- CREATE BLOCK
- EMOJI
- TEXT
- HIDE INPUT
- OVERVIEW
- MORE

显示规则：

- 点击、编辑或选择 Block 时出现。
- 键盘弹出时停靠在 IME 上方。
- 无操作约 3 秒且无键盘时降低透明度。
- 再次无操作后收起为一个轻量圆形入口。

MORE 包含：

- EXPORT SPACE
- APPEARANCE
- PRIVACY
- CONNECTION
- PAIRED DEVICES

## 16. 返回键与层级关闭规则

Android Back 按以下顺序处理：

1. 关闭系统 Emoji 或输入法附加面板。
2. 关闭 Bottom Sheet 或 Dialog。
3. 退出 Hidden Input，并询问保留草稿。
4. 退出 Block 对象模式。
5. 关闭软键盘并保留编辑位置。
6. 退出 Follow Live。
7. 返回系统桌面，不清空 Space。

## 17. 离线、重连与同步

### 17.1 离线时允许

- 创建新 Block。
- 编辑文字和 Emoji。
- 本地 Undo / Redo。
- 移动和调整新创建且尚未同步的 Block。
- 浏览、缩放和切换本地主题。
- 创建 Encrypted Archive 或明文导出。

### 17.2 离线时限制

- 禁止 Merge 已同步 Block。
- 禁止永久 Delete 已同步 Block。
- 禁止 Clear Space。
- 禁止设备撤销和重新配对。
- 导出审计事件进入待发送队列。

### 17.3 重连流程

~~~mermaid
sequenceDiagram
    participant A as Android App
    participant S as Server
    A->>A: Validate local encrypted event log
    A->>S: Authenticate device and Space
    S-->>A: Last acknowledged event/version
    A->>S: Upload missing encrypted events
    S-->>A: Download remote encrypted events
    A->>A: Decrypt and merge CRDT state
    A->>S: Send state hash and acknowledgements
    S-->>A: Sync complete
    A->>A: Change status to ONLINE
~~~

同步要求：

- 所有事件幂等，可安全重发。
- 客户端保存服务端 Ack 前不得删除本地事件。
- 同步中 Canvas 可读，安全编辑进入新队列。
- 状态差异无法自动解决时进入只读保护模式，而不是覆盖本地数据。

## 18. App 生命周期与通知

### 18.1 Foreground

- 保持 WebSocket。
- 实时发送 Ephemeral 与 Persistent Event。
- 网络切换后自动重连。

### 18.2 Background

- 允许系统关闭 WebSocket。
- 本地未提交内容先持久化。
- 最近任务缩略图默认模糊或阻止捕获。
- 回到前台后先 Sync，再恢复 Live Cursor。

### 18.3 Notifications

默认通知文案：

- New activity in your private space

规则：

- 不显示聊天正文、Block 内容、Emoji 或导出文件名。
- 通知权限被拒绝不影响聊天。
- 局域网/私人穿透原型无法可靠推送时，不伪装成后台实时在线。
- 点击通知先解锁，再定位到发生变化的 Block。

## 19. Export

### 19.1 入口

MORE → EXPORT SPACE

导出前必须通过生物识别或设备凭据确认。

### 19.2 导出范围

- ENTIRE CANVAS
- SELECTED BLOCKS
- TIME RANGE

### 19.3 Encrypted Archive

- 扩展名暂定 .pcanvas。
- 包含 Block、坐标、尺寸、Emoji、事件版本和完整性校验。
- 用户设置独立备份密码。
- 明确提示：忘记密码无法恢复。
- 文件在本机生成，服务器不参与解密或打包。
- 成功后写入加密 Export Audit Event。

### 19.4 PDF

- 按空间区域分页或使用大页面。
- 提供 INCLUDE TIMESTAMPS 开关。
- 默认隐藏设备信息和安全指纹。
- 明确提示 PDF IS NOT ENCRYPTED。

### 19.5 Image

- 支持 ENTIRE CANVAS 与 SELECTED AREA。
- 分辨率过大时自动分片或生成 ZIP。
- 明确提示 IMAGE IS NOT ENCRYPTED。

### 19.6 导出审计

- 任一用户可独立导出。
- Partner 会看到不含文件内容的记录：SPACE EXPORTED LOCALLY。
- 审计只记录时间、导出类型和发起设备，不上传导出文件。

## 20. Import 与恢复

### 20.1 Restore Encrypted Archive

1. 用户通过 Android 文件选择器选择 .pcanvas。
2. 输入备份密码。
3. 本地验证版本、完整性和 Space 标识。
4. 显示恢复预览和 Block 数量。
5. 选择 RESTORE THIS DEVICE。
6. 重建本地数据库。
7. 在线时与服务器执行差异同步。

### 20.2 不允许直接覆盖活跃共享空间

- 外部 Archive 不得直接覆盖当前 Space。
- 不同 Space 的 Archive 以只读 RECOVERED CANVAS 打开。
- 用户可选择 Block 并 COPY INTO PRIVATE SPACE。
- 复制后这些内容成为新的 Shared Block，并生成新的事件 ID。

### 20.3 PDF 与 Image

- 只能查看或分享，不能恢复为可编辑 Canvas。

## 21. Clear、Leave 与设备撤销

### 21.1 REMOVE LOCAL DATA

- 只删除当前设备缓存。
- 如果身份密钥仍存在，下次在线会重新同步。
- 操作前说明这不是永久删除。

### 21.2 CLEAR SPACE FOR BOTH

1. 用户点击 REQUEST CLEAR。
2. 生物识别确认。
3. Partner 收到 CLEAR REQUEST。
4. Partner 必须选择 APPROVE 或 REJECT。
5. 双方批准后生成 Clear Event。
6. 所有 Block 进入 Tombstone。
7. 提供有限撤销窗口。

请求超时后自动失效。

### 21.3 LEAVE PRIVATE SPACE

- 删除当前设备的 Space 密钥和访问令牌。
- 需要明确输入 LEAVE 或长按确认。
- 离开后无法靠服务器恢复明文。
- 如果是最后一台有效设备，提示先创建 Encrypted Archive。

### 21.4 REVOKE DEVICE

- 仅在线可执行。
- 显示设备名称、首次配对时间和最近活动。
- 撤销后服务器拒绝该设备的新连接。
- 撤销不会远程擦除该设备已经导出的明文。
- 密钥轮换后双方重新验证 Safety Code。

## 22. Settings

### 22.1 Appearance

主题：

- MIST SAGE
- MOON BLUE
- DUSK VIOLET
- APRICOT MIST

规则：

- 主题为 Local Preference，不强制同步给 Partner。
- Local Cursor 与 Partner Cursor 的身份颜色不随主题互换。
- 高对比模式降低透明度并增强边框。

文字大小：

- COMPACT
- COMFORTABLE
- LARGE
- FOLLOW SYSTEM

### 22.2 Privacy

- APP LOCK
- LOCK DELAY
- BLOCK SCREENSHOTS
- HIDE RECENTS PREVIEW
- AUTO-CLEAR CLIPBOARD
- GENERIC NOTIFICATIONS
- EXPORT AUDIT

### 22.3 Connection

- SERVER URL
- CONNECTION STATUS
- TEST CONNECTION
- CERTIFICATE STATUS
- MIGRATE SERVER
- LAST SUCCESSFUL SYNC

原型期支持：

- 局域网 ws:// 地址。
- 私人穿透网络地址。

正式期要求：

- wss://。
- 有效证书或受控的证书固定策略。

### 22.4 Data

- STORAGE USED
- CREATE ENCRYPTED ARCHIVE
- RESTORE ARCHIVE
- REMOVE LOCAL DATA
- REQUEST CLEAR

### 22.5 Paired Devices

- 当前两位参与者和各自设备。
- Safety Code 状态。
- VERIFY AGAIN。
- REVOKE DEVICE。

## 23. Server Migration

MIGRATE SERVER 流程：

1. 检查本地是否完全同步。
2. 强制建议创建 Encrypted Archive。
3. 输入或扫描新 Server URL。
4. 执行 TEST CONNECTION。
5. 验证新服务端支持的协议版本。
6. 上传本机拥有的加密事件。
7. 比较事件数量、Space 版本和状态哈希。
8. 新服务器验证完成后切换 Active Server。
9. 旧配置保留有限时间用于 ROLLBACK。

迁移不改变：

- Space ID。
- 双方身份密钥。
- 聊天加密密钥。
- Canvas 坐标和 Block ID。

## 24. 数据与事件分类

### 24.1 Persistent Encrypted Events

- SPACE_CREATED
- DEVICE_PAIRED
- DEVICE_REVOKED
- BLOCK_CREATED
- TEXT_OPERATION
- BLOCK_MOVED
- BLOCK_RESIZED
- BLOCK_PINNED
- BLOCK_MERGED
- BLOCK_DELETED
- BLOCK_RECOVERED
- EXPORT_COMPLETED
- CLEAR_REQUESTED
- CLEAR_APPROVED
- SPACE_CLEARED

### 24.2 Ephemeral Events

- CURSOR_MOVED
- SELECTION_CHANGED
- VIEWPORT_CHANGED
- BLOCK_FOCUSED
- LIVE_INPUT_ACTIVE
- HIDDEN_INPUT_ACTIVE
- PARTNER_PRESENCE

### 24.3 Local-only Data

- Theme。
- 字号和高对比设置。
- 本机 Viewport。
- Hidden Draft。
- App Lock 延迟。
- 剪贴板清理偏好。
- 调试日志和性能指标。

## 25. 错误与恢复分支

| 错误 | 用户界面 | 处理 |
|---|---|---|
| Server unavailable | OFFLINE — SAVED LOCALLY | 继续安全编辑并排队 |
| Tunnel unavailable | CAN'T REACH PRIVATE SERVER | 提供 RETRY 和 CONNECTION |
| TLS certificate invalid | SECURITY CHECK REQUIRED | 禁止连接，不允许忽略继续 |
| Device revoked | THIS DEVICE WAS REMOVED | 锁定 Space，允许导出本地加密备份 |
| Key mismatch | ENCRYPTION KEY MISMATCH | 停止同步并要求重新验证 |
| Database full | STORAGE FULL | 停止高风险写入，提示释放空间 |
| Export failed | EXPORT NOT CREATED | 删除不完整临时文件并允许重试 |
| Sync divergence | SYNC NEEDS ATTENTION | 进入只读保护，保留双方版本 |
| Unsupported archive | ARCHIVE VERSION NOT SUPPORTED | 不修改当前数据 |
| Partner deleted edited block | RECOVERED BLOCK CREATED | 保存离线编辑，不静默丢弃 |

错误文案不得包含密钥、完整服务器令牌或聊天正文。

## 26. Accessibility 与视觉降级

- 所有点击目标至少满足 Android 推荐触控尺寸。
- 所有图标具有英文 Content Description。
- 不能只靠颜色区分两位光标；高对比模式增加不同形状或线型。
- Reduce Motion 开启时取消大范围 Canvas 飞行动画。
- 低性能设备关闭实时背景模糊，替换为高透明度纯色面板。
- 系统字体放大后 Block 自适应高度，不裁切内容。
- TalkBack 以“Block、位置、内容摘要、正在编辑人数”的顺序朗读。

## 27. 隐私与安全交互底线

- 默认阻止最近任务预览泄露。
- 可配置阻止截图，但必须说明无法阻止另一台设备拍照。
- 通知默认不含正文。
- 服务端日志不记录聊天明文。
- 崩溃报告删除 Block 内容、密钥、邀请码和服务器令牌。
- 导出明文前必须明确提示风险。
- 配对和设备变化需要 Safety Code。
- 不通过自研加密算法实现端到端加密。
- 服务器只能基于密文事件完成转发、排序、存储和 Ack。

## 28. 核心验收场景

### 28.1 共同编辑

- 两台设备同时进入同一个 Block。
- 双方在不同位置连续输入 60 秒。
- 最终文字、Emoji 和光标退出位置一致。
- 不出现重复字符、丢字或错误覆盖。

### 28.2 同位置并发

- 两台设备在同一字符位置同时插入和删除。
- 重复测试后双方最终状态完全一致。

### 28.3 输入法

- 英文逐字输入。
- 中文拼音候选输入。
- Emoji 组合和肤色修饰符。
- 对方不能看到未提交的输入法候选键序列。

### 28.4 断线恢复

- 一台设备关闭 Wi-Fi 后继续输入。
- 另一台在线继续编辑同一或不同 Block。
- 恢复网络后自动合并，双方无数据丢失。

### 28.5 Hidden Input

- 创建草稿、关闭 App、重新打开。
- 草稿仍存在且 Partner 未收到内容。
- Reveal 后双方以一个原子变化看到完整内容。

### 28.6 Block 操作

- Move、Resize、Pin、Merge 和 Delete 在双方设备一致。
- Merge 中断或失败不会丢失源 Block。
- Delete 与离线编辑冲突时生成 Recovered Block。

### 28.7 Export

- Encrypted Archive 可在干净安装上恢复。
- 错误密码不能泄露任何预览。
- PDF 与 Image 坐标布局正确。
- Partner 能看到 Export Audit，但无法获得导出文件。

### 28.8 Server Migration

- 从电脑局域网服务迁移到穿透地址。
- 再迁移到云服务器。
- Space、密钥、Block 和导出能力保持不变。

## 29. 后续设计输出顺序

1. Canvas 与 Shared Block 的低保真交互原型。
2. Shared Block 全状态组件图。
3. 双人光标、选区和并发动画规范。
4. Pairing 与 Safety Code 流程。
5. Offline、Syncing 和错误状态。
6. Export、Restore 和 Clear 流程。
7. 四套主题 Design Tokens。
8. Jetpack Compose 组件与状态映射。
9. WebSocket / CRDT 事件协议。
10. Android 双机端到端验收测试。

## 30. 版本决策记录

### v1.0 — 2026-09-01

- 确立 English-only UI。
- 用 Shared Text Block 取代传统聊天消息。
- 确立双方共同所有、共同编辑和共同整理。
- 定义 Live Input、Hidden Input、Merge、Offline 和 Export。
- 确立本地电脑加私人穿透为原型部署，后续可迁移云服务器。
