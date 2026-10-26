---
name: launcher-control
description: Drive the Lawnchair Android home screen (workspace) with an AI agent — list, move, add, remove app icons and reload the desktop over a local HTTP or MCP endpoint. Use when the user asks to arrange, tidy, add or remove apps/icons on their phone/emulator home screen, or to script the launcher workspace.
---

# Launcher control

Let the agent read and reshape the phone's home screen through the launcher's local control server.
Five tools, one shared command set, reachable over **MCP** (`POST /mcp`) or **plain HTTP**.

## Before you start

1. The user must have enabled a channel on the device: **Lawnchair → Settings → General → AI access
   channels → Local HTTP server** (and/or **MCP server**). Everything is off by default.
2. Base URL is `http://127.0.0.1:9091`.
   - On-device agent: use it directly.
   - From a PC: first `adb forward tcp:9091 tcp:9091`.
3. If unsure what is available, `GET http://127.0.0.1:9091/help` prints the live contract (no switch
   needed).
4. **Before your first mutation, self-check connectivity** with the read-only smoke test (safe: it never
   edits the workspace):

   ```sh
   bash scripts/verify_mcp.sh http://127.0.0.1:9091
   ```

   If it reports the port is unreachable (exit 2), stop and tell the user to enable a channel (and
   `adb forward` from a PC) rather than guessing commands blindly.

## The five tools

| Tool | Required | Optional | Effect |
|------|----------|----------|--------|
| `list_icons` | — | — | Read desktop pages. Lines: `ICON\|id=..\|title=..\|intent=..\|screen=..\|x=..\|y=..\|type=..`. Hotseat/folder children are not listed. |
| `move_icon` | `package_name`,`target_cellX`,`target_cellY` | `screen` | Move an existing desktop icon. `screen=-1`/omitted = keep page. |
| `add_icon` | `package_name`,`target_cellX`,`target_cellY` | `screen` | Add an app to the exact cell named (launcher won't pick a free cell). Omitted `screen` = page 0. |
| `remove_icon` | `package_name` | — | Remove every desktop row of that app; hotseat/folder copy untouched. |
| `reload` | — | — | Re-read desktop from DB (only after an external edit). |

## How to call

HTTP (simplest — one GET per action):

```sh
curl http://127.0.0.1:9091/cmd?cmd=list_icons
curl "http://127.0.0.1:9091/cmd?cmd=move_icon&package_name=com.android.chrome&target_cellX=0&target_cellY=1"
curl "http://127.0.0.1:9091/cmd?cmd=add_icon&package_name=org.telegram.messenger&target_cellX=3&target_cellY=2&screen=0"
curl "http://127.0.0.1:9091/cmd?cmd=remove_icon&package_name=com.android.chrome"
curl "http://127.0.0.1:9091/state?pkg=com.android.chrome"   # where an app sits, incl. hotseat/folder
```

MCP (`tools/call`, if you're wired in as an MCP client the tools are already discoverable via
`tools/list`):

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"move_icon",
 "arguments":{"package_name":"com.android.chrome","target_cellX":0,"target_cellY":1}}}
```

## Rules (the launcher enforces these)

- Cells are **0-based**: `target_cellX` = column, `target_cellY` = row, `screen` = page index.
- **Always `list_icons` first** to learn the grid size and which cells are free.
- A cell off the grid or already occupied is **refused** (HTTP `409` / MCP `isError:true`). To place
  something there, first `move_icon`/`remove_icon` the occupant — never assume it will be overwritten.
- `package_name` is matched **literally** and must be one app's package (e.g. `com.android.chrome`),
  not its display label.
- Writes echo back the app's resulting position, verified against the DB — trust the echo over a guess.
- `reload` is only needed after editing `launcher.db` outside the launcher (root/Shizuku); normal
  tool calls already apply live.

## Typical flows

**"Tidy my home screen"** → `list_icons` → plan a compact row-major layout → `move_icon` each app into
a free cell (resolve collisions by moving occupants first) → re-`list_icons` to confirm.

**"Add Signal to my second row"** → `list_icons` → find a free cell on the target page →
`add_icon package_name=org.telegram.messenger target_cellX=.. target_cellY=.. screen=..`.

**"Take Chrome off the home screen"** → `remove_icon package_name=com.android.chrome` (note: this only
affects desktop pages; a Chrome icon in the dock or a folder stays).

## Be safe

- The endpoint has **no auth** and edits the user's real home screen. Do only what was asked, show the
  exact call you ran, and confirm the resulting layout. If a call is refused, explain why and propose
  the fix rather than forcing it.

Full details: see the repo's `docs/AI_INTEGRATION.md` and `docs/AI_CONTROL.md`.
