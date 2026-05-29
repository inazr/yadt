# YADT — Yet Another dbt Tool Changelog

## [Unreleased]

## [0.2.1] - 2026-05-29

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
