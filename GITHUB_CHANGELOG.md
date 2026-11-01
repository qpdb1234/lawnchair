# Changelog — AI Control Fork

This fork is built on the official **Lawnchair 15.0.0-beta3.0** tag. Everything below is what this
fork changes on top of that baseline.

## AI Control Interface

Let an AI agent or `adb` drive the workspace through nine independent, individually-toggleable
entry points — Broadcast, Activity, Service, ContentProvider, Deep Link
(`launcher://control?cmd=…`), batch insert, a loopback-only HTTP REST server on `localhost:9091`,
MCP JSON-RPC, and dynamic launcher shortcuts.

- Commands: `list_icons`, `move_icon`, `add_icon`, `remove_icon`, `reload`.
- HTTP endpoints: `GET /help`, `/list`, `/state`, `/cmd` and `POST /mcp`.
- Every channel is off by default and gated behind its own switch under **Settings → AI control**.

## Home screen & drawer

- **No-drawer home (toggle):** flatten every app onto the workspace instead of an app drawer; the
  classic drawer can be re-enabled with one switch. Per-app "hidden from home" list included.
- **Classic drag mode:** no spring-loaded zoom / top bar; page by hovering near the screen edge with
  a configurable trigger range (5%–30%, default 15%).

## Icons, folders & apps

- **Icon long-press menu:** customize which rows show and their order.
- **Add to folder:** a menu row that moves the pressed icon into any folder already living on the
  home screen — pick one in a single choice dialog and confirm, no dragging needed. Only offered for
  a loose icon, only while a folder exists, and it follows the menu editor like every other row.
- **Folder upgrades:** an "add apps" editor (global de-duplication / return-to-desktop), a tight-fit
  grid that avoids stray empty cells, and a default folder tint.

## System & misc

- In-app language switching across 23 languages.
- Log collection/export, reset settings to defaults, custom icon import, wallpaper blur, and several
  experimental features promoted to stable.

## Fixes in 15.0.1

- **Uninstalling no longer flashes the home screen twice.** An app removal used to clear the themed-icon
  preference unconditionally, and writing a preference notifies its listeners: the activity got recreated
  *and* the model force-reloaded behind it. The availability check and the write now only happen while
  themed icons are actually enabled.
- **Dropping an icon onto an icon in the first row builds a folder again.** In classic drag mode the top
  Cancel / Uninstall bar stays hidden, but its buttons were still registered as drop targets and their
  hit rectangles cover that row, so the drop was swallowed — either an uninstall confirmation popped up or
  nothing happened at all.
- **Folder gestures survive edge paging.** Icons in the outermost columns sit inside the paging trigger
  zone, and hovering there switched the page just as the finger lifted, dropping the icon on the wrong
  page and discarding the create/add-to-folder gesture. Paging now freezes while a folder is pending.
- **Folders resize immediately.** Adding or removing an app while the folder is open resizes the panel on
  the spot, instead of keeping the old size until the folder was closed and reopened.
- **Dock apps leave the dock when a folder takes them.** The folder "add apps" editor de-duplicated only
  across the workspace pages, while the dock is a separate cell layout, so an app added from the bar ended
  up in the folder *and* still sitting in the bar. The same sweep now covers both.

## Build

- Local / dev builds use the `lawnWithQuickstepGithubDebug` variant; the published release
  (tag `v15.0.1`) is built as `lawnWithQuickstepGithubRelease`. On this fork the release APK is
  debug-signed, because no release keystore is configured (build.gradle falls back to `signingConfigs.debug`).
- The icon menu editor identifies a row by the class name of its `SystemShortcut`, so `proguard.pro`
  keeps those names through R8 (`-keepnames class * extends com.android.launcher3.popup.SystemShortcut`).
- The `@hide` Android APIs compile against the checked-in `prebuilts/libs/*.jar`.

Full diff against upstream: https://github.com/LawnchairLauncher/lawnchair/compare/v15.0.0-beta3.0...qpdb1234:lawnchair:lawnchair-enhancements
