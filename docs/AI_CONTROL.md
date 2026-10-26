# AI Control Interface — channel manual

This fork lets an **AI agent, a script, or a human at a shell** read and reshape the launcher
workspace. There is **one command set** and **nine independent entry points (channels)**. Every
channel executes the exact same commands through one shared runner
(`AdbControlReceiver.handleCommand`), so a command behaves identically no matter how you deliver
it — only the transport and the security gate differ.

> Connecting an AI agent? For a copy-paste **system prompt**, **MCP client** configs and a ready-made
> **Skill**, see [AI_INTEGRATION.md](AI_INTEGRATION.md). This page is the per-channel reference.

---

## 0. Shared model

### The commands

| Command | Required parameters | Optional | Notes |
|---------|---------------------|----------|-------|
| `list_icons` | — | — | Lists icons on the **desktop pages only** (`container=-100`). Does not include the hotseat/dock or icons inside folders. |
| `move_icon` | `package_name`, `target_cellX`, `target_cellY` | `screen` | Moves an existing desktop icon. If the package is on several cells, the first found is moved. `screen=-1` (or omitted) keeps its current page. |
| `add_icon` | `package_name`, `target_cellX`, `target_cellY` | `screen` | Adds the app's launcher activity at the exact cell you name (it will **not** pick a free cell). Omitted `screen` = page `0`. |
| `remove_icon` | `package_name` | — | Removes **every** desktop-page row of that app. A hotseat/folder row of the same app is left alone. |
| `reload` | — | — | Re-reads the desktop from its database. Use after editing `launcher.db` out-of-band (root / Shizuku). |

Parameter names are literal: `cmd`, `package_name`, `target_cellX`, `target_cellY`, `screen`.
`package_name` is matched **literally** — a `%` or `_` in it is plain text, and it must name one
package. Cells outside the current grid, or a cell another icon already holds, are **refused**
(no silent overwrite). A move/remove of a package that isn't on the desktop, or an add of one that
is already there, counts as **accepted** — the end state is what you asked for.

### Row output format

Reads emit one line per icon:

```
ICON|id=<_id>|title=<label>|intent=<intent uri>|container=<n>|screen=<n>|x=<cellX>|y=<cellY>|type=<itemType>
```

`list_icons` omits the `container` field (it is always the desktop pages). A folder row shows a
`null` title/intent because a folder has no component of its own.

### Enabling a channel (required first step)

Every channel is **OFF by default**. Turn on the one you want under
**Settings → AI control** in the launcher. Each has its own switch:

| Channel | Switch key | System permission in front of it |
|---------|-----------|----------------------------------|
| 1. Broadcast | `pref_enable_adb_control` | `android.permission.DUMP` |
| 2. Activity | `pref_enable_adb_activity` | `DUMP` |
| 3. Service | `pref_enable_adb_service` | `DUMP` |
| 4. ContentProvider (`call`) | `pref_enable_adb_provider` | `DUMP` |
| 5. Deep Link | `pref_enable_adb_link` | none |
| 6. Batch (`insert`/`bulkInsert`) | `pref_enable_adb_batch` | `DUMP` |
| 7. HTTP REST | `pref_enable_adb_http` | none (loopback only) |
| 8. MCP | `pref_enable_adb_mcp` | none (loopback only) |
| 9. Shortcut | `pref_enable_adb_shortcut` | none |

The switches live in `shared_prefs/com.android.launcher3.prefs`. Channels **1–4 and 6** additionally
require the caller to hold `DUMP`, which in practice means `adb shell` / root — ordinary installed
apps cannot reach them. Channels **5, 7, 8, 9** carry no permission so an on-device app, a browser,
or a voice assistant can drive them; that is exactly why they default to off and why every call is
logged with the calling package.

### Where results go

- Channels **1, 2, 3, 5** (broadcast / activity / service / deep link) have **no result channel**
  back to the caller — detailed output goes to **logcat**, tag `AdbControl`:
  `adb logcat -s AdbControl`
- Channel **4 (provider `call`)** returns a `Bundle` (`handled`, `completed`, `result`); details in
  logcat. **6 (batch)** returns the number of rows accepted.
- Channels **7 (HTTP) / 8 (MCP)** return the data **inline** in the HTTP response.

### The package name (`<PKG>`)

The ContentProvider authority is `<PKG>.aicontrol`, and `<PKG>` depends on the build variant:

| Build variant | `<PKG>` (applicationId) |
|---------------|-------------------------|
| GitHub (`lawnWithQuickstepGithubDebug`) | `com.lawnchair.launcher` |
| Nightly | `app.lawnchair.nightly` |
| Play | `app.lawnchair.play` |

Replace `<PKG>` in every example below with the value for your build.

---

## 1. Broadcast (`am broadcast`)

The original adb-facing entry point. Protected by `DUMP` + `pref_enable_adb_control`.
Actions it accepts: `com.android.launcher3.adb.{COMMAND,LIST_ICONS,MOVE_ICON,ADD_ICON,REMOVE_ICON,RELOAD}`,
or any action with the `COMMAND` filter plus a `cmd` extra. The command may also be the last dotted
segment of the action (e.g. `...MOVE_ICON` → `move_icon`).

```sh
# list (result → logcat)
adb shell am broadcast -a com.android.launcher3.adb.LIST_ICONS

# move, via the generic COMMAND action + cmd extra
adb shell am broadcast -a com.android.launcher3.adb.COMMAND \
  --es cmd move_icon --es package_name com.android.chrome \
  --ei target_cellX 0 --ei target_cellY 1 --ei screen 0

# add
adb shell am broadcast -a com.android.launcher3.adb.ADD_ICON \
  --es package_name telegram.m \
  --ei target_cellX 2 --ei target_cellY 3

# remove
adb shell am broadcast -a com.android.launcher3.adb.REMOVE_ICON \
  --es package_name com.android.chrome

# reload after editing launcher.db by hand
adb shell am broadcast -a com.android.launcher3.adb.RELOAD

# read back what happened
adb logcat -d -s AdbControl
```

---

## 2. Headless Activity (`am start`)

Same command set, reached by starting `AdbControlActivity` (NoDisplay, no recents, no history).
Gate: `DUMP` + `pref_enable_adb_activity`. Command travels in the `cmd` extra (or the action).

```sh
adb shell am start -n <PKG>/com.android.launcher3.AdbControlActivity \
  --es cmd list_icons

adb shell am start -n <PKG>/com.android.launcher3.AdbControlActivity \
  --es cmd move_icon --es package_name com.android.chrome \
  --ei target_cellX 3 --ei target_cellY 0
```

Result → logcat (`AdbControl`).

---

## 3. Short-lived Service (`am start-service`)

Runs past the ~10s broadcast window; `START_NOT_STICKY`, stops itself after one request.
Gate: `DUMP` + `pref_enable_adb_service`.

```sh
adb shell am start-service -n <PKG>/com.android.launcher3.AdbControlService \
  --es cmd list_icons

adb shell am start-service -n <PKG>/com.android.launcher3.AdbControlService \
  --es cmd add_icon --es package_name org.mozilla.firefox \
  --ei target_cellX 1 --ei target_cellY 1 --ei screen 0
```

Result → logcat (`AdbControl`).

---

## 4. ContentProvider `call` (synchronous)

For tools that speak the content-URI dialect. `call()` runs the command on the launcher main
thread and **waits up to 60s** so you get a synchronous answer. Gate: `DUMP` +
`pref_enable_adb_provider`. Authority = `<PKG>.aicontrol`.

The `--method` is used as the command; `--extra` key:value pairs carry parameters
(`:s:` = string, `:i:` = integer). An explicit `cmd:s:` extra also works and wins.

```sh
# list
adb shell content call --uri content://<PKG>.aicontrol --method list_icons

# move
adb shell content call --uri content://<PKG>.aicontrol --method move_icon \
  --extra package_name:s:com.android.chrome \
  --extra target_cellX:i:0 --extra target_cellY:i:1

# reload
adb shell content call --uri content://<PKG>.aicontrol --method reload
```

Returns a Bundle with `handled` / `completed` / `result`; details in logcat.

---

## 5. Deep Link (`launcher://control`)

The one write-capable channel with **no system permission**, so anything that can fire a VIEW
intent can use it: Termux, Tasker, a web page, a voice assistant. Because a deep link cannot carry
extras, **all parameters go in the URL query string**. Gate: `pref_enable_adb_link` (default off;
every call is logged with the calling package). There is **no result channel** — use HTTP for inline
output.

```
launcher://control?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1&screen=0
launcher://control?cmd=list_icons
launcher://control?cmd=remove_icon&package_name=telegram.m
launcher://control?cmd=reload
```

From a shell (same VIEW intent a browser/app would fire):

```sh
adb shell am start -a android.intent.action.VIEW \
  -d "launcher://control?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1"
```

From an on-device app:

```kotlin
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
    "launcher://control?cmd=reload"
)))
```

Result → logcat (`AdbControl`, line prefixed `[deeplink] from=<caller>`).

---

## 6. Batch — `insert` / `bulkInsert`

Hand over **many operations in one round trip**. Same authority (`<PKG>.aicontrol`), same
`DUMP` gate, its own switch `pref_enable_adb_batch`. Each row is run in order on the launcher main
thread and awaited before the next, preserving your sequence. `bulkInsert` returns **how many rows
actually ran**; `insert` returns the row URI (or `null` on failure).

Single row via shell `content insert`:

```sh
adb shell content insert --uri content://<PKG>.aicontrol \
  --bind cmd:s:move_icon \
  --bind package_name:s:com.android.chrome \
  --bind target_cellX:i:0 --bind target_cellY:i:1
```

Many rows from an app (each `ContentValues` = one command):

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
val accepted = cr.bulkInsert(uri, rows)   // number of rows that ran
```

---

## 7. HTTP REST (loopback `localhost:9091`)

A loopback-only HTTP server inside the launcher process, for callers with **no adb at all**. Bound
to `127.0.0.1` only — nothing is reachable from the network. Gate: `pref_enable_adb_http`.
The server is started by the settings screen when the switch turns on (and revived on next bind
after a reboot); while off, nothing is bound. `/help` needs no switch.

From a PC, forward the port first:

```sh
adb forward tcp:9091 tcp:9091
```

Endpoints:

```sh
# what is available (no switch required)
curl http://127.0.0.1:9091/help

# the desktop pages, inline
curl http://127.0.0.1:9091/list

# where one package sits, in ANY container (page / hotseat / folder)
curl "http://127.0.0.1:9091/state?pkg=com.android.chrome"

# run any command; the reply is verified against the database afterwards
curl "http://127.0.0.1:9091/cmd?cmd=list_icons"
curl "http://127.0.0.1:9091/cmd?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1&screen=0"
curl "http://127.0.0.1:9091/cmd?cmd=add_icon&package_name=telegram.m&target_cellX=2&target_cellY=3"
curl "http://127.0.0.1:9091/cmd?cmd=remove_icon&package_name=com.android.chrome"
curl "http://127.0.0.1:9091/cmd?cmd=reload"
```

Response behavior:

- `list_icons` is answered inline (no logcat needed).
- A mutation answers `ok` **plus** the package's resulting position (`/state` echo).
- Refusals answer **HTTP 409** with an explanation (unknown cmd, blank `package_name`, a cell off
  the grid, a cell already taken, a package with no launch intent, a row the library would not
  take, or the main thread not answering within 60s).
- `/state` with no/blank `pkg` → **400**. Unknown path → **404**. Switch off → **403**.
- The `/cmd` parameters are identical to every other channel: `cmd`, `package_name`,
  `target_cellX`, `target_cellY`, `screen`.

---

## 8. MCP (JSON-RPC 2.0 over `POST /mcp`)

A [Model Context Protocol](https://modelcontextprotocol.io) server for AI clients that already
speak MCP (Claude Desktop, an MCP SDK, an agent framework). It rides the **same loopback HTTP
server** as channel 7: MCP requests are `POST /mcp`. Gate: `pref_enable_adb_mcp` (independent of
the plain REST switch, checked per request).

Implemented subset: `initialize`, `ping`, `tools/list`, `tools/call`, and `notifications/*`
(a bare notification with no `id` is acknowledged with **HTTP 202** and no body). No streaming,
sampling, or resources — the launcher only exposes tools.

Server info: `name = "lawnchair-launcher"`, `version = "1.10"`,
`protocolVersion = "2024-11-05"`.

```sh
adb forward tcp:9091 tcp:9091   # only if calling from a PC

# initialize
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# list the tools
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# call a tool: move_icon
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"move_icon","arguments":{"package_name":"com.android.chrome","target_cellX":0,"target_cellY":1}}}'

# read a tool's result (list_icons returns the rows inline in the result text)
curl -X POST http://127.0.0.1:9091/mcp -d \
 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_icons","arguments":{}}}'
```

Tools exposed by `tools/list`: `list_icons`, `move_icon`, `add_icon`, `remove_icon`, `reload` —
each with its `inputSchema`. Tool arguments use the same names (`package_name`, `target_cellX`,
`target_cellY`, `screen`). A tool result is `{"content":[{"type":"text","text":...}],"isError":...}`;
`move/add/remove` on success return `ok` followed by the package's resulting position, and refusals
return an `error: ...` text with `isError: true`.

Example `tools/list` response (abridged):

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

## 9. Dynamic Shortcut (long-press the control icon)

When `pref_enable_adb_shortcut` is on, the launcher enables a `LauncherControl` icon; long-pressing
it exposes **the two parameter-free commands** as static shortcuts:

- `list_icons`
- `reload`

Tapping the icon opens the settings screen; the long-press entries fire their intent at
`AdbControlShortcutActivity`, which passes the command through the action
(`com.android.launcher3.adb.LIST_ICONS` → `list_icons`, `...RELOAD` → `reload`). No permission is
required (the system fires these on the user's tap); the switch is the only gate. When the switch
goes off, the icon and shortcuts are disabled again and leave no trace.

> Only the two parameter-free commands are offered here — a static shortcut cannot take arguments
> at trigger time. Use the deep link (channel 5) or HTTP (channel 7) for `move` / `add` / `remove`.

You can also trigger a shortcut by id from adb:

```sh
adb shell am start -n <PKG>/com.android.launcher3.AdbControlShortcutActivity \
  -a com.android.launcher3.adb.RELOAD
```

Result → logcat (`AdbControl`).

---

## Quick chooser

| Situation | Use |
|-----------|-----|
| At an adb shell / from root | Broadcast (1), Activity (2), Service (3), Provider call (4) |
| An installed app / automation must drive it, no root | Deep Link (5) or HTTP (7) |
| Many edits in one shot, in order | Batch `bulkInsert` (6) |
| A human/script wants inline text output over a socket | HTTP (7) |
| An MCP-capable AI client | MCP (8) |
| One-tap on-device actions, no args | Shortcut (9) |

## Safety notes

- Turn channels on only while you need them; **5/7/8/9 have no permission gate** and can rearrange
  the home screen from anything on the device that reaches them.
- The HTTP/MCP server is **loopback-only** (`127.0.0.1`); it is not exposed to the network. Reaching
  it from a PC is via an explicit `adb forward`, which you control.
- The mutation channels write the launcher's own `favorites` table on the main thread, so what a
  channel reports back is already reflected on the workspace.
