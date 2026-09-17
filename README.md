# YADT — Yet Another dbt Tool

A JetBrains IDE plugin that brings **lineage visualization**, **code intelligence**, and **command runner** for [dbt](https://www.getdbt.com/) projects.

Works with **IntelliJ IDEA**, **PyCharm**, **DataSpell**, and other JetBrains IDEs (2025.1+).

> Forked from [Endiruslan/dbt-helper](https://github.com/Endiruslan/dbt-helper), licensed under MIT.

---

## Features

- **Lineage graph** — interactive DAG of your dbt models with click-to-explore navigation. Single click previews a model and its dependencies; double click refocuses the graph and opens the file.
- **Run status & freshness** — color lineage nodes by their last `dbt run`/`build` result (the default), with test-failure badges and source-freshness state, updated live as commands run.
- **Selector-driven graph** — type a dbt selector (`tag:`, `path:`, `source:`, `+model`, `model+`, globs) to drive what the graph shows; resolved live or via `dbt ls`.
- **Docs sidebar** — columns, tests, SQL, and metadata for the selected model, side-by-side with the graph.
- **Code intelligence** — autocomplete and go-to-definition for `ref()`, `source()`, and `macro()`; hover for column info; warnings on unresolved references. Works in `.sql` and Jinja files, and in dbt Charts boards.
- **Potentially terminal columns** — a gutter icon in the `.sql` model file marks output columns that no downstream model or exposure reads. It's a heuristic over direct children and exposures; columns it can't pin to a line collapse into a single file-level marker.
- **dbt Charts boards** — completion, hover docs, and structural errors for [dbt Charts](https://dbtcharts.com) board YAML, driven by the schema of your installed `dct`; plus `ref()` / `source()` intelligence inside board queries. See [dbt Charts boards](#dbt-charts-boards).
- **Runner** — run, test, compile, preview, and regenerate docs without leaving the IDE. Target selector and live output log.
- **Editor actions** — in the **YADT** submenu of the editor's right-click menu (and under **Edit**):
  - **Copy for Target DB** — copy SQL with refs resolved to `db.schema.table`
  - **Paste as dbt Refs** — paste SQL with table names converted back into `ref()` / `source()` calls
  - **Convert: ref ↔ Relation** — select a `{{ ref() }}` / `{{ source() }}` or a `database.schema.table` name and convert it in place to the other form; the direction is detected from the selection

---

## Installation

### From Disk (Development Build)
1. Download the latest `yadt-x.x.x.zip` from [Releases](https://github.com/inazr/yadt/releases)
2. In your IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the ZIP file and restart the IDE

### Requirements
- JetBrains IDE **2025.1** or later
- A dbt project with `manifest.json` (run `dbt compile` or `dbt docs generate` first)
- dbt CLI installed and accessible
- Optional, for dbt Charts board editing: the dbt Charts CLI `dct` (`uv tool install dbt-charts`)

---

## Setup

1. Open a project containing `dbt_project.yml`
2. The plugin auto-detects your dbt project root and parses `target/manifest.json`
3. Open the **YADT** tool window (bottom panel)

### Settings
**Settings** → **Tools** → **YADT**

| Setting | Description | Default |
|---------|-------------|---------|
| dbt executable path | Path to the dbt CLI binary | `dbt` (auto-detected from PATH) |
| dct executable path | Path to the dbt Charts CLI; its installed schema drives board editing | `dct` (auto-detected from PATH) |
| Download the board schema from GitHub | Fallback when `dct` isn't installed: fetch the newest released dbt Charts schema, sha256-verified and cached | off |
| Project root override | Absolute path to the dbt project root | auto-detect from `dbt_project.yml` |
| Active target | Target from `profiles.yml` used for compilation | default target |
| Upstream depth | Parent levels shown above the current node (1–20) | 1 |
| Downstream depth | Child levels shown below the current node (1–20) | 2 |
| Edge style | bezier, taxi, round-taxi, segments, straight, unbundled-bezier, haystack | round-taxi |
| Layout direction | Left → Right / Top → Bottom / Right → Left / Bottom → Top | Left → Right |
| Node color | Resource type / Schema name / Status | Status |
| Cluster mode | None / Schema / Folder / Tag | Schema |
| Preview row limit | Max rows returned by `dbt show` (1–1000) | 10 |
| Auto-open tool window on SQL file | Show the YADT panel when a `.sql` file is opened | on |
| Show exposures in lineage | Display dbt exposures in the graph | on |
| Show test failure badge | Red failure-count badge on cards with failed tests | on |
| Send system notifications | Native OS notification when dbt commands finish | on |
| Colored dbt output | Pass `--use-colors`; render ANSI in the Runner panel | off |
| Auto-parse on save | Background `dbt parse` after saving a model/YAML | on |
| Auto-parse also for dbt Cloud CLI | Also auto-parse via dbt Cloud CLI (network per save) | off |

---

## dbt Charts boards

[dbt Charts](https://dbtcharts.com) (`dct`) describes dashboards as YAML boards. YADT makes those board files first-class in the editor.

**Which files count as boards:** `.yml` / `.yaml` files anywhere below the `charts/` directory of a project whose root contains `dbt_charts.yml` — the same layout `dct` uses. `meta.yml` cascade files and `dbt_charts.yml` itself are not treated as boards.

**What you get in a board:**
- **Schema-backed editing** — completion for board, query, and chart keys, hover descriptions, and structural errors (e.g. an unknown chart `type`). The status bar shows *Schema: dbt Charts board* when it is active.
- **dbt code intelligence in queries** — `ref()` / `source()` completion, Cmd/Ctrl+Click navigation, hover docs, and unresolved-reference warnings, exactly as in `.sql` models. dbt macro completion is not offered in boards, because `dct` doesn't resolve dbt macros.

**Where the schema comes from:**
1. **Your installed `dct` (default)** — YADT finds the Python environment behind `dct` (uv, pipx, or a plain virtualenv) and uses the newest released schema shipped with that version, so the editor matches the CLI that renders your boards. Set **dct executable path** if `dct` isn't on your PATH.
2. **GitHub download (opt-in)** — with **Download the board schema from GitHub** enabled, YADT fetches the newest released schema from [dbt-labs/dbt-charts](https://github.com/dbt-labs/dbt-charts) when no local `dct` is found. Downloads are sha256-verified and cached, and fall back to the cache when offline.

If neither source yields a schema, board files stay plain YAML and a project containing `dbt_charts.yml` shows a one-time notification.

Rendering boards (`dct serve` / `dct render`) and `dct validate` are not part of the plugin; run them from the terminal.

---

## Keyboard Shortcuts

| Action | macOS | Windows/Linux |
|--------|-------|---------------|
| Copy for Target DB | `Cmd+Shift+C` | `Ctrl+Shift+C` |
| Paste as dbt Refs | `Cmd+Shift+V` | `Ctrl+Shift+V` |
| Convert: ref ↔ Relation | _unbound_ | _unbound_ |
| dbt: Run / Stop | _unbound_ | _unbound_ |
| dbt: Clear Output | _unbound_ | _unbound_ |

The first three actions live in the editor's right-click **YADT** submenu; `Convert: ref ↔ Relation` has no default shortcut — assign one under **Settings → Keymap**.

`dbt: Run / Stop` and `dbt: Clear Output` mirror the Runner panel's **[RUN]** and **Clear** buttons (same dbt pipeline, same lineage overlays). They ship without a default shortcut — assign one under **Settings → Keymap** (search for "dbt:"). Both are also in the **Tools** menu.

---

## Building from Source

```bash
git clone https://github.com/inazr/yadt.git
cd yadt
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/yadt-x.x.x.zip`.

To run a development instance:
```bash
./gradlew runIde
```

---

## License

[MIT](LICENSE) — © 2026 Ruslan (original author) and inazr (YADT fork).

---

*This plugin is not affiliated with or endorsed by dbt Labs.*
