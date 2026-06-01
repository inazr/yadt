# YADT — Yet Another dbt Tool Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/inazr/yadt/compare/v0.3.2...HEAD
[0.3.2]: https://github.com/inazr/yadt/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/inazr/yadt/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/inazr/yadt/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/inazr/yadt/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/inazr/yadt/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/inazr/yadt/commits/v0.1.0
