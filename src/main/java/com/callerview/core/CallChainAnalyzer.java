package com.callerview.core;

import com.callerview.config.CallerViewSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the caller-chain tree for a target {@link PsiMethod} and annotates nodes/edges
 * so the UI can highlight chains that affect a configured "core" method.
 *
 * <h3>Performance model</h3>
 * <p>The tree is grown level by level (breadth-first): the expensive part — finding the
 * callers of every frontier method, i.e. running {@link MethodReferencesSearch} /
 * {@link ClassInheritorsSearch} — is embarrassingly parallel, because frontier methods are
 * independent of each other. The search work for one level is split into chunks and executed
 * by a small fixed thread pool ({@link #MAX_WORKER_THREADS} threads max, see below); wiring
 * the found callers into the tree (cycle check, dedupe, caps) is cheap and stays on the
 * orchestrator thread. Levels with a single chunk run inline, so narrow chains pay no
 * thread hand-off at all.</p>
 *
 * <p>Space is traded for time in two caches: caller lists are memoised per method signature
 * (the same method typically appears at many tree positions — diamond-shaped call graphs —
 * and is then searched only once), and the "core method" match is memoised per signature.</p>
 *
 * <p>Each frontier item takes its own short read action instead of holding one read lock for
 * the whole analysis. This is required for the parallel design to be safe: the orchestrator
 * must not hold a read lock while waiting for workers (a pending write action between them
 * would block the workers' read actions and deadlock the analysis), and short read actions
 * also let the IDE process user edits between chunks instead of freezing. Because writes can
 * now interleave with the analysis, every cached {@code PsiMethod} is re-validated with
 * {@code isValid()} before use. Each worker search additionally runs under a progress
 * indicator registered with {@code ProgressManager.runProcess()}, because the platform's
 * index searches assert being executed under one — on any thread, including pool workers.</p>
 *
 * <p>Worker count: {@code min(availableProcessors, 8)}. Index-backed searches scale
 * sub-linearly with threads (shared index read locks, read-action bookkeeping), and an
 * oversized pool mainly inflates the latency of write actions waiting behind reads — which
 * the user perceives as IDE stutter. Eight threads is where the throughput curve flattens
 * on typical developer hardware.</p>
 *
 * <h3>Analysis semantics (unchanged by parallelism)</h3>
 * <p>Overloads are kept apart everywhere: references are searched with the platform's
 * <em>strict signature</em> mode (the non-strict fallback of {@link MethodReferencesSearch}
 * accepts any reference that resolves to a same-name method of the same class, regardless
 * of parameters, which would add spurious branches for overloaded methods), and every
 * accepted reference is additionally checked to resolve into the target's dispatch family
 * (the method itself, its super methods, or its overriding methods).</p>
 *
 * <p>Callers are searched within the project source scope, so calls originating from binary
 * libraries (frameworks, JDK) are inherently invisible; reflection-based invocations cannot
 * be found statically at all. Calls that execute outside any method body (field initializers,
 * static and instance initializer blocks, enum constant arguments) are attributed to synthetic
 * {@code <clinit>} / {@code <init>} nodes; {@code <init>} expands to the enclosing methods of
 * {@code new Owner(...)} sites, {@code <clinit>} stays a leaf because class-initializer
 * triggers (any static access) are too noisy. Javadoc, comment and annotation references are
 * skipped because they never execute.</p>
 *
 * <p>Cycles (direct, mutual and across overloads) are broken per-branch by signature, tracked
 * through an immutable linked path (one small allocation per node) instead of copying a
 * per-branch set. Depth is capped by {@link #MAX_ANALYSIS_DEPTH} so the recursive UI code
 * that consumes the tree can never overflow the thread stack, even with the depth setting
 * at -1 (unlimited).</p>
 */
public class CallChainAnalyzer {

    /** Safety net so an "unlimited" analysis cannot freeze the IDE on pathological graphs. */
    private static final int SAFETY_NODE_CAP = 20000;

    /** Hard ceiling on chain depth: protects the Java stack of the recursive UI code. */
    private static final int MAX_ANALYSIS_DEPTH = 1000;

    /** Upper bound for the worker pool — see the performance model in the class javadoc. */
    private static final int MAX_WORKER_THREADS = 8;

    private final @NotNull Project project;

    // Per-analysis state (analyzer instances are created per run; analyze() resets anyway).
    private final ConcurrentHashMap<String, List<CallerRef>> callerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> coreCache = new ConcurrentHashMap<>();
    private @Nullable ExecutorService pool;
    private @Nullable ProgressIndicator indicator;
    private @Nullable Thread orchestratorThread;
    private int nodeCount;

    public CallChainAnalyzer(@NotNull Project project) {
        this.project = project;
    }

    public @Nullable CallNode analyze(@Nullable PsiMethod target, @Nullable ProgressIndicator indicator) {
        if (target == null) {
            return null;
        }
        // Searching while indices are updating silently returns incomplete results.
        if (DumbService.isDumb(project)) {
            if (indicator != null) {
                indicator.setText("CallerView: 等待索引更新完成…");
            }
            DumbService.getInstance(project).waitForSmartMode();
        }
        this.indicator = indicator;
        this.orchestratorThread = Thread.currentThread();
        this.nodeCount = 0;
        this.callerCache.clear();
        this.coreCache.clear();

        CallerRef rootRef = ApplicationManager.getApplication().runReadAction((Computable<CallerRef>) () ->
                target.isValid() ? CallerRef.of(target) : null);
        if (rootRef == null) {
            return null;
        }

        this.pool = Executors.newFixedThreadPool(workerThreads(), daemonFactory());
        try {
            return buildTree(rootRef);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- level-parallel tree construction ----

    private CallNode buildTree(CallerRef rootRef) {
        int effectiveMax = effectiveMaxDepth();
        CallNode rootNode = makeNode(rootRef, 0);
        nodeCount = 1;

        List<LevelItem> frontier = new ArrayList<>();
        if (effectiveMax > 0) {
            frontier.add(new LevelItem(rootNode, rootRef, new PathNode(rootRef.signature, null)));
        } else {
            rootNode.setTruncated(true);
        }

        while (!frontier.isEmpty()) {
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText("CallerView: 深度 " + frontier.get(0).node.getDepth() + "，节点 " + nodeCount);
            }
            if (nodeCount >= SAFETY_NODE_CAP) {
                for (LevelItem item : frontier) {
                    item.node.setTruncated(true);
                }
                break;
            }

            List<List<CallerRef>> expansions = expandLevel(frontier);

            List<LevelItem> next = new ArrayList<>();
            for (int i = 0; i < frontier.size(); i++) {
                wireChildren(frontier.get(i), expansions.get(i), effectiveMax, next);
            }
            frontier = next;
        }
        return rootNode;
    }

    /**
     * Runs the caller search for every frontier item, spread over the worker pool.
     * The frontier is split into {@code 4 x workers} chunks so a slow method (many
     * references, big inheritor family) cannot leave a worker idle at the end of a level.
     */
    private List<List<CallerRef>> expandLevel(List<LevelItem> frontier) {
        int chunks = Math.max(1, workerThreads() * 4);
        int chunkSize = Math.max(1, (frontier.size() + chunks - 1) / chunks);

        List<Callable<List<List<CallerRef>>>> tasks = new ArrayList<>();
        for (int start = 0; start < frontier.size(); start += chunkSize) {
            final List<LevelItem> chunk = frontier.subList(start, Math.min(start + chunkSize, frontier.size()));
            tasks.add(() -> {
                List<List<CallerRef>> out = new ArrayList<>(chunk.size());
                for (LevelItem item : chunk) {
                    checkWorkerCanceled();
                    out.add(findCallersCached(item.ref));
                }
                return out;
            });
        }

        // Narrow level (the common chain-shaped case): run on the calling thread, no pool hop.
        if (tasks.size() == 1) {
            try {
                return tasks.get(0).call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        List<List<CallerRef>> result = new ArrayList<>(frontier.size());
        try {
            // invokeAll waits for every chunk and returns futures in task order.
            for (Future<List<List<CallerRef>>> f : pool.invokeAll(tasks)) {
                result.addAll(f.get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessCanceledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ProcessCanceledException) {
                throw (ProcessCanceledException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /** Cheap, sequential: attaches the found callers to one node (cycle / dedupe / caps). */
    private void wireChildren(LevelItem item, List<CallerRef> callers, int effectiveMax, List<LevelItem> next) {
        Set<String> seen = new HashSet<>();
        for (CallerRef caller : callers) {
            if (nodeCount >= SAFETY_NODE_CAP) {
                item.node.setTruncated(true);
                return;
            }
            if (indicator != null && indicator.isCanceled()) {
                throw new ProcessCanceledException();
            }
            if (item.path.contains(caller.signature)) {
                continue; // cycle (direct, mutual, or across overloads)
            }
            if (!seen.add(caller.signature)) {
                continue; // the same caller found via several family members / call sites
            }
            CallNode child = makeNode(caller, item.node.getDepth() + 1);
            item.node.addChild(child);
            nodeCount++;
            if (child.getDepth() < effectiveMax && nodeCount < SAFETY_NODE_CAP) {
                next.add(new LevelItem(child, caller, new PathNode(caller.signature, item.path)));
            } else {
                child.setTruncated(true);
            }
        }
    }

    /**
     * Memoised caller search. A method reached at several tree positions (diamond call
     * graphs) is searched once; the cache key is the unique signature.
     */
    private List<CallerRef> findCallersCached(CallerRef target) {
        List<CallerRef> cached = callerCache.get(target.signature);
        if (cached != null) {
            return cached;
        }
        List<CallerRef> computed = runUnderProgress((Computable<List<CallerRef>>) () ->
                ApplicationManager.getApplication().runReadAction((Computable<List<CallerRef>>) () -> {
                    checkWorkerCanceled();
                    List<CallerRef> result = new ArrayList<>();
                    if (target.method != null) {
                        // Writes can interleave between levels (short read actions), so re-validate.
                        if (target.method.isValid()) {
                            findMethodCallers(target.method, result);
                        }
                    } else if (target.ownerClass != null && "<init>".equals(target.name)) {
                        if (target.ownerClass.isValid()) {
                            findInitializerCallers(target.ownerClass, result);
                        }
                    }
                    // <clinit> stays a leaf: triggered by any class access, which is too noisy to search.
                    return result;
                }));
        List<CallerRef> previous = callerCache.putIfAbsent(target.signature, computed);
        return previous != null ? previous : computed;
    }

    /** Node creation is PSI-free: all strings are precomputed on the {@link CallerRef}. */
    private CallNode makeNode(CallerRef ref, int depth) {
        CallNode node = new CallNode(
                depth,
                ref.name,
                ref.className,
                ref.fqn,
                ref.className + "." + ref.name,
                ref.signature,
                ref.method,
                false
        );
        node.setCore(coreCache.computeIfAbsent(ref.signature, key ->
                CallerViewSettings.getInstance().isCore(node)));
        return node;
    }

    // ---- caller search (PSI work, runs inside a read action on a worker thread) ----

    private void findMethodCallers(PsiMethod method, List<CallerRef> result) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        for (final PsiMethod m : hierarchySearchMethods(method)) {
            // true = strict signature search. With false the searcher also accepts references
            // that merely resolve to a same-name method of the same class — every overload call
            // would then be reported as a call of the target (spurious extra branches).
            MethodReferencesSearch.search(m, scope, true).forEach(reference -> {
                PsiElement element = reference.getElement();
                if (isNonExecutableReference(element)) {
                    return true; // javadoc / comment / annotation links never execute
                }
                PsiMethod resolved = resolveToMethod(reference);
                if (resolved != null && !isInDispatchFamily(resolved, method)) {
                    return true; // call to a different overload or a sibling override
                }
                PsiMethod caller = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (caller != null) {
                    // Skips direct self-recursion, including dispatch through a hierarchy method.
                    if (!caller.isEquivalentTo(method) && !caller.isEquivalentTo(m)) {
                        result.add(CallerRef.of(caller));
                    }
                } else {
                    CallerRef pseudo = initializerCaller(element);
                    if (pseudo != null) {
                        result.add(pseudo);
                    }
                }
                return true;
            });
        }
    }

    /** Field-initializer code runs inside every constructor, so any {@code new Owner(...)} site is a caller. */
    private void findInitializerCallers(PsiClass owner, List<CallerRef> result) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        for (final PsiMethod ctor : owner.getConstructors()) {
            MethodReferencesSearch.search(ctor, scope, true).forEach(reference -> {
                PsiElement element = reference.getElement();
                if (isNonExecutableReference(element)) {
                    return true;
                }
                PsiMethod caller = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (caller != null && !caller.isEquivalentTo(ctor)) {
                    result.add(CallerRef.of(caller));
                } else if (caller == null) {
                    CallerRef pseudo = initializerCaller(element);
                    if (pseudo != null) {
                        result.add(pseudo);
                    }
                }
                return true;
            });
        }
    }

    /**
     * The method itself plus every method it overrides and every method overriding it.
     * Each call site resolves to exactly one of them statically, so only searching all of
     * them finds callers invoked through a base-class / interface reference (and through
     * a subtype reference when the target is the base method).
     */
    private Set<PsiMethod> hierarchySearchMethods(PsiMethod method) {
        Set<PsiMethod> methods = new LinkedHashSet<>();
        methods.add(method);
        for (PsiMethod superMethod : method.findSuperMethods()) {
            methods.add(superMethod);
        }
        PsiClass cls = method.getContainingClass();
        if (cls != null && canBeOverridden(method, cls)) {
            ClassInheritorsSearch.search(cls, GlobalSearchScope.projectScope(project), true).forEach(inheritor -> {
                PsiMethod override = inheritor.findMethodBySignature(method, false);
                if (override != null) {
                    methods.add(override);
                }
                return true;
            });
        }
        return methods;
    }

    /**
     * True when {@code resolved} is the target itself or shares its override slot (one of them
     * overrides the other). A call resolves to the target's dispatch slot only in that case;
     * same-name overloads and sibling overrides in a subclass never dispatch to the target.
     */
    private static boolean isInDispatchFamily(PsiMethod resolved, PsiMethod target) {
        if (resolved.isEquivalentTo(target)) {
            return true;
        }
        for (PsiMethod s : resolved.findSuperMethods()) {
            if (s.isEquivalentTo(target)) {
                return true; // resolved overrides the target (call through a subtype reference)
            }
        }
        for (PsiMethod s : target.findSuperMethods()) {
            if (s.isEquivalentTo(resolved)) {
                return true; // the target overrides resolved (call through a base reference)
            }
        }
        return false;
    }

    private static boolean canBeOverridden(PsiMethod method, PsiClass cls) {
        return !method.isConstructor()
                && !method.hasModifierProperty(PsiModifier.PRIVATE)
                && !method.hasModifierProperty(PsiModifier.STATIC)
                && !method.hasModifierProperty(PsiModifier.FINAL)
                && !cls.hasModifierProperty(PsiModifier.FINAL);
    }

    private static @Nullable PsiMethod resolveToMethod(PsiReference reference) {
        PsiElement resolved = reference.resolve();
        return resolved instanceof PsiMethod ? (PsiMethod) resolved : null;
    }

    /** Javadoc, comment and annotation references point at the method but never invoke it. */
    private static boolean isNonExecutableReference(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, PsiDocComment.class, PsiComment.class, PsiAnnotation.class) != null;
    }

    /**
     * Attributes a reference outside any method body (field initializer, array initializer,
     * static/instance initializer block, enum constant arguments) to a synthetic
     * {@code <clinit>} / {@code <init>} node of the owning class.
     */
    private static @Nullable CallerRef initializerCaller(PsiElement element) {
        for (PsiElement e = element; e != null; e = e.getParent()) {
            if (e instanceof PsiField) {
                PsiClass owner = PsiTreeUtil.getParentOfType(e, PsiClass.class);
                boolean isStatic = e instanceof PsiEnumConstant
                        || ((PsiField) e).hasModifierProperty(PsiModifier.STATIC);
                return owner != null ? CallerRef.initializer(owner, isStatic) : null;
            }
            if (e instanceof PsiClassInitializer) {
                PsiClass owner = PsiTreeUtil.getParentOfType(e, PsiClass.class);
                boolean isStatic = ((PsiClassInitializer) e).hasModifierProperty(PsiModifier.STATIC);
                return owner != null ? CallerRef.initializer(owner, isStatic) : null;
            }
            if (e instanceof PsiClass) {
                return null; // left the member without finding an initializer context
            }
        }
        return null;
    }

    // ---- helpers ----

    /**
     * Cross-thread cancel check for workers: the task's indicator belongs to the orchestrator
     * thread, so workers only poll the flag ({@code isCanceled}) instead of calling
     * {@code checkCanceled()} on a foreign indicator.
     */
    private void checkWorkerCanceled() {
        if (indicator != null && indicator.isCanceled()) {
            throw new ProcessCanceledException();
        }
    }

    /**
     * Index-backed searches assert that the current thread runs under a progress indicator
     * ({@code CoreProgressManager.assertUnderProgress}, enforced by {@code PsiSearchHelperImpl}).
     * The orchestrator thread already runs under the background task's indicator, but every
     * pool worker must register its own via {@link ProgressManager#runProcess} — otherwise the
     * search fails with "Must be executed under progress indicator".
     */
    private <T> T runUnderProgress(Computable<T> computation) {
        if (Thread.currentThread() == orchestratorThread && indicator != null) {
            return computation.compute();
        }
        return ProgressManager.getInstance().runProcess(computation, new EmptyProgressIndicator());
    }

    private static int workerThreads() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_WORKER_THREADS));
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "CallerView-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static int effectiveMaxDepth() {
        int max = CallerViewSettings.getInstance().getMaxDepth();
        if (max < 0 || max > MAX_ANALYSIS_DEPTH) {
            return MAX_ANALYSIS_DEPTH;
        }
        return max;
    }

    private static String className(PsiMethod method) {
        PsiClass c = method.getContainingClass();
        if (c instanceof PsiAnonymousClass) {
            return anonymousLabel((PsiAnonymousClass) c);
        }
        if (c != null && c.getName() != null) {
            return c.getName();
        }
        return "(unknown)";
    }

    private static String anonymousLabel(PsiAnonymousClass c) {
        PsiJavaCodeReferenceElement base = c.getBaseClassReference();
        String text = base != null ? base.getText() : null;
        return text != null && !text.isEmpty() ? "(anon " + text + ")" : "(anonymous)";
    }

    private static String fqn(PsiMethod method) {
        PsiClass c = method.getContainingClass();
        if (c != null) {
            String q = c.getQualifiedName();
            if (q != null) {
                return q;
            }
            return c.getName() != null ? c.getName() : "anonymous";
        }
        return "unknown";
    }

    /**
     * Unique per method. Classes without a qualified name (anonymous / local classes) get a
     * file+offset suffix, otherwise every anonymous {@code run()} in the project would share
     * one signature and distinct anonymous callers would be mistaken for a cycle.
     */
    private static String signature(PsiMethod method) {
        PsiClass c = method.getContainingClass();
        String base = c != null && c.getQualifiedName() != null ? c.getQualifiedName() : uniqueFallback(method);
        return base + "." + method.getName() + "(" + paramTypes(method) + ")";
    }

    private static String uniqueFallback(PsiElement element) {
        PsiFile file = element.getContainingFile();
        String path = file != null && file.getVirtualFile() != null ? file.getVirtualFile().getPath() : "";
        return path.isEmpty() ? "anonymous" : path + "@" + element.getTextOffset();
    }

    private static String paramTypes(PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            PsiType type = params[i].getType();
            sb.append(type.getPresentableText());
        }
        return sb.toString();
    }

    /** Bottom-up: a node has a core in its subtree iff itself is core or any child has one. */
    public static void markCoreBelow(CallNode node) {
        boolean has = node.isCore();
        for (CallNode child : node.getChildren()) {
            markCoreBelow(child);
            has |= child.isHasCoreBelow();
        }
        node.setHasCoreBelow(has);
    }

    /** Top-down: propagate "core in self or ancestors" downward. */
    public static void markCoreAbove(CallNode node, boolean coreInAncestors) {
        boolean selfOrAbove = node.isCore() || coreInAncestors;
        node.setCoreAbove(selfOrAbove);
        for (CallNode child : node.getChildren()) {
            markCoreAbove(child, selfOrAbove);
        }
    }

    // ---- small value types ----

    /** Immutable root-to-node signature path: cycle detection without copying sets. */
    private static final class PathNode {
        final String signature;
        final @Nullable PathNode parent;

        PathNode(String signature, @Nullable PathNode parent) {
            this.signature = signature;
            this.parent = parent;
        }

        boolean contains(String sig) {
            for (PathNode p = this; p != null; p = p.parent) {
                if (p.signature.equals(sig)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One node of the current BFS frontier: the node, its search key, and its path. */
    private static final class LevelItem {
        final CallNode node;
        final CallerRef ref;
        final PathNode path;

        LevelItem(CallNode node, CallerRef ref, PathNode path) {
            this.node = node;
            this.ref = ref;
            this.path = path;
        }
    }

    /** A discovered caller: a real {@link PsiMethod}, or a synthetic node for class initialization code. */
    private static final class CallerRef {
        final @Nullable PsiMethod method;
        final @Nullable PsiClass ownerClass; // only for synthetic initializer nodes
        final String name;
        final String className;
        final String fqn;
        final String signature;

        private CallerRef(@Nullable PsiMethod method,
                          @Nullable PsiClass ownerClass,
                          String name,
                          String className,
                          String fqn,
                          String signature) {
            this.method = method;
            this.ownerClass = ownerClass;
            this.name = name;
            this.className = className;
            this.fqn = fqn;
            this.signature = signature;
        }

        static CallerRef of(PsiMethod method) {
            return new CallerRef(method, null, method.getName(), className(method), fqn(method), signature(method));
        }

        /** Synthetic caller for calls that execute while the class is being initialized. */
        static CallerRef initializer(PsiClass owner, boolean isStatic) {
            String name = isStatic ? "<clinit>" : "<init>";
            String clsName = owner instanceof PsiAnonymousClass
                    ? anonymousLabel((PsiAnonymousClass) owner)
                    : (owner.getName() != null ? owner.getName() : "(anonymous)");
            String q = owner.getQualifiedName();
            String base = q != null ? q : uniqueFallback(owner);
            return new CallerRef(null, owner, name, clsName, q != null ? q : clsName, base + "." + name + "()");
        }
    }
}
