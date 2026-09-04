# CallerView

IntelliJ IDEA plugin that analyzes and visualizes the **caller chain (searching upward)** of a Java method.
Right-click any method → `CallerView: Show Call Chain` to see the full call chain in a graph plus a side tree,
with every chain that affects a configured **core method** highlighted in red.

> Tech stack: Java 8 + Maven, depending only on IntelliJ Platform module artifacts
> (`com.jetbrains.intellij.platform:core` and `com.jetbrains.intellij.java:java`, `provided` scope),
> with no third-party jars.

## Features

1. **Right-click a method to view its caller chain**: in the editor, right-click → `CallerView: Show Call Chain`; it locates the method at the caret and expands all of its callers upward.
2. **Configurable upward depth**: `Settings | Tools | CallerView` → "Upward analysis depth", default `-1` (all levels); beyond the depth limit or the node cap (safety valve 20000) a truncated marker is shown.
3. **Configurable core methods**: on the same page, "Core methods (one per line)"; supports `ClassName.methodName` or FQN-substring matching; whenever a chain contains/affects a core method, that chain (nodes + edges) is **highlighted in red**.
4. **Graph view**: pure Java2D horizontal tidy-tree layout; the target method (★) is on the left and callers extend to the right; supports wheel zoom (anchored to the cursor), drag to pan, click to select, double-click to jump, and fit-to-view.
5. **Side tree panel**: mirrors the call chain in sync with the graph; core/affected paths are highlighted in red, bidirectionally linked selection with the graph, and double-click to jump to source.
6. **Local install / Marketplace upload**: `mvn package` directly produces an installable `target/CallerView-1.0.0.zip`.

## Compatibility

- Compile target is `1.8`, built against the IntelliJ 2020.3 SDK (the last Platform that bundles JBR 8).
- `plugin.xml` sets `<idea-version since-build="203"/>` with no `until-build`; combined with stable APIs
  (`AnAction`, `ToolWindow`, `PersistentStateComponent`, `PsiMethod`, `MethodReferencesSearch`, plain Swing/AWT drawing),
  the generated Java 8 bytecode runs on 2020.3 and all later IDEs (verified that the platform artifacts
  `core`/`java` exist together at `203.7148.57`).
- Before publishing to the Marketplace it is recommended to re-verify the target version range with
  [Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html).

## Project Layout

```
callerView/
├── pom.xml
├── src/assembly/plugin.xml                 # Packs target/CallerView.jar into a CallerView/lib/CallerView.jar zip
└── src/main/
    ├── resources/
    │   ├── META-INF/
    │   │   ├── plugin.xml                  # Plugin descriptor, dependencies, extensions, editor popup action
    │   │   ├── pluginIcon.svg              # Marketplace / local-install icon (light)
    │   │   └── pluginIcon_dark.svg         # Dark
    │   └── icons/callChain.svg             # Action / tool-window icon
    └── java/com/callerview/
        ├── CallerViewIcons.java
        ├── core/        CallNode, CallChainAnalyzer
        ├── config/      CallerViewSettings, CallerViewConfigurable
        ├── action/      ShowCallChainAction
        ├── ui/          CallGraphCanvas, CallChainTreePanel, CallChainPanel
        └── toolwindow/  CallChainViewService, CallChainToolWindowFactory
```

## Building

Requirements: JDK 8 (or newer; compile target is fixed to 1.8) + Maven 3.6+.

```bash
mvn clean package
```

The first build downloads the platform artifacts from `https://www.jetbrains.com/intellij-repository/releases`
and `https://cache-redirector.jetbrains.com/intellij-dependencies` (large, please be patient). Artifacts:

- `target/CallerView.jar` (the plugin itself, containing `META-INF/plugin.xml` and the icons)
- `target/CallerView-1.0.0.zip` (the directly installable distribution, structured as `CallerView/lib/CallerView.jar`)

> The zip must keep its top-level `CallerView/` directory: IDEA treats the zip's first top-level entry as the plugin
> directory, and without it you get "Fail to load plugin descriptor from file".

> If compilation reports "package xxx does not exist", the platform module containing a given class is not pulled in
> transitively by `core`/`java`. Add the module artifact to `<dependencies>` in `pom.xml` (coordinate rules are in the
> [IntelliJ Artifacts doc](https://plugins.jetbrains.com/docs/intellij/intellij-artifacts.html)), for example:
>
> ```xml
> <dependency>
>   <groupId>com.jetbrains.intellij.platform</groupId>
>   <artifactId>ide</artifactId>
>   <version>${intellij.version}</version>
>   <scope>provided</scope>
> </dependency>
> ```

## Installation

### Local install

`File | Settings | Plugins | ⚙ | Install Plugin from Disk…` → select `target/CallerView-1.0.0.zip` → restart the IDE.

### From the Marketplace

Upload `CallerView-1.0.0.zip` (or a signed release) to [JetBrains Marketplace](https://plugins.jetbrains.com/). Publishing requires:

1. Generate a plugin signing key pair in your [account](https://plugins.jetbrains.com/author/me);
2. (Optional) configure a signing certificate and repackage with Maven, or complete dual signing on the Marketplace.

## Usage

1. Open any Java project.
2. Place the caret inside a method body (or select the method name).
3. Right-click → `CallerView: Show Call Chain`.
4. The `CallerView` tool window opens at the bottom: graph on the left, tree on the right, and the two stay in sync.
   - Graph: wheel to zoom, drag on empty space to pan, click to select, double-click to jump to source, and toolbar
     `Zoom In / Zoom Out / Reset View`.
   - Tree: click to select (slaved to the graph), double-click to jump, auto-expands the whole chain.
5. Adjust depth and core methods in `Settings | Tools | CallerView`; once a core method is matched, the affected chains turn red automatically.

## Notes & Limitations

- The caller search is scoped to the **project sources** (`GlobalSearchScope.projectScope`, test sources included);
- Cycles (including direct/indirect recursion) are broken per branch; the same method may appear repeatedly in
  different branches to preserve the full path information.
- The default `-1` means unlimited depth, guarded by the 20000-node safety cap; set an explicit depth for very large call graphs.
- The stored `PsiMethod` is used for double-click navigation; a jump is silently skipped once the PSI becomes invalid after edits.
- **Scenarios static analysis cannot cover**: reflection calls, framework calls driven by XML/config files
  (Spring, MyBatis, etc.), callbacks originating inside binary dependency libraries (the search scope is the
  project sources), and the ambiguity of dynamic dispatch (which implementation an interface call resolves to is
  only known at runtime).