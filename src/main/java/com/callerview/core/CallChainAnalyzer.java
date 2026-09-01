package com.callerview.core;

import com.callerview.config.CallerViewSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the caller-chain tree for a target {@link PsiMethod} and annotates nodes/edges
 * so the UI can highlight chains that affect a configured "core" method.
 *
 * <p>Callers are searched within the project source scope. Cycles (including direct and
 * mutual recursion) are broken per-branch. A hard node cap protects against runaway graphs
 * when the depth limit is -1 (unlimited).</p>
 */
public class CallChainAnalyzer {

    /** Safety net so an "unlimited" analysis cannot freeze the IDE on pathological graphs. */
    private static final int SAFETY_NODE_CAP = 20000;

    private final @NotNull Project project;
    private int nodeCount;

    public CallChainAnalyzer(@NotNull Project project) {
        this.project = project;
    }

    public @Nullable CallNode analyze(@Nullable PsiMethod target, @Nullable ProgressIndicator indicator) {
        if (target == null) {
            return null;
        }
        return ApplicationManager.getApplication().runReadAction((Computable<CallNode>) () ->
                build(target, 0, new HashSet<>(), indicator));
    }

    private CallNode build(PsiMethod method, int depth, Set<String> ancestors, ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.checkCanceled();
        }

        String shortId = shortId(method);
        String signature = signature(method);
        CallNode node = new CallNode(
                depth,
                method.getName(),
                className(method),
                fqn(method),
                shortId,
                signature,
                method,
                false
        );
        node.setCore(CallerViewSettings.getInstance().isCore(node));
        nodeCount++;

        int max = CallerViewSettings.getInstance().getMaxDepth();
        boolean depthOk = max < 0 || depth < max;
        boolean canExpand = depthOk && nodeCount < SAFETY_NODE_CAP;

        if (canExpand) {
            ancestors.add(signature); // current node is on the path for its callers
            List<PsiMethod> callers = findCallers(method);
            Set<String> seen = new HashSet<>();
            for (PsiMethod caller : callers) {
                if (caller == null) {
                    continue;
                }
                String callerSig = signature(caller);
                if (seen.contains(callerSig) || ancestors.contains(callerSig)) {
                    continue; // duplicate / cycle
                }
                seen.add(callerSig);
                node.addChild(build(caller, depth + 1, ancestors, indicator));
            }
            ancestors.remove(signature);
        } else {
            node.setTruncated(true);
        }
        return node;
    }

    private List<PsiMethod> findCallers(PsiMethod method) {
        final List<PsiMethod> result = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        MethodReferencesSearch.search(method, scope, false).forEach(new Processor<PsiReference>() {
            @Override
            public boolean process(PsiReference reference) {
                PsiElement element = reference.getElement();
                PsiMethod caller = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (caller != null && !caller.isEquivalentTo(method)) {
                    result.add(caller);
                }
                return true;
            }
        });
        return result;
    }

    private static String className(PsiMethod method) {
        PsiClass c = method.getContainingClass();
        if (c != null && c.getName() != null) {
            return c.getName();
        }
        return "(unknown)";
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

    private static String shortId(PsiMethod method) {
        return className(method) + "." + method.getName();
    }

    private static String signature(PsiMethod method) {
        return fqn(method) + "." + method.getName() + "(" + paramTypes(method) + ")";
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
}
