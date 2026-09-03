package com.callerview.core;

import com.callerview.config.CallerViewSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
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

/**
 * Builds the caller-chain tree for a target {@link PsiMethod} and annotates nodes/edges
 * so the UI can highlight chains that affect a configured "core" method.
 *
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
 * <p>Cycles (direct, mutual and across overloads) are broken per-branch by signature. Depth is
 * additionally capped by {@link #MAX_ANALYSIS_DEPTH} so the recursive build — and the recursive
 * UI code that consumes the tree — can never overflow the thread stack, even with the depth
 * setting at -1 (unlimited).</p>
 */
public class CallChainAnalyzer {

    /** Safety net so an "unlimited" analysis cannot freeze the IDE on pathological graphs. */
    private static final int SAFETY_NODE_CAP = 20000;

    /** Hard ceiling on chain depth: protects the Java stack of the recursive build and UI. */
    private static final int MAX_ANALYSIS_DEPTH = 1000;

    private final @NotNull Project project;
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
        return ApplicationManager.getApplication().runReadAction((Computable<CallNode>) () ->
                build(CallerRef.of(target), 0, new HashSet<>(), indicator));
    }

    private CallNode build(CallerRef target, int depth, Set<String> ancestors, ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.checkCanceled();
        }

        CallNode node = new CallNode(
                depth,
                target.name,
                target.className,
                target.fqn,
                target.className + "." + target.name,
                target.signature,
                target.method,
                false
        );
        node.setCore(CallerViewSettings.getInstance().isCore(node));
        nodeCount++;

        int max = CallerViewSettings.getInstance().getMaxDepth();
        if (max < 0 || max > MAX_ANALYSIS_DEPTH) {
            max = MAX_ANALYSIS_DEPTH;
        }
        boolean canExpand = depth < max && nodeCount < SAFETY_NODE_CAP;

        if (canExpand) {
            ancestors.add(target.signature); // current node is on the path for its callers
            List<CallerRef> callers = findCallers(target);
            Set<String> seen = new HashSet<>();
            for (CallerRef caller : callers) {
                if (!seen.add(caller.signature) || ancestors.contains(caller.signature)) {
                    continue; // duplicate / cycle (direct, mutual, or across overloads)
                }
                node.addChild(build(caller, depth + 1, ancestors, indicator));
            }
            ancestors.remove(target.signature);
        } else {
            node.setTruncated(true);
        }
        return node;
    }

    private List<CallerRef> findCallers(CallerRef target) {
        List<CallerRef> result = new ArrayList<>();
        if (target.method != null) {
            findMethodCallers(target.method, result);
        } else if (target.ownerClass != null && "<init>".equals(target.name)) {
            findInitializerCallers(target.ownerClass, result);
        }
        // <clinit> stays a leaf: it is triggered by any class access, which is too noisy to search.
        return result;
    }

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
