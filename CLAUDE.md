# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A JetBrains IDE plugin (`com.inazr.yadt`, marketed as **YADT — Yet Another dbt Tool**) that adds lineage visualization, code intelligence (completion / goto / docs / annotator) and a dbt CLI runner for dbt projects. Targets IntelliJ IDEA, PyCharm, DataSpell and any IntelliJ-Platform IDE on build 251+ (2025.1).

Stack: Kotlin 2.0 on JVM 21, IntelliJ Platform Gradle Plugin **2.x** (`org.jetbrains.intellij.platform` — not the legacy `org.jetbrains.intellij`). Versions are centralized in `gradle/libs.versions.toml`; plugin metadata lives in `gradle.properties`.


## Claude Coding Rules
1. YAGNI
2. SOLID
3. KISS
4. DRY

## Common commands

```bash
./gradlew buildPlugin        # produces build/distributions/dbt-helper-<version>.zip
./gradlew runIde             # launches a sandbox IDE with the plugin installed
./gradlew verifyPlugin       # runs IntelliJ Plugin Verifier against `recommended()` IDEs
./gradlew publishPlugin      # uploads to JetBrains Marketplace (needs PUBLISH_TOKEN env var)
```

Tests live under `src/test/kotlin` and run via `./gradlew test`. Coverage is sparse — currently only `SearchQueryParserTest`, `SearchIndexBuilderTest`, and `RunResultsParserTest` — so don't assume a change is safe just because tests pass. Place new tests alongside the existing ones; the Kotlin/Gradle defaults will pick them up.

To bump the plugin version, edit `pluginVersion` in `gradle.properties` (it is the single source of truth — `build.gradle.kts` reads it via `providers.gradleProperty`).

**Plugin identity lives in the Gradle build, NOT in `plugin.xml`.** The `intellijPlatform { pluginConfiguration { … } }` block in `build.gradle.kts` sets `id` (hardcoded string), `name` (← `pluginName` in `gradle.properties`), and `version` (← `pluginVersion`). `patchPluginXml` **overwrites** the `<id>`/`<name>`/`<version>` in `src/main/resources/META-INF/plugin.xml` with these values at build time, so the corresponding tags in the source XML are dead — editing them has no effect on the artifact. Always verify identity by extracting the built jar's `META-INF/plugin.xml`, not by reading the source. The `id` (currently `com.inazr.yadt`) must stay distinct from upstream's Marketplace plugin `com.dbthelper` (id #31663, vendor "Ruslan Hryshchenko") — reusing that id makes every IDE offer his releases as updates to this fork. Note: `pluginName` is a Java-properties value (ISO-8859-1), so non-ASCII like the em-dash in "YADT — Yet Another dbt Tool" must be written as the escape `\u2014` (a raw `—` byte gets mangled to `â`). The zip filename comes from `rootProject.name` in `settings.gradle.kts`, independent of `pluginName`.

## Architecture

### Entry point: `src/main/resources/META-INF/plugin.xml`
This is the wiring file — every Kotlin class is registered here as a service, extension, action, or listener. **When adding new functionality, this file usually needs an edit alongside the Kotlin code.** Notable gotchas baked in here:

- Code-intelligence extensions (`completion.contributor`, `psi.referenceContributor`, `annotator`, `lang.documentationProvider`) are registered **three times** — once each for languages `TEXT`, `Jinja2`, and `SQL`. This is intentional: IDEA Community assigns `.sql` to `TEXT` (no SQL plugin), while DataSpell/PyCharm assign it to `Jinja2`/`SQL`. Dropping any of the three breaks one IDE.
- The v2 documentation API (`platform.backend.documentation.targetProvider` + `psiTargetProvider`) is required for hover-without-modifier in DataSpell, otherwise the bundled SQL plugin hijacks hover before our PSI provider runs. See the comment in `plugin.xml` before changing the docs path.

### `core/` — manifest ingestion (the heart of the plugin)
- `ManifestService` (project-level `@Service`, in `core/ManifestService.kt`) parses `<dbt-root>/target/manifest.json` on a background coroutine, builds a `ManifestIndex` (nodes, sources, macros, exposures + parent/child/path/relation maps), merges `catalog.json` via `CatalogParser`, then publishes `onManifestUpdated` on the project message bus (`ManifestUpdateListener.TOPIC`). Consumers (`LineageTab`, `DocsTab`, code-intel) subscribe — do not poll `cachedIndex` directly from UI code, listen to the topic.
- `DbtProjectLocator` finds dbt roots via `FilenameIndex` for `dbt_project.yml`. It supports a multi-project workspace and a settings override, but **`ManifestService` currently parses only the first root** (see TODO at `core/ManifestService.kt:67`). Watch for this when touching multi-project behaviour.
- `core/model/` holds data classes mirroring the dbt manifest schema. Jackson + `KotlinModule` is used directly — no generated DTOs.

### `codeintel/` — language integration
`DbtJinjaUtils.kt` is the regex-based shared parser (`ref()`, `source()`, `macro()` calls + completion context detection). Every code-intel class delegates token recognition here rather than parsing PSI — this is what lets the same code run unchanged across `TEXT`/`Jinja2`/`SQL` languages.

### `toolwindow/` — the bottom tool window
Two tabs assembled in `DbtToolWindowFactory`:
- **Lineage** (`LineageTab`) — JCEF webview hosting `resources/js/lineage.html` + `lineage.js` (Cytoscape.js with the ELK layout, run in a Web Worker). Kotlin↔JS messaging is how clicks/navigation are wired. The vendored JS files (`cytoscape.min.js`, `cytoscape-elk.js`, `elk.bundled.js`, `elk.worker.js`) are deliberate — no npm/CDN at runtime.
- **Runner** (`DbtRunnerTab`) — uses `actions/DbtCommandRunner` to spawn the dbt CLI; streams stdout/stderr to a log component.

The factory sets the `ToolWindowContentUi.HIDE_ID_LABEL` client property so the bold "YADT" id label (the `<toolWindow id="YADT" ... displayName="YADT"/>` registered in `plugin.xml`) is not rendered before the tabs — keep this when touching the factory. (The stripe button still carries the `id` from `plugin.xml`, so the window stays discoverable.)

### `actions/` and `listeners/`
- `CopyWithRefsReplacedAction` / `PasteAsRefsAction` are bound to Ctrl/Cmd+Shift+C/V, and have `<add-to-group>` entries in `EditorPopupMenu` and `EditMenu`. If you add new editor actions, follow the same pattern.
- `DbtFileListener` tracks the current editor to drive the docs sidebar; `ManifestFileWatcher` invalidates the cache on VFS changes to `manifest.json`. Both are registered in `<projectListeners>` in `plugin.xml`.

### `settings/`
`DbtHelperSettings` is a `PersistentStateComponent` (project-level). `SettingsChangeListener` is fired by `DbtHelperConfigurable` after Apply — UI tabs subscribe to repaint when, e.g., lineage depth changes.

## Conventions that aren't obvious from the code

- **Do not bump `untilBuild`.** It is intentionally left empty in `gradle.properties` so the plugin loads on future IDE versions. (See commit `87fd113`.)
- **Path strings from the manifest are normalised** with `.replace('\\', '/')` because dbt on Windows writes backslashes into `original_file_path`. Preserve this when adding new map keys derived from manifest paths.
- The plugin must remain `DumbAware` where used (`DbtToolWindowFactory` already is) — manifest parsing must work during indexing.
