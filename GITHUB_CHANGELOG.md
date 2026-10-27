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
- **Folder upgrades:** an "add apps" editor (global de-duplication / return-to-desktop), a tight-fit
  grid that avoids stray empty cells, and a default folder tint.

## System & misc

- In-app language switching across 23 languages.
- Log collection/export, reset settings to defaults, custom icon import, wallpaper blur, and several
  experimental features promoted to stable.

## Build

- Build variant: `lawnWithQuickstepGithubDebug`.
- The `@hide` Android APIs compile against the checked-in `prebuilts/libs/*.jar`.

Full diff against upstream: https://github.com/LawnchairLauncher/lawnchair/compare/v15.0.0-beta3.0...qpdb1234:lawnchair:lawnchair-enhancements
