# YADT — Yet Another dbt Tool Changelog

## [Unreleased]

- **Auto-parse on save** now runs `dbt parse` with the target selected in YADT, so `manifest.json` (and every `ref()` resolved from it, e.g. by dbt Charts) points at that target's relations. Switching the target re-parses right away, and so does opening the project
- New: **dbt Charts board preview.** Board files open in a split editor like Markdown: YAML on the left, the board rendered by your own `dct serve` on the right, with the usual Editor / Split / Preview switch. The preview refreshes about a second after you stop typing (YADT saves the board for you), and edits to `meta.yml` files refresh open previews too. Queries run against your warehouse exactly as `dct serve` would; in the Editor-only layout the preview is paused, so nothing is saved automatically and no queries run. If `dct` answers with an error page (for example while a SQL string is still open), the preview reloads itself on the next save. The preview runs on the dbt target selected in the Runner, and switches when you change it. Saving a board no longer triggers **Auto-parse on save** (boards aren't dbt resources; the parse rewrote `manifest.json` while dbt Charts was reading it). Needs an IDE with JCEF (otherwise boards open in the plain editor) and `dct` installed (otherwise the preview pane says so, with a Retry button)

## [0.7.0] - 2026-09-17

- Lineage: zooming no longer speeds up the longer the tool window is open. The wheel, zoom-button and keyboard zoom handlers were attached again on every graph render, so after N re-renders a single scroll tick or click zoomed N times; they are now attached once
- Lineage: the hover tooltip shows the node's name again (its title line was always empty)
- Lineage: nodes whose last run ended in a status YADT doesn't color (for example dbt's `no-op`) no longer show as failed when the tool window reloads `run_results.json`; they keep a neutral card, as they already did right after a run
- Lineage: when a run starts with a `tag:`, `path:`, `source:`, `fqn:` or wildcard selector, exactly the nodes it builds are marked as queued. Previously only plain model names and `+` operators were resolved, and any other selector marked every model/seed/snapshot visible in the graph
- Compatibility: YADT no longer uses any IntelliJ Platform API that JetBrains marks as deprecated or scheduled for removal (flagged against 2026.2 and the 2026.3 EAP), and it now uses the IDE's own Kotlin coroutines instead of bundling a copy — that bundled copy is what kept dbt Charts support from starting on PyCharm 2026.2 in 0.6.0
- A dbt project added to an open IDE project (a new `dbt_project.yml`) is now picked up without reopening the project
- `dct` and `uv` are now also found in `/usr/local/bin` and `/opt/homebrew/bin` when they aren't on the IDE's `PATH`

## [0.6.1] - 2026-09-17

- Refreshed the plugin description: it now covers the docs sidebar (replacing the outdated "Docs viewer" panel), grouping/search/minimap in the lineage graph, potentially terminal columns, and the **YADT** editor submenu with **Convert: ref ↔ Relation**, and notes that code intelligence also works in dbt Charts boards. No functional changes since 0.6.0

## [0.6.0] - 2026-09-17

- New: **dbt Charts board editing.** Board YAML files under a dbt Charts project's `charts/` directory get completion, hover docs and structural errors from dbt Charts' own schema. YADT reads the schema from your installed `dct` so the editor matches the CLI that renders the board; if `dct` isn't installed you can let YADT download the newest released schema from GitHub (Settings → Tools → YADT → dbt Charts, off by default). Downloads are sha256-verified and cached
- dbt Charts boards: `ref()` / `source()` completion, Ctrl/Cmd+Click navigation, hover docs and unresolved-reference warnings now work inside board `queries:`, the same as in `.sql` models. dbt macro completion is not offered there, because dbt Charts doesn't resolve dbt macros

## [0.5.2] - 2026-08-26

- Lineage: models inside a column are now ordered alphabetically wherever that is (nearly) free. The layout engine has many equally good optima and used to keep whichever one it reached first, which is why the order looked arbitrary; the graph now picks the alphabetical one from among them. A column keeps its alphabetical order only while it costs at most 5% more edge crossings than the engine's own order, or 2 more, whichever is the larger allowance — where sorting would be expensive, the readable layout wins. The flat allowance is what makes this work on small graphs, where 5% of a handful of crossings rounds down to no leeway at all. Group boxes keep their exact bounds, and "+ N more" stubs follow the model they belong to
- Lineage: cards are now aligned to the start of their layer instead of centered in it. Card width follows the model name, so a column of differently-named models used to sit ragged on both sides; the column now reads as a list. Applies to left-to-right (left edges align) and top-to-bottom (top edges align) layouts alike; the group boxes themselves are unchanged

## [0.5.1] - 2026-08-25

- Fixed the tool window failing to open on 2026.2 IDEs with `NoClassDefFoundError: com/intellij/ui/jcef/JBCefJSQuery$Response`. Build 262 moved JCEF out of the platform core into a separate bundled plugin, so the lineage/docs webviews were no longer reachable from YADT's classloader; the plugin now declares that dependency (optionally, so it keeps loading on 2025.1–2026.1 where JCEF is still part of the core)
- The RUN button is no longer disabled when the **dbt Select** field is empty. An empty selector is a valid whole-project `dbt run`/`build`/`test`/`compile` (the command preview already showed it correctly, and the builder already omits `--select`), so it now runs — which also un-blocks the **dbt: Run / Stop** keymap action, previously a no-op in that state. Only **Preview** still requires a selector, because `dbt show` compiles a single node
- The RUN/**Stop** toggle is now clickable for runs that were started past the button — the lineage graph's **Show preview rows** starts a `dbt show` directly, which used to leave a greyed-out "Stop" with no way to cancel it

## [0.5.0] - 2026-06-22

- New editor action **Convert: ref ↔ Relation** — select a `{{ ref() }}`/`{{ source() }}` (or a `database.schema.table` name) and convert it in place to the other form; direction is detected from the selection. Lives in a new **YADT** right-click submenu that also gathers the existing **Copy for Target DB** and **Paste as dbt Refs** actions (their shortcuts are unchanged)
- Lineage/code intelligence: model output columns that are not consumed by any downstream model or exposure are now flagged as **potentially terminal** with a gutter icon in the `.sql` model file (a heuristic over direct children + exposures; columns it can't pin to a line collapse into a single file-level marker)

## [0.4.2] - 2026-06-10

- The Runner now defaults to the **Build** verb instead of Run, so the most common dbt command is preselected when you open the panel
- Default lineage depth is now `1+model+2` (one parent level, two child levels) instead of 2/1 — existing users still on the old default are migrated automatically, while any customized depth is preserved
- Two new keymap-configurable actions, **dbt: Run / Stop** and **dbt: Clear Output**, mirror the Runner panel's RUN and Clear buttons (same dbt run, same lineage overlays). They have no default shortcut — bind them under Settings → Keymap — and are also listed in the Tools menu

## [0.4.1] - 2026-06-02

- Rewrote the plugin/Marketplace description: clearer summary, current feature list (selector-driven lineage with autocomplete, build-status/freshness overlays, multi-engine support), and fixed an unescaped character

## [0.4.0] - 2026-06-02

- Autocomplete in the dbt selector field: fuzzy-matched suggestions for model names, tags
  (`tag:`), sources (`source:`), paths (`path:`), and fqns (`fqn:`) as you type, shown in a
  scrollable popup (up to 10 rows visible). Triggers from the second character of the token
  under the caret.
- Lineage: copied screenshots are now trimmed to the graph — the left/right edges sit 150px beyond the outermost nodes instead of including the full panel width
- Fix the Target dropdown being empty when a project keeps its `profiles.yml` in the project root (e.g. the duckdb starter projects) — profile resolution now checks the project directory before `~/.dbt`, matching dbt 1.5+

## [0.3.3] - 2026-06-01

- Lineage: node cards now grow to show the full model name (up to a max width; very long names still ellipsize), instead of truncating every name at a fixed width — so diagrams and screenshots are readable
- Lineage: a camera button in the graph controls copies the visible lineage to the clipboard as an image (clean, without the floating controls/minimap/sidebar) — handy for pasting into documentation
- Fix the "last run" lineage banner counting tests as models — it now counts only the built nodes (e.g. a build of one model plus its two tests shows "1 model", not "3 models")

## [0.3.2] - 2026-05-31

- Maintenance release; packaging and documentation updates only, no functional changes since 0.3.0

## [0.3.1] - 2026-05-31

- Fix plugin name so it passes JetBrains Marketplace validation (no functional changes since 0.3.0, which was never published)

## [0.3.0] - 2026-05-31

- Drive the lineage graph by typing a dbt selector — supports `tag:`, `path:`, `source:`, fqn and glob patterns, plus graph operators (`+model`, `2+model`, `model+3`); resolved live as you type, falling back to `dbt ls` for richer selectors
- Press Enter in the selector field to apply the selection; a "no nodes match" hint appears when a selector resolves to nothing
- Opening a schema `.yml` focuses all models it documents
- Failing or warning tests now show as a "!" triangle on model cards, kept separate from build-status color (a green model with a failing test stays green with a red triangle)
- Run-status colors are now confined to status mode, which is the new default

## [0.2.1] - 2026-05-29

- Forked from dbt-helper after 0.2.0 into a standalone plugin (new id `com.inazr.yadt`) — installs no longer receive the upstream Marketplace plugin's releases as updates
- Lineage graph now adapts to your full IDE theme (any Look-and-Feel), not just light/dark
- Run-status highlighting now matches exactly the models `dbt build --select <selector>` will build
- Run controls (target, verb, flags, selector, Run) are now always visible above the Lineage and Runner tabs
- More reliable "Group" dropdown in the lineage toolbar

## [0.2.0]

- Run / Test / Compile buttons in Runner tab for the current model
- Full-refresh checkbox — automatically shown for incremental models
- Native OS system notifications when dbt commands finish (configurable in settings)
- Manifest last updated date on Status tab
- Plugin icon for Settings → Plugins list (40x40 with transparent center)
- New setting: "Send system notifications" toggle

## [0.1.0]

- Initial release
- Interactive lineage graph with Cytoscape.js and dagre layout
- Code intelligence: autocompletion, go-to-definition, annotations, documentation hover
- Runner tab: run, test, compile models; dbt show preview; docs generate
- Full-refresh checkbox for incremental models
- Target selector from profiles.yml
- Copy for Target DB / Paste as dbt Refs actions
- Docs viewer tab with column info and descriptions
- Native OS notifications (macOS Notification Center) when commands finish
- Manifest date display on Status tab
- Support for .sql, .jinja, .jinja2 files
- Light and dark theme support for lineage graph
- Configurable lineage depth, edge style, layout direction

[Unreleased]: https://github.com/inazr/yadt/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/inazr/yadt/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/inazr/yadt/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/inazr/yadt/compare/v0.5.2...v0.6.0
[0.5.2]: https://github.com/inazr/yadt/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/inazr/yadt/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/inazr/yadt/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/inazr/yadt/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/inazr/yadt/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/inazr/yadt/compare/v0.3.3...v0.4.0
[0.3.3]: https://github.com/inazr/yadt/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/inazr/yadt/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/inazr/yadt/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/inazr/yadt/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/inazr/yadt/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/inazr/yadt/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/inazr/yadt/commits/v0.1.0
