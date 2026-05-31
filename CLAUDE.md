# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A JetBrains IDE plugin (`com.inazr.yadt`, marketed as **YADT — Yet Another dbt Tool**) that adds lineage visualization, code intelligence (completion / goto / docs / annotator) and a dbt CLI runner for dbt projects. Targets IntelliJ IDEA, PyCharm, DataSpell and any IntelliJ-Platform IDE on build 251+ (2025.1).

Stack: Kotlin 2.0 on JVM 21, IntelliJ Platform Gradle Plugin **2.x** (`org.jetbrains.intellij.platform` — not the legacy `org.jetbrains.intellij`). Versions are centralized in `gradle/libs.versions.toml`; plugin metadata lives in `gradle.properties`.

**Source package ≠ plugin id.** All Kotlin lives under `src/main/kotlin/com/dbthelper/…` (the original package, never repackaged), even though the plugin id and `pluginGroup` are `com.inazr.yadt`. Don't look for a `com/inazr` source dir — there isn't one.


## Claude Coding Rules
1. YAGNI
2. SOLID
3. KISS
4. DRY

## Common commands

```bash
./gradlew buildPlugin        # produces build/distributions/yadt-<version>.zip
./gradlew runIde             # launches a sandbox IDE with the plugin installed
./gradlew verifyPlugin       # runs IntelliJ Plugin Verifier against `recommended()` IDEs
./gradlew publishPlugin      # uploads to JetBrains Marketplace (needs PUBLISH_TOKEN env var)
```

Tests live under `src/test/kotlin` and run via `./gradlew test`. Coverage is sparse — currently only `SearchQueryParserTest`, `SearchIndexBuilderTest`, `RunResultsParserTest`, `DbtSelectionResolverTest`, and `LineageGraphBuilderTest` — so don't assume a change is safe just because tests pass. Place new tests alongside the existing ones; the Kotlin/Gradle defaults will pick them up.

To bump the plugin version, edit `pluginVersion` in `gradle.properties` (it is the single source of truth — `build.gradle.kts` reads it via `providers.gradleProperty`).

**Plugin identity lives in the Gradle build, NOT in `plugin.xml`.** The `intellijPlatform { pluginConfiguration { … } }` block in `build.gradle.kts` sets `id` (hardcoded string), `name` (← `pluginName` in `gradle.properties`), and `version` (← `pluginVersion`). `patchPluginXml` **overwrites** the `<id>`/`<name>`/`<version>` in `src/main/resources/META-INF/plugin.xml` with these values at build time, so the corresponding tags in the source XML are dead — editing them has no effect on the artifact. Always verify identity by extracting the built jar's `META-INF/plugin.xml`, not by reading the source. The `id` (currently `com.inazr.yadt`) must stay distinct from upstream's Marketplace plugin `com.dbthelper` (id #31663, vendor "Ruslan Hryshchenko") — reusing that id makes every IDE offer his releases as updates to this fork. Note: `pluginName` (and the embedded `<name>`) must use **only** the Marketplace's allowed descriptor characters — letters, digits, spaces, and `` .,+_-/:()#'&[]| ``. An em-dash (`—`) is **rejected** by `verifyPlugin` and JetBrains moderation (this shipped once as a broken `v0.3.0` tag), which is why the name uses a plain hyphen. `pluginName` is also ISO-8859-1, so any *allowed* non-ASCII char would need a `\uXXXX` escape (a raw byte gets mangled to `â`). The zip filename comes from `rootProject.name` in `settings.gradle.kts`, independent of `pluginName`.

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
Two tabs assembled in `DbtMainPanel` (which `DbtToolWindowFactory` instantiates):
- **Lineage** (`LineageTab`) — JCEF webview hosting `resources/js/lineage.html` + `lineage.js` (Cytoscape.js with the ELK layout, run in a Web Worker). Kotlin↔JS messaging is how clicks/navigation are wired. The vendored JS files (`cytoscape.min.js`, `cytoscape-elk.js`, `elk.bundled.js`, `elk.worker.js`) are deliberate — no npm/CDN at runtime.
- **Runner** (`DbtRunnerTab`) — uses `actions/DbtCommandRunner` to spawn the dbt CLI; streams stdout/stderr to a log component.

The factory sets the `ToolWindowContentUi.HIDE_ID_LABEL` client property so the bold "YADT" id label (the `<toolWindow id="YADT" ... displayName="YADT"/>` registered in `plugin.xml`) is not rendered before the tabs — keep this when touching the factory. (The stripe button still carries the `id` from `plugin.xml`, so the window stays discoverable.)

### `actions/` and `listeners/`
- `CopyWithRefsReplacedAction` / `PasteAsRefsAction` are bound to Ctrl/Cmd+Shift+C/V, and have `<add-to-group>` entries in `EditorPopupMenu` and `EditMenu`. If you add new editor actions, follow the same pattern.
- `DbtFileListener` tracks the current editor to drive the docs sidebar; `ManifestFileWatcher` invalidates the cache on VFS changes to `manifest.json`. Both are registered in `<projectListeners>` in `plugin.xml`.

### Run status, freshness & selectors — what colors the lineage cards
- **Run status has two sources that must agree on one vocabulary** (`success | warn | error | skipped`). `DbtRunStatusParser` is a stateless parser for the *live* human-readable `run`/`build`/`test` log lines (maps a printed `schema.identifier` → status as dbt streams). `RunResultsReconciler` reads the *authoritative* `target/run_results.json` afterwards. When a node gets multiple contributions, **worst status wins** (`error > warn > success > skipped`).
- **Test results are NOT rolled into the node's color.** A model's bar reflects only its own build/run status; failing tests surface as the separate "!" triangle overlay (`LineageTab.pushRunResultsToJs`), so a green model with a failing test stays green + red triangle. Don't "fix" this by merging test status into the reconciler.
- `RunResultsWatcher` (started by `RunResultsWatcherStarter`, a `ProjectActivity`) polls/watches `run_results.json` and fires `RunResultsUpdateListener`. `SourcesFreshnessParser` + `FreshnessDetailBuilder` do the equivalent for `sources.json` freshness.
- **Selector parsing is deliberately split.** `DbtSelectorParser` handles only the narrow graph-operator grammar we drive the graph with (`+model`, `2+model+3`, etc.) and returns `null` for anything richer (wildcards, `tag:`, `path:`, unions) rather than guessing. `DbtSelectionResolver` resolves a selector to a flat unique-id set two ways: `resolveLive` (in-memory, updates as you type) and `resolveViaCli` (authoritative `dbt ls` for any selector dbt understands). Graph operators are expanded during resolution, so the renderer only ever receives a fully-expanded id set.

### `settings/`
`DbtHelperSettings` is a `PersistentStateComponent` (project-level). `SettingsChangeListener` is fired by `DbtHelperConfigurable` after Apply — UI tabs subscribe to repaint when, e.g., lineage depth changes.

## Conventions that aren't obvious from the code

- **Do not bump `untilBuild`.** It is intentionally left empty in `gradle.properties` so the plugin loads on future IDE versions. (See commit `87fd113`.)
- **Path strings from the manifest are normalised** with `.replace('\\', '/')` because dbt on Windows writes backslashes into `original_file_path`. Preserve this when adding new map keys derived from manifest paths.
- The plugin must remain `DumbAware` where used (`DbtToolWindowFactory` already is) — manifest parsing must work during indexing.

## Releasing & signing

Release order: bump `pluginVersion` in `gradle.properties` → write notes under `## [Unreleased]` in `CHANGELOG.md` → `./gradlew patchChangelog` (renames `[Unreleased]` to `[<version>] - <date>` and inserts a fresh `[Unreleased]`; this feeds the IDE "What's New" panel via `changeNotes` in `build.gradle.kts`, which resolves `getOrNull(version) ?: getUnreleased()`) → `./gradlew buildPlugin` → `./gradlew verifyPlugin` → sign → upload.

- **`verifyPlugin` against `recommended()` can fail to *resolve* an IDE offline** (e.g. "Could not find idea:ideaIC:2025.x") — that's a download failure, not a compatibility problem. For a deterministic local run, temporarily replace `recommended()` with `ide("IC-2025.1.3")` (the build target, already cached) in `pluginVerification.ides`, then revert. A passing run prints `Compatible.` ("N usages of experimental API" is just a note).
- **The plugin name must pass descriptor validation** (see the identity note above): an em-dash makes `verifyPlugin`/moderation reject the build. This already cost a dead `v0.3.0` tag.
- **Signing reads three env vars** — `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` (PEM *contents*, not paths; wired in `build.gradle.kts`'s `signing {}`). `./gradlew signPlugin` writes `build/distributions/yadt-<version>-signed.zip`. **If any var is unset, `signPlugin` silently no-ops and still prints BUILD SUCCESSFUL — no file.** Export all three in the *same* shell, then sign; `--rerun-tasks` avoids a stale up-to-date skip.
- **`./gradlew verifyPluginSignature` is broken** in IntelliJ Platform plugin 2.6.0 when `certificateChain` is a string env var (it passes the PEM body as a stray CLI arg → exit 64 usage error, *not* a bad signature). Verify manually instead: `java -jar <gradle-cache>/marketplace-zip-signer-*-cli.jar verify -in build/distributions/yadt-<version>-signed.zip -cert <chain.crt>; echo $?` (silent + `0` = valid).
- **The first Marketplace upload is manual** at https://plugins.jetbrains.com/plugin/add — `publishPlugin` only updates an *existing* listing and needs `PUBLISH_TOKEN`. Generate a self-signed signing key once (`openssl genpkey` + `openssl req -x509`), keep it out of git, and reuse it for every release. After the first upload + moderation, future releases are `PUBLISH_TOKEN=… ./gradlew publishPlugin` (signs and uploads in one step; secrets never belong in a tracked file).
