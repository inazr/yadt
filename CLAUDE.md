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

Tests live under `src/test/kotlin` and run via `./gradlew test`. Coverage is partial and skewed: pure logic (parsers, selectors, graph building, command assembly) has tests, UI and platform wiring have almost none — so don't assume a change is safe just because tests pass. Place new tests alongside the existing ones; the Kotlin/Gradle defaults will pick them up.

To bump the plugin version, edit `pluginVersion` in `gradle.properties` (it is the single source of truth — `build.gradle.kts` reads it via `providers.gradleProperty`).

**Plugin identity lives in the Gradle build, NOT in `plugin.xml`.** The `intellijPlatform { pluginConfiguration { … } }` block in `build.gradle.kts` sets `id` (hardcoded string), `name` (← `pluginName` in `gradle.properties`), and `version` (← `pluginVersion`). `patchPluginXml` **overwrites** the `<id>`/`<name>`/`<version>` in `src/main/resources/META-INF/plugin.xml` with these values at build time, so the corresponding tags in the source XML are dead — editing them has no effect on the artifact. Always verify identity by extracting the built jar's `META-INF/plugin.xml`, not by reading the source. The `id` (currently `com.inazr.yadt`) must stay distinct from upstream's Marketplace plugin `com.dbthelper` (id #31663, vendor "Ruslan Hryshchenko") — reusing that id makes every IDE offer his releases as updates to this fork. Note: `pluginName` (and the embedded `<name>`) must use **only** the Marketplace's allowed descriptor characters — letters, digits, spaces, and `` .,+_-/:()#'&[]| ``. An em-dash (`—`) is **rejected** by `verifyPlugin` and JetBrains moderation (this shipped once as a broken `v0.3.0` tag), which is why the name uses a plain hyphen. `pluginName` is also ISO-8859-1, so any *allowed* non-ASCII char would need a `\uXXXX` escape (a raw byte gets mangled to `â`). The zip filename comes from `rootProject.name` in `settings.gradle.kts`, independent of `pluginName`.

## Architecture

### Entry point: `src/main/resources/META-INF/plugin.xml`
This is the wiring file — every Kotlin class is registered here as a service, extension, action, or listener. **When adding new functionality, this file usually needs an edit alongside the Kotlin code.** Notable gotchas baked in here:

- Code-intelligence extensions (`psi.referenceContributor`, `annotator`, `lang.documentationProvider`) are registered **three times** — once each for languages `TEXT`, `Jinja2`, and `SQL`. This is intentional: IDEA Community assigns `.sql` to `TEXT` (no SQL plugin), while DataSpell/PyCharm assign it to `Jinja2`/`SQL`. Dropping any of the three breaks one IDE. `completion.contributor` is the exception: it is registered **once** with `language="any"`, which already covers all three. A fourth, `language="yaml"` set lives in the optional `yadt-charts-yaml.xml` for dbt Charts boards (see `charts/` below).
- The v2 documentation API (`platform.backend.documentation.targetProvider` + `psiTargetProvider`) is required for hover-without-modifier in DataSpell, otherwise the bundled SQL plugin hijacks hover before our PSI provider runs. See the comment in `plugin.xml` before changing the docs path.

### `core/` — manifest ingestion (the heart of the plugin)
- `ManifestService` (project-level `@Service`, in `core/ManifestService.kt`) parses `<dbt-root>/target/manifest.json` on a background coroutine, builds a `ManifestIndex` (nodes, sources, macros, exposures + parent/child/path/relation maps), merges `catalog.json` via `CatalogParser`, then publishes `onManifestUpdated` on the project message bus (`ManifestUpdateListener.TOPIC`). Consumers (`LineageTab`, code-intel) subscribe — do not poll `cachedIndex` directly from UI code, listen to the topic.
- `DbtProjectLocator` finds dbt roots via `FilenameIndex` for `dbt_project.yml`. It supports a multi-project workspace and a settings override, but **`ManifestService` currently parses only the first root** (see TODO at `core/ManifestService.kt:67`). Watch for this when touching multi-project behaviour.
- `core/model/` holds data classes mirroring the dbt manifest schema. Jackson + `KotlinModule` is used directly — no generated DTOs.

### `codeintel/` — language integration
`DbtJinjaUtils.kt` is the regex-based shared parser (`ref()`, `source()`, `macro()` calls + completion context detection). Every code-intel class delegates token recognition here rather than parsing PSI — this is what lets the same code run unchanged across `TEXT`/`Jinja2`/`SQL` languages, and inside YAML block scalars (dbt Charts board queries).

Every entry point gates on **`isDbtCodeIntelFile(vFile)`** (= `.sql`/`.jinja`/`.jinja2` **or** a dbt Charts board), not on `isDbtTemplateFile` directly. Boards pass `allowMacros = false` to `detectCompletionContext`: `dct` resolves board Jinja (`{{ filter(...) }}` etc.), not dbt, so dbt macros are never offered there.

### `charts/` — dbt Charts board editing
[dbt Charts](https://dbtcharts.com) (`dct`, package `dbt-charts`) describes dashboards as YAML boards. This package makes board files first-class: schema-backed completion/docs/errors plus the `codeintel/` ref/source features. It does not reimplement rendering or run `dct validate`: the board preview (`charts/preview/`) shows the user's own `dct serve`.

- **`DbtChartsBoardLocator`** is the single gate for "is this a board": `.yml`/`.yaml` below a `charts/` dir whose *parent* holds `dbt_charts.yml` (dct hardcodes `CHARTS_SUBDIR = "charts"`), excluding `meta.yml`/`meta.yaml` (cascade files). It is generic over the directory type so tests run on `java.nio.file.Path` while the IDE walks the cached VFS — it runs per PSI element in the reference contributor, so it must never hit the disk.
- **`DctSchemaResolver`** (project light service) finds the board JSON Schema. `dct` has no command that prints it; the schemas ship as package data (`dbt_charts/data/schemas/yaml/<ver>.json` + `manifest.json` with `RELEASED`/`DEV` entries and sha256). Order: (1) installed `dct` → venv via `DctVenvLocator` (realpath of the uv/pipx symlink, then shebang, then `uv tool dir`, then pipx) → newest **RELEASED** entry by numeric version, sha256-verified; (2) only if the `downloadChartsSchema` setting is on: GitHub raw download, sha256-verified, cached under `<system>/yadt/dbt-charts-schema/`, at most once per IDE session. Pure logic lives in `DctSchemaManifest` and `DctVenvLocator` (tested); the service only wires processes/HTTP/VFS. It publishes `DctSchemaListener.TOPIC` on change and never blocks the EDT (`getProviders` reads a cached `VirtualFile`).
- **`DbtChartsSchemaStarter`** (`postStartupActivity`) runs the first resolve, re-resolves on `SettingsChangeListener`, and shows the one-time "needs a schema" notification — only in projects that contain a `dbt_charts.yml`.
- **Two independent optional descriptors**, both deliberate (guarded by `ChartsDependencyDeclarationTest`):
  - `<depends optional="true" config-file="yadt-charts-schema.xml">com.intellij.modules.json</depends>` → `DbtChartsSchemaProviderFactory` (`JavaScript.JsonSchema.ProviderFactory`) + `DbtChartsSchemaResetter` (listener calling `JsonSchemaService.Impl.get(project).reset()`, which also restarts highlighting — don't add a separate `DaemonCodeAnalyzer.restart()`, deprecated in 262 and redundant).
  - `<depends optional="true" config-file="yadt-charts-yaml.xml">org.jetbrains.plugins.yaml</depends>` → `language="yaml"` registrations of `DbtReferenceContributor`, `DbtDocumentationProvider`, `DbtAnnotator` (not `TerminalColumnAnnotator`).
  - Don't merge or nest them: in 2025.1.3 the YAML plugin core only depends on the `intellij.json.split` module, so neither plugin guarantees the other. Only classes registered in these sub-descriptors may import `com.jetbrains.jsonSchema.*`; the locator/resolver/starter must stay loadable without JSON/YAML.
- **Schema quirks users will notice:** board `rows` items are nested `anyOf`s, so an invalid chart `type` is reported on the chart's sibling keys (or the row), not on the bad value; hover docs still resolve through the matching branch. The status bar shows "Schema: dbt Charts board(bundled)" — "bundled" is just how the IDE labels `SchemaType.embeddedSchema`, the schema comes from `dct`.
- **Board preview (`charts/preview/`).** `DbtChartsPreviewEditorProvider` (registered in `plugin.xml`, *not* `yadt-jcef.xml` — that only loads on 262+) wraps the text editor and a JCEF `DbtChartsPreviewEditor` in `TextEditorWithPreview`, gated on `DbtChartsBoardLocator` + `JBCefApp.isSupported()`. `DctServeService` runs one `dct serve --host 127.0.0.1 --port <free>` per project root, leased by open previews and killed with the last one; a dead/failed server is replaced on the next acquire (Retry). Refresh is dct's own livereload (`/__livereload` SSE, fires on any project file change) after the editor's ~1 s typing-pause `saveDocument` — YADT never pushes HTML. URL mapping (`charts/fin/q 1.yml` → `/fin/q%201/`) is `DctBoardUrl`; the `dct` lookup shared with the schema resolver is `DctExecutable`.

### `toolwindow/` — the bottom tool window
Two tabs assembled in `DbtMainPanel` (which `DbtToolWindowFactory` instantiates):
- **Lineage** (`LineageTab`) — JCEF webview hosting `resources/js/lineage.html` + `lineage.js` (Cytoscape.js with the ELK layout, run in a Web Worker). Kotlin↔JS messaging is how clicks/navigation are wired. The vendored JS files (`cytoscape.min.js`, `cytoscape-elk.js`, `elk.bundled.js`, `elk.worker.js`) are deliberate — no npm/CDN at runtime.
- **Runner** (`DbtRunnerTab`) — uses `actions/DbtCommandRunner` to spawn the dbt CLI; streams stdout/stderr to a log component.

Lineage layout runs in two steps. ELK does the layering and crossing minimization; a pass in `lineage.js` between the `// --- ALPHA-SORT BEGIN/END` markers then reorders each column **alphabetically on the finished layout** — ELK is never reconfigured. It permutes only nodes sharing a column *and* a cluster box, re-stacks them into the slots they already occupied (so box bounds and spacing survive), drags each node's exclusive `+ N more` stubs along, and keeps the result only while the total edge-crossing count stays within the budget — `CROSSING_TOLERANCE` (5%) or `CROSSING_SLACK` (+2), whichever is more generous. Both are needed: the percentage is nearly meaningless on small graphs (5% of 16 crossings allows not one more), the flat slack would be too permissive on large ones. Grouping a column relies on nodes sharing their *leading* edge, which is what `elkNodeOptionsFor` aligns them to — the two features are coupled, don't remove the alignment and leave the sort in place. `src/test/js/lineage-order-test.js` extracts that block verbatim and drives it against the vendored ELK + Cytoscape (`node src/test/js/lineage-order-test.js`); it is **not** part of `./gradlew test` because the Gradle build must not require a Node runtime. Keep the markers intact or the test silently tests nothing.

The **docs sidebar** is not a third tab and has no Kotlin class of its own: it is rendered *inside* `lineage.html`/`lineage.js` and fed from Kotlin by `LineageTab.pushDocsToSidebar()` via `core/DocsPayloadBuilder`. (A `DocsTab.kt` existed until 0.5.1 but was never instantiated — it was deleted, don't resurrect it.)

The factory sets the `ToolWindowContentUi.HIDE_ID_LABEL` client property so the bold "YADT" id label (the `<toolWindow id="YADT" ... displayName="YADT"/>` registered in `plugin.xml`) is not rendered before the tabs — keep this when touching the factory. (The stripe button still carries the `id` from `plugin.xml`, so the window stays discoverable.)

**JCEF is a separate plugin from build 262 (2026.2) on.** `com.intellij.ui.jcef.*` used to be part of the platform core (`lib/app-client.jar` in 251); 262 moved it into the bundled plugin `com.intellij.modules.jcef`, whose classes a plugin classloader only reaches if it declares the dependency. `plugin.xml` therefore carries `<depends optional="true" config-file="yadt-jcef.xml">com.intellij.modules.jcef</depends>` — **optional** because the id does not exist before 262 and a required `<depends>` on a missing plugin makes those IDEs refuse to load YADT entirely (`sinceBuild` is 251). `yadt-jcef.xml` is an intentionally empty sub-descriptor; without a `config-file` the verifier emits a structure warning. Neither is dead code — removing either reintroduces `NoClassDefFoundError: com/intellij/ui/jcef/JBCefJSQuery$Response` the moment the tool window opens on 2026.2.

### `actions/` and `listeners/`
- `CopyWithRefsReplacedAction` / `PasteAsRefsAction` are bound to Ctrl/Cmd+Shift+C/V, and have `<add-to-group>` entries in `EditorPopupMenu` and `EditMenu`. If you add new editor actions, follow the same pattern.
- `DbtFileListener` tracks the current editor to drive the docs sidebar; `ManifestFileWatcher` invalidates the cache on VFS changes to `manifest.json`. Both are registered in `<projectListeners>` in `plugin.xml`.

### Run status, freshness & selectors — what colors the lineage cards
- **Run status has two sources that must agree on one vocabulary** (`success | warn | error | skipped`). `DbtRunStatusParser` is a stateless parser for the *live* human-readable `run`/`build`/`test` log lines (maps a printed `schema.identifier` → status as dbt streams). `RunResultsParser` reads the *authoritative* `target/run_results.json` afterwards (statuses dbt reports that we don't color, e.g. `no-op`, are dropped), and `nodeStatuses` turns that into the card colors. Both produce `RunStatus`, whose `wire` value is what the webview receives.
- **Test results are NOT rolled into the node's color.** A model's bar reflects only its own build/run status; failing tests surface as the separate "!" triangle overlay (`LineageTab.pushRunResultsToJs`), so a green model with a failing test stays green + red triangle. Don't "fix" this by merging test status into `nodeStatuses`.
- `RunResultsWatcher` (started by `RunResultsWatcherStarter`, a `ProjectActivity`) polls/watches `run_results.json` and fires `RunResultsUpdateListener`. `SourcesFreshnessParser` + `FreshnessDetailBuilder` do the equivalent for `sources.json` freshness.
- **Selector parsing is deliberately split.** `DbtSelectorParser` handles only the narrow graph-operator grammar we drive the graph with (`+model`, `2+model+3`, etc.) and returns `null` for anything richer (wildcards, `tag:`, `path:`, unions) rather than guessing. `DbtSelectionResolver` resolves a selector to a flat unique-id set two ways: `resolveLive` (in-memory, updates as you type) and `resolveViaCli` (authoritative `dbt ls` for any selector dbt understands). Graph operators are expanded during resolution, so the renderer only ever receives a fully-expanded id set.

### `settings/`
`DbtHelperSettings` is a `PersistentStateComponent` (project-level). `SettingsChangeListener` is fired by `DbtHelperConfigurable` after Apply — UI tabs subscribe to repaint when, e.g., lineage depth changes.

## Conventions that aren't obvious from the code

- **Do not bump `untilBuild`.** It is intentionally left empty in `gradle.properties` so the plugin loads on future IDE versions. (See commit `87fd113`.)
- **Path strings from the manifest are normalised** with `.replace('\\', '/')` because dbt on Windows writes backslashes into `original_file_path`. Preserve this when adding new map keys derived from manifest paths.
- The plugin must remain `DumbAware` where used (`DbtToolWindowFactory` already is) — manifest parsing must work during indexing.
- **Never bundle kotlinx-coroutines.** Services that need a coroutine scope take the platform-injected one as a constructor parameter (`ManifestService(project, scope)`, `DctSchemaResolver(project, cs)`). That only works because YADT uses the IDE's own coroutines: a bundled jar makes the parameter a different class than the platform's, the IDE then fails with "does not define any of supported signatures" and the service never exists — invisible to `verifyPlugin` (this shipped in 0.6.0). `CoroutinesNotBundledTest` guards the build files.

## Releasing & signing

Release order: bump `pluginVersion` in `gradle.properties` → write notes under `## [Unreleased]` in `CHANGELOG.md` → `./gradlew patchChangelog` (renames `[Unreleased]` to `[<version>] - <date>` and inserts a fresh `[Unreleased]`; this feeds the IDE "What's New" panel via `changeNotes` in `build.gradle.kts`, which resolves `getOrNull(version) ?: getUnreleased()`) → `./gradlew buildPlugin` → `./gradlew verifyPlugin` → sign → upload. Then tag and publish the source release: `git tag v<version>` on the release commit, `git push origin main v<version>`, and `gh release create v<version> build/distributions/yadt-<version>.zip --title "v<version>" --notes-file <that version's CHANGELOG section>` (title is the bare tag name, body is that version's bullets, one asset). Don't skip this — the CHANGELOG's generated compare links are built from version numbers *without* checking that the tags exist, so a missing tag silently 404s its own link.

- **`verifyPlugin` against `recommended()` can fail to *resolve* an IDE offline** (e.g. "Could not find idea:ideaIC:2025.x") — that's a download failure, not a compatibility problem. For a deterministic local run, temporarily replace `recommended()` with `ide("IC-2025.1.3")` (the build target, already cached) in `pluginVerification.ides`, then revert. You can only *replace*, not add: a second entry alongside the build target fails with "'intellijPlatformDependency' configuration already contains … IC-2025.1.3". To check against the IDEs installed on this machine, skip Gradle: `scripts/verify-installed-ides.sh` (after `buildPlugin`) runs the verifier CLI offline against every IntelliJ IDEA/PyCharm/DataSpell app in `~/Applications` and `/Applications`, or against the `.app` paths you pass, with reports under `build/verifier-reports/`. A passing run prints `Compatible` ("N usages of experimental API" is just a note: the v2 documentation API is still experimental).
- **`verifyPlugin` cannot catch classloader-visibility bugs.** It resolves against the whole IDE classpath, not the per-plugin classloader graph, so a build that dies at runtime with `NoClassDefFoundError` for a package it never declared a dependency on still reports `Compatible.` (verified against the broken 0.4.2 on 2026.2). A missing module dependency surfaces at most as an informational "Missing optional dependency" line. Descriptor wiring needs a real install, or a test that asserts the declaration — see `JcefDependencyDeclarationTest`.
- **The plugin name must pass descriptor validation** (see the identity note above): an em-dash makes `verifyPlugin`/moderation reject the build. This already cost a dead `v0.3.0` tag.
- **Signing reads three env vars** — `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` (PEM *contents*, not paths; wired in `build.gradle.kts`'s `signing {}`). `./gradlew signPlugin` writes `build/distributions/yadt-<version>-signed.zip`. **If any var is unset, `signPlugin` silently no-ops and still prints BUILD SUCCESSFUL — no file.** Export all three in the *same* shell, then sign; `--rerun-tasks` avoids a stale up-to-date skip.
- **`./gradlew verifyPluginSignature` is broken** in IntelliJ Platform plugin 2.6.0 when `certificateChain` is a string env var (it passes the PEM body as a stray CLI arg → exit 64 usage error, *not* a bad signature). Verify manually instead: `java -jar <gradle-cache>/marketplace-zip-signer-*-cli.jar verify -in build/distributions/yadt-<version>-signed.zip -cert <chain.crt>; echo $?` (silent + `0` = valid).
- **The first Marketplace upload is manual** at https://plugins.jetbrains.com/plugin/add — `publishPlugin` only updates an *existing* listing and needs `PUBLISH_TOKEN`. Generate a self-signed signing key once (`openssl genpkey` + `openssl req -x509`), keep it out of git, and reuse it for every release. After the first upload + moderation, future releases are `PUBLISH_TOKEN=… ./gradlew publishPlugin` (signs and uploads in one step; secrets never belong in a tracked file).
