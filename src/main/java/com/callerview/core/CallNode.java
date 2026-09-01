package com.callerview.core;

import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the caller chain tree.
 *
 * <p>The root is the method selected by the user. Each child is a method that calls its parent
 * (i.e. a caller, one level further from the target). Edges therefore read
 * <em>child calls parent</em>.</p>
 *
 * <p>Three booleans drive the red highlight of "chains affecting a core method":
 * <ul>
 *   <li>{@code core} &mdash; this method matches a configured core method.</li>
 *   <li>{@code hasCoreBelow} &mdash; some node in this subtree (incl. self) is a core method
 *       &rArr; this node lies on the root&rarr;core path.</li>
 *   <li>{@code coreAbove} &mdash; some ancestor (incl. self) is a core method
 *       &rArr; this node lies on the core&rarr;leaf path.</li>
 * </ul>
 * A node/edge belongs to a chain that affects a core method iff
 * {@code hasCoreBelow || coreAbove} (node) / {@code child.hasCoreBelow || parent.coreAbove} (edge).</p>
 */
public final class CallNode {

    private final int depth;
    private final String methodName;
    private final String className;
    private final String packageFqn;
    private final String shortId;
    private final String signature;
    private final @Nullable PsiMethod psiMethod;

    private final List<CallNode> children = new ArrayList<>();

    private boolean truncated;     // analysis stopped here (depth/nodes cap)
    private boolean core;
    private boolean hasCoreBelow;
    private boolean coreAbove;

    public CallNode(int depth,
                    String methodName,
                    String className,
                    String packageFqn,
                    String shortId,
                    String signature,
                    @Nullable PsiMethod psiMethod,
                    boolean truncated) {
        this.depth = depth;
        this.methodName = methodName;
        this.className = className;
        this.packageFqn = packageFqn;
        this.shortId = shortId;
        this.signature = signature;
        this.psiMethod = psiMethod;
        this.truncated = truncated;
    }

    public void addChild(CallNode child) {
        children.add(child);
    }

    public List<CallNode> getChildren() {
        return children;
    }

    public int getDepth() {
        return depth;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getClassName() {
        return className;
    }

    public String getPackageFqn() {
        return packageFqn;
    }

    public String getShortId() {
        return shortId;
    }

    public String getSignature() {
        return signature;
    }

    public @Nullable PsiMethod getPsiMethod() {
        return psiMethod;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean isCore() {
        return core;
    }

    public boolean isHasCoreBelow() {
        return hasCoreBelow;
    }

    public boolean isCoreAbove() {
        return coreAbove;
    }

    public boolean isOnCorePath() {
        return hasCoreBelow || coreAbove;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    void setCore(boolean core) {
        this.core = core;
    }

    void setHasCoreBelow(boolean hasCoreBelow) {
        this.hasCoreBelow = hasCoreBelow;
    }

    void setCoreAbove(boolean coreAbove) {
        this.coreAbove = coreAbove;
    }
}
