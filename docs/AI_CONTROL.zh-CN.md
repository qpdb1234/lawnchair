# AI 控制接口 — 通道使用手册（中文）

本 fork 让 **AI 智能体、脚本，或命令行前的开发者** 读取并改写桌面（workspace）。只有
**一套命令**，却有 **九个相互独立的入口（通道）**。每个通道都通过同一个执行器
（`AdbControlReceiver.handleCommand`）运行完全相同的命令，因此同一条命令无论用哪种方式送达，
行为都一致 —— 不同的只是传输方式和权限门。

> 要接入 AI 智能体（系统提示词 / MCP 客户端配置 / 现成 Skill）？见
> [AI_INTEGRATION.zh-CN.md](AI_INTEGRATION.zh-CN.md)。本页是逐通道参考手册。

---

## 0. 通用模型

### 命令集

| 命令 | 必填参数 | 可选参数 | 说明 |
|------|----------|----------|------|
| `list_icons` | — | — | 列出 **桌面页**（`container=-100`）上的图标。不含 hotseat/停靠栏，也不含文件夹内的图标。 |
| `move_icon` | `package_name`、`target_cellX`、`target_cellY` | `screen` | 移动一个已存在的桌面图标。若该应用占多个格子，移动找到的第一个。`screen=-1`（或省略）保持其当前页。 |
| `add_icon` | `package_name`、`target_cellX`、`target_cellY` | `screen` | 把应用的启动 Activity 加到你指定的确切格子（它 **不会** 自动挑空格）。省略 `screen` 视为第 `0` 页。 |
| `remove_icon` | `package_name` | — | 删除该应用在桌面页上的 **每一** 行。同一应用在 hotseat/文件夹里的行不受影响。 |
| `reload` | — | — | 从数据库重新读取桌面。在外部（root / Shizuku）直接改过 `launcher.db` 后用。 |

参数名是固定的字面量：`cmd`、`package_name`、`target_cellX`、`target_cellY`、`screen`。
`package_name` 按 **字面** 匹配 —— 其中的 `%` 或 `_` 是普通文本，且它必须指向单个应用。落在当前网格
之外、或被别的图标占用的格子会被 **拒绝**（不会静默覆盖）。移动/删除一个并不在桌面上的应用，或添加
一个已在桌面上的应用，都算 **成功** —— 因为最终状态正是你要的。

### 行输出格式

读取时每个图标一行：

```
ICON|id=<_id>|title=<标签>|intent=<intent uri>|container=<n>|screen=<n>|x=<cellX>|y=<cellY>|type=<itemType>
```

`list_icons` 不输出 `container` 字段（它恒为桌面页）。文件夹行会显示 `null` 的 title/intent，
因为文件夹本身没有组件。

### 启用某个通道（第一步，必做）

每个通道 **默认全部关闭**。在启动器的 **设置 → AI 控制** 里打开你要用的那一个。每个通道各有独立开关：

| 通道 | 开关 key | 前置系统权限 |
|------|----------|--------------|
| 1. 广播 Broadcast | `pref_enable_adb_control` | `android.permission.DUMP` |
| 2. Activity | `pref_enable_adb_activity` | `DUMP` |
| 3. Service | `pref_enable_adb_service` | `DUMP` |
| 4. ContentProvider（`call`） | `pref_enable_adb_provider` | `DUMP` |
| 5. Deep Link 深链 | `pref_enable_adb_link` | 无 |
| 6. 批量 Batch（`insert`/`bulkInsert`） | `pref_enable_adb_batch` | `DUMP` |
| 7. HTTP REST | `pref_enable_adb_http` | 无（仅回环） |
| 8. MCP | `pref_enable_adb_mcp` | 无（仅回环） |
| 9. Shortcut 快捷方式 | `pref_enable_adb_shortcut` | 无 |

这些开关存放在 `shared_prefs/com.android.launcher3.prefs`。通道 **1–4 和 6** 还额外要求调用方持有
`DUMP` 权限，实践中意味着 `adb shell` / root —— 普通安装的 App 无法触达。通道 **5、7、8、9** 不带任何
权限门，因此设备上的 App、浏览器、语音助手都能驱动它们；这正是它们默认关闭、且每次调用都记录调用方
包名的原因。

### 结果去向

- 通道 **1、2、3、5**（广播 / activity / service / 深链）**没有回传给调用方的结果通道** —— 详细输出
  写入 **logcat**，tag 为 `AdbControl`：`adb logcat -s AdbControl`
- 通道 **4（provider `call`）** 返回一个 `Bundle`（`handled`、`completed`、`result`）；细节仍在 logcat。
  **6（批量）** 返回实际执行的行数。
- 通道 **7（HTTP）/ 8（MCP）** 在 HTTP 响应里 **内联返回** 数据。

### 包名（`<PKG>`）

ContentProvider 的 authority 是 `<PKG>.aicontrol`，而 `<PKG>` 取决于构建变体：

| 构建变体 | `<PKG>`（applicationId） |
|----------|--------------------------|
| GitHub（`lawnWithQuickstepGithubDebug`） | `com.lawnchair.launcher` |
| Nightly | `app.lawnchair.nightly` |
| Play | `app.lawnchair.play` |

下面每个示例里的 `<PKG>` 都请替换成你构建对应的值。

---

## 1. 广播（`am broadcast`）

最早面向 adb 的入口。受 `DUMP` + `pref_enable_adb_control` 保护。
可接受的 action：`com.android.launcher3.adb.{COMMAND,LIST_ICONS,MOVE_ICON,ADD_ICON,REMOVE_ICON,RELOAD}`，
或任意带 `COMMAND` filter 的 action 再配 `cmd` extra。命令也可以是 action 的最后一段
（例如 `...MOVE_ICON` → `move_icon`）。

```sh
# 列出（结果进 logcat）
adb shell am broadcast -a com.android.launcher3.adb.LIST_ICONS

# 移动：用通用 COMMAND action + cmd extra
adb shell am broadcast -a com.android.launcher3.adb.COMMAND \
  --es cmd move_icon --es package_name com.android.chrome \
  --ei target_cellX 0 --ei target_cellY 1 --ei screen 0

# 添加
adb shell am broadcast -a com.android.launcher3.adb.ADD_ICON \
  --es package_name telegram.m \
  --ei target_cellX 2 --ei target_cellY 3

# 删除
adb shell am broadcast -a com.android.launcher3.adb.REMOVE_ICON \
  --es package_name com.android.chrome

# 手改 launcher.db 后重新加载
adb shell am broadcast -a com.android.launcher3.adb.RELOAD

# 读回结果
adb logcat -d -s AdbControl
```

---

## 2. 无界面 Activity（`am start`）

同一命令集，通过启动 `AdbControlActivity` 送达（NoDisplay，不进 recents、不留历史）。
门：`DUMP` + `pref_enable_adb_activity`。命令放在 `cmd` extra（或 action）里。

```sh
adb shell am start -n <PKG>/com.android.launcher3.AdbControlActivity \
  --es cmd list_icons

adb shell am start -n <PKG>/com.android.launcher3.AdbControlActivity \
  --es cmd move_icon --es package_name com.android.chrome \
  --ei target_cellX 3 --ei target_cellY 0
```

结果进 logcat（`AdbControl`）。

---

## 3. 短生命周期 Service（`am start-service`）

能跑过广播约 10 秒的时间窗；`START_NOT_STICKY`，处理完一次请求就自我停止。
门：`DUMP` + `pref_enable_adb_service`。

```sh
adb shell am start-service -n <PKG>/com.android.launcher3.AdbControlService \
  --es cmd list_icons

adb shell am start-service -n <PKG>/com.android.launcher3.AdbControlService \
  --es cmd add_icon --es package_name org.mozilla.firefox \
  --ei target_cellX 1 --ei target_cellY 1 --ei screen 0
```

结果进 logcat（`AdbControl`）。

---

## 4. ContentProvider `call`（同步）

面向会说 content-URI 这套方言的工具。`call()` 把命令投递到启动器主线程执行并 **最多等待 60 秒**，
因此调用方能拿到同步答复。门：`DUMP` + `pref_enable_adb_provider`。authority = `<PKG>.aicontrol`。

`--method` 被当作命令；`--extra` 的 key:value 承载参数（`:s:` = 字符串，`:i:` = 整数）。
显式的 `cmd:s:` extra 同样有效且优先级更高。

```sh
# 列出
adb shell content call --uri content://<PKG>.aicontrol --method list_icons

# 移动
adb shell content call --uri content://<PKG>.aicontrol --method move_icon \
  --extra package_name:s:com.android.chrome \
  --extra target_cellX:i:0 --extra target_cellY:i:1

# 重新加载
adb shell content call --uri content://<PKG>.aicontrol --method reload
```

返回一个含 `handled` / `completed` / `result` 的 Bundle；细节在 logcat。

---

## 5. Deep Link 深链（`launcher://control`）

唯一 **不带系统权限** 的可写入口，因此任何能发出 VIEW intent 的东西都能用它：Termux、Tasker、
网页、语音助手。因为深链无法携带 extras，**所有参数都要放进 URL 查询串**。
门：`pref_enable_adb_link`（默认关；每次调用都记录调用方包名）。**没有结果通道** —— 需要内联输出请用 HTTP。

```
launcher://control?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1&screen=0
launcher://control?cmd=list_icons
launcher://control?cmd=remove_icon&package_name=telegram.m
launcher://control?cmd=reload
```

从命令行（等价于浏览器/App 发出的 VIEW intent）：

```sh
adb shell am start -a android.intent.action.VIEW \
  -d "launcher://control?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1"
```

从设备上的 App：

```kotlin
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
    "launcher://control?cmd=reload"
)))
```

结果进 logcat（`AdbControl`，行前缀 `[deeplink] from=<调用方>`）。

---

## 6. 批量 — `insert` / `bulkInsert`

**一次往返提交多条操作**。同一 authority（`<PKG>.aicontrol`）、同一 `DUMP` 门，但有独立开关
`pref_enable_adb_batch`。每一行按顺序在主线程执行、执行完才跑下一行，从而保留你的次序。`bulkInsert`
返回 **实际执行的行数**；`insert` 返回该行的 URI（失败为 `null`）。

用命令行 `content insert` 提交单行：

```sh
adb shell content insert --uri content://<PKG>.aicontrol \
  --bind cmd:s:move_icon \
  --bind package_name:s:com.android.chrome \
  --bind target_cellX:i:0 --bind target_cellY:i:1
```

从 App 一次提交多行（每个 `ContentValues` = 一条命令）：

```kotlin
val cr = context.contentResolver
val uri = Uri.parse("content://$PKG.aicontrol")
val rows = arrayOf(
    ContentValues().apply {
        put("cmd", "add_icon"); put("package_name", "org.mozilla.firefox")
        put("target_cellX", 0); put("target_cellY", 0); put("screen", 1)
    },
    ContentValues().apply {
        put("cmd", "remove_icon"); put("package_name", "telegram.m")
    },
    ContentValues().apply { put("cmd", "reload") },
)
val accepted = cr.bulkInsert(uri, rows)   // 实际执行的行数
```

---

## 7. HTTP REST（回环 `localhost:9091`）

运行在启动器进程内、仅监听回环地址的 HTTP 服务，面向 **完全没有 adb** 的调用方。
只绑定 `127.0.0.1` —— 网络上无法访问。门：`pref_enable_adb_http`。服务由设置页在开关打开时启动
（重启后下次绑定该开关时复活）；开关关闭时什么都不绑定。`/help` 无需开关。

从 PC 调用前，先转发端口：

```sh
adb forward tcp:9091 tcp:9091
```

端点：

```sh
# 可用的接口（无需开关）
curl http://127.0.0.1:9091/help

# 桌面页，内联返回
curl http://127.0.0.1:9091/list

# 某个应用所在位置，覆盖所有容器（页 / hotseat / 文件夹）
curl "http://127.0.0.1:9091/state?pkg=com.android.chrome"

# 运行任意命令；答复会与数据库核对后返回
curl "http://127.0.0.1:9091/cmd?cmd=list_icons"
curl "http://127.0.0.1:9091/cmd?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1&screen=0"
curl "http://127.0.0.1:9091/cmd?cmd=add_icon&package_name=telegram.m&target_cellX=2&target_cellY=3"
curl "http://127.0.0.1:9091/cmd?cmd=remove_icon&package_name=com.android.chrome"
curl "http://127.0.0.1:9091/cmd?cmd=reload"
```

响应行为：

- `list_icons` 内联返回（不必看 logcat）。
- 写操作返回 `ok` **外加** 该应用改写后的位置（`/state` 回显）。
- 被拒绝时返回 **HTTP 409** 并附原因（未知 cmd、`package_name` 为空、格子越界、格子被占、
  应用没有启动 intent、库不肯接受的行，或主线程 60 秒内无应答）。
- `/state` 缺 `pkg`/`pkg` 为空 → **400**。未知路径 → **404**。开关关 → **403**。
- `/cmd` 的参数与其它通道完全一致：`cmd`、`package_name`、`target_cellX`、`target_cellY`、`screen`。

---

## 8. MCP（`POST /mcp` 上的 JSON-RPC 2.0）

面向已经会说 [Model Context Protocol](https://modelcontextprotocol.io) 的 AI 客户端
（Claude Desktop、某个 MCP SDK、agent 框架）的 MCP 服务端。它 **复用通道 7 的同一个回环 HTTP
服务**：MCP 请求即 `POST /mcp`。门：`pref_enable_adb_mcp`（与普通 REST 开关相互独立，逐请求检查）。

实现的子集：`initialize`、`ping`、`tools/list`、`tools/call`，以及 `notifications/*`
（不带 `id` 的纯通知以 **HTTP 202** 空体确认）。不做流式、采样、资源 —— 启动器只暴露工具。

服务信息：`name = "lawnchair-launcher"`、`version = "1.10"`、`protocolVersion = "2024-11-05"`。

```sh
adb forward tcp:9091 tcp:9091   # 仅当从 PC 调用时需要

# initialize
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# 列出工具
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 调用工具：move_icon
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"move_icon","arguments":{"package_name":"com.android.chrome","target_cellX":0,"target_cellY":1}}}'

# 读取工具结果（list_icons 会把行内联在 result 文本里返回）
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_icons","arguments":{}}}'
```

`tools/list` 暴露的工具：`list_icons`、`move_icon`、`add_icon`、`remove_icon`、`reload` ——
每个都带自己的 `inputSchema`。工具参数用同样的名字（`package_name`、`target_cellX`、`target_cellY`、
`screen`）。工具结果形如 `{"content":[{"type":"text","text":...}],"isError":...}`；
`move/add/remove` 成功时返回 `ok` 后接该应用改写后的位置，被拒绝时返回以 `error: ` 开头的文本且
`isError: true`。

`tools/list` 响应示例（节选）：

```json
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"move_icon","description":"Move an existing workspace icon ...",
   "inputSchema":{"type":"object",
     "properties":{"package_name":{"type":"string"},"target_cellX":{"type":"integer"},
       "target_cellY":{"type":"integer"},"screen":{"type":"integer"}},
     "required":["package_name","target_cellX","target_cellY"]}}
]}}
```

---

## 9. 动态快捷方式 Shortcut（长按控制图标）

当 `pref_enable_adb_shortcut` 打开时，启动器会启用一个 `LauncherControl` 图标；长按它会暴露
**两条无参命令** 作为静态快捷方式：

- `list_icons`
- `reload`

点击图标会打开设置页；长按项把 intent 发给 `AdbControlShortcutActivity`，再通过 action 传递命令
（`com.android.launcher3.adb.LIST_ICONS` → `list_icons`，`...RELOAD` → `reload`）。无需权限
（系统按用户点击触发），开关是唯一的门。开关关闭时图标和快捷方式会被重新禁用，不留痕迹。

> 这里只提供两条无参命令 —— 静态快捷方式在触发时无法带参数。需要 `move` / `add` / `remove`
> 请用深链（通道 5）或 HTTP（通道 7）。

也可从 adb 按 id 触发：

```sh
adb shell am start -n <PKG>/com.android.launcher3.AdbControlShortcutActivity \
  -a com.android.launcher3.adb.RELOAD
```

结果进 logcat（`AdbControl`）。

---

## 场景选型

| 场景 | 用哪个 |
|------|--------|
| 在 adb shell / root 环境 | 广播 (1)、Activity (2)、Service (3)、Provider call (4) |
| 已安装的 App / 自动化要驱动，且无 root | 深链 (5) 或 HTTP (7) |
| 一次提交多条编辑、且讲究顺序 | 批量 `bulkInsert` (6) |
| 人或脚本想要 socket 上的内联文本 | HTTP (7) |
| 会说 MCP 的 AI 客户端 | MCP (8) |
| 设备上一键触发、无需参数 | 快捷方式 (9) |

## 安全提示

- 只在需要时打开通道；**5/7/8/9 没有权限门**，设备上任何能触达它的东西都能改写桌面。
- HTTP/MCP 服务 **仅回环**（`127.0.0.1`），不对外网暴露。从 PC 访问要靠你显式执行的 `adb forward`，
  由你掌控。
- 可写的通道在主线程写启动器自己的 `favorites` 表，所以通道回报的内容已经是桌面上生效的结果。

> 英文原版见 [AI_CONTROL.md](AI_CONTROL.md)。
