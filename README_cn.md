# CallerView

IntelliJ IDEA 插件：分析并可视化一个 Java 方法的 **被调用链路（向上查找 caller）**。
右键某个方法 → `CallerView: Show Call Chain`，即可在图形 + 侧边树中查看完整的调用链路，
并标红所有会影响到“核心方法”的链路。

> 技术栈：Java 8 + Maven，仅依赖 IntelliJ Platform 模块构件（`com.jetbrains.intellij.platform:core`
> 与 `com.jetbrains.intellij.java:java`，`provided` 作用域），无任何第三方 jar。

## 功能一览

1. **右键方法查看调用链**：在编辑器中右键 → `CallerView: Show Call Chain`，定位光标处方法并向上展开所有调用者。
2. **可配置向上层级**：`Settings | Tools | CallerView` → “向上分析层级”，默认 `-1`（全部）；超过层级或节点上限（安全阀 20000）处显示截断标记。
3. **可配置核心方法**：同页“核心方法（每行一个）”，支持 `ClassName.methodName` 或 FQN 子串匹配；任一链路包含/影响核心方法时，该链路（节点 + 连线）**标红**。
4. **图形展示**：纯 Java2D 横向 tidy-tree 布局，目标方法（★）在左、调用者向右延伸；支持滚轮缩放（光标锚定）、拖拽平移、点击选中、双击跳转、自适应视图。
5. **侧边树面板**：与图形同步镜像调用链，核心/受影响路径红色高亮，与图形双向选中联动，双击跳转源码。
6. **本地安装 / 上架市场**：`mvn package` 直接产出可安装的 `target/CallerView-1.0.0.zip`。

## 兼容性

- 编译目标 `1.8`，SDK 选用 IntelliJ 2020.3（最后一个内置 JBR 8 的平台）。
- `plugin.xml` 中 `<idea-version since-build="203"/>`，不设 `until-build`，配合稳定 API（`AnAction`、`ToolWindow`、`PersistentStateComponent`、`PsiMethod`、`MethodReferencesSearch`、纯 Swing/AWT 绘图），
  生成的 Java 8 字节码可运行于 2020.3 及以后的所有 IDE（已验证平台构件 `core`/`java` 在 `203.7148.57` 同时存在）。
- 上架市场前建议用 [Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html) 对目标版本范围再核验一次。

## 目录结构

```
callerView/
├── pom.xml
├── src/assembly/plugin.xml                 # 把 target/CallerView.jar 打成 CallerView/lib/CallerView.jar 的 zip
└── src/main/
    ├── resources/
    │   ├── META-INF/
    │   │   ├── plugin.xml                  # 插件描述、依赖、扩展、右键 Action
    │   │   ├── pluginIcon.svg              # 市场/本地安装图标（亮色）
    │   │   └── pluginIcon_dark.svg         # 暗色
    │   └── icons/callChain.svg             # Action / 工具窗口图标
    └── java/com/callerview/
        ├── CallerViewIcons.java
        ├── core/        CallNode, CallChainAnalyzer
        ├── config/      CallerViewSettings, CallerViewConfigurable
        ├── action/      ShowCallChainAction
        ├── ui/          CallGraphCanvas, CallChainTreePanel, CallChainPanel
        └── toolwindow/  CallChainViewService, CallChainToolWindowFactory
```

## 构建

环境要求：JDK 8（或更新，编译目标固定 1.8）+ Maven 3.6+。

```bash
mvn clean package
```

首次构建会从 `https://www.jetbrains.com/intellij-repository/releases` 与
`https://cache-redirector.jetbrains.com/intellij-dependencies` 下载平台构件（较大，请耐心等待）。
产物：

- `target/CallerView.jar`（插件本体，内含 `META-INF/plugin.xml` 与图标）
- `target/CallerView-1.0.0.zip`（可直接安装的分发包，结构为 `CallerView/lib/CallerView.jar`）

> zip 必须带顶层 `CallerView/` 目录：IDEA 安装时把 zip 的第一个顶层条目当作插件目录，
> 缺少该目录会报 “Fail to load plugin descriptor from file”。

> 若编译期出现“package xxx does not exist”，说明个别类所在平台模块未被 `core`/`java` 传递引入，
> 可在 `pom.xml` 的 `<dependencies>` 中补一个模块构件（坐标规则见
> [IntelliJ Artifacts 文档](https://plugins.jetbrains.com/docs/intellij/intellij-artifacts.html)），例如：
>
> ```xml
> <dependency>
>   <groupId>com.jetbrains.intellij.platform</groupId>
>   <artifactId>ide</artifactId>
>   <version>${intellij.version}</version>
>   <scope>provided</scope>
> </dependency>
> ```

## 安装

### 本地安装

`File | Settings | Plugins | ⚙ | Install Plugin from Disk…` → 选择 `target/CallerView-1.0.0.zip` → 重启 IDE。

### 从插件市场安装

将 `CallerView-1.0.0.zip`（或签名后的发布包）上传到
[JetBrains Marketplace](https://plugins.jetbrains.com/)。

## 使用

1. 打开任意 Java 工程。
2. 把光标放到某个方法体内（或选中方法名）。
3. 右键 → `CallerView: Show Call Chain`。
4. 底部 `CallerView` 工具窗口出现：左侧图形、右侧树，二者联动。
   - 图形：滚轮缩放、空白处拖拽平移、点击选中、双击跳转到源码、工具栏「放大/缩小/重置视图」。
   - 树：单击选中（图形同步）、双击跳转、自动展开整棵链路。
5. 在 `Settings | Tools | CallerView` 调整层级与核心方法；核心方法命中后相关链路自动标红。

## 说明与限制

- 调用者检索范围为**工程源码**（`GlobalSearchScope.projectScope`，含测试源）；
- 循环（含直接/间接递归）按分支打破，同一方法可在不同分支重复出现以保留完整路径信息。
- 默认 `-1` 表示无限层级，受安全节点上限 20000 保护；超大调用图建议显式设置层级。
- 存储的 `PsiMethod` 用于双击跳转，PSI 变更后若失效会自动忽略跳转。
- **无法覆盖的场景（静态分析的固有限制）**：反射调用、XML/配置文件驱动的框架调用（Spring、MyBatis 等）、二进制依赖库内部发起的回调（搜索范围是项目源码）、以及动态分发导致的歧义（接口调用具体走哪个实现只在运行时确定）。

