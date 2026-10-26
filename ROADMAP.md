# Fork roadmap

This fork is built on the official **Lawnchair 15.0.0-beta3.0** tag. This document tracks what the
fork has already landed and where it is headed. Upstream Lawnchair's own roadmap lives in the
[main repository](https://github.com/LawnchairLauncher/lawnchair).

## Vision

* **Programmable:** Make the home screen something an AI agent or a script can safely drive.
* **Practical:** Ship launcher tweaks that improve daily use without getting in the way.
* **Optional:** Every new power is an opt-in switch; default behavior stays a normal home app.

## Shipped

- **AI Control Interface:** nine entry points (Broadcast, Activity, Service, ContentProvider, Deep
  Link, batch, HTTP REST on `localhost:9091`, MCP JSON-RPC, dynamic Shortcut), each behind its own
  switch; commands `list_icons` / `move_icon` / `add_icon` / `remove_icon` / `reload`.
- **No-drawer home (toggle)** with a per-app hidden-from-home list.
- **Classic drag mode** with edge paging and a configurable trigger range (5%–30%).
- **Icon long-press menu** customization plus a **Force Stop** entry.
- **Folder upgrades:** add-apps editor, tight-fit grid, default folder tint.
- **In-app language switching** (23 languages).
- Log collection/export, reset settings to defaults, custom icon import, wallpaper blur.

## Current focus

- Harden and document the AI control channels (auth/UX around the enable switches).
- Polish the no-drawer flattening pass for large app lists and reflows.

## Up next

- Richer control commands and query output (folders, widgets, pages).
- Safety guardrails around agent-driven layout changes (undo / confirm).
- Keep the fork in sync with upstream Lawnchair beta/stable releases.
