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
- **Code intelligence** — autocomplete and go-to-definition for `ref()`, `source()`, and `macro()`; hover for column info; warnings on unresolved references. Works in `.sql` and Jinja files.
- **Runner** — run, test, compile, preview, and regenerate docs without leaving the IDE. Target selector and live output log.
- **Copy & paste helpers** — copy SQL with refs resolved to `db.schema.table`, or paste SQL with table names converted back into `ref()` / `source()` calls.

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
| Project root override | Absolute path to the dbt project root | auto-detect from `dbt_project.yml` |
| Active target | Target from `profiles.yml` used for compilation | default target |
| Upstream depth | Parent levels shown above the current node (1–20) | 2 |
| Downstream depth | Child levels shown below the current node (1–20) | 1 |
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

## Keyboard Shortcuts

| Action | macOS | Windows/Linux |
|--------|-------|---------------|
| Copy for Target DB | `Cmd+Shift+C` | `Ctrl+Shift+C` |
| Paste as dbt Refs | `Cmd+Shift+V` | `Ctrl+Shift+V` |

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
