package com.callerview.config;

import com.callerview.core.CallNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level (global) settings: upward depth limit + the list of "core methods".
 * Registered via {@code <applicationService>} in plugin.xml, so it is serialised to
 * {@code config/CallerViewSettings.xml} in the IDE config directory.
 *
 * <p>The {@code @State} annotation is mandatory since IntelliJ 2024.x: without it the platform
 * throws {@code UnsupportedOperationException: configurationSchemaKey must be specified} when the
 * service is first accessed, which used to abort the whole chain analysis at the root node.</p>
 */
@State(name = "CallerViewSettings", storages = {@Storage("CallerViewSettings.xml")})
public class CallerViewSettings implements PersistentStateComponent<CallerViewSettings.State> {

    public static class State {
        /** -1 means unlimited upward depth. */
        public int maxDepth = -1;
        public List<String> coreMethods = new ArrayList<>();
    }

    private State state = new State();

    public static CallerViewSettings getInstance() {
        return ApplicationManager.getApplication().getService(CallerViewSettings.class);
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
        if (this.state.coreMethods == null) {
            this.state.coreMethods = new ArrayList<>();
        }
    }

    public int getMaxDepth() {
        return state.maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        state.maxDepth = maxDepth;
    }

    public List<String> getCoreMethods() {
        if (state.coreMethods == null) {
            state.coreMethods = new ArrayList<>();
        }
        return state.coreMethods;
    }

    public void setCoreMethods(List<String> coreMethods) {
        state.coreMethods = coreMethods == null ? new ArrayList<>() : coreMethods;
    }

    /**
     * A method is considered "core" when any configured entry matches it.
     *
     * <p>Two match modes are supported so overloaded methods can be told apart:
     * <ul>
     *   <li><b>Exact overload signature</b> &mdash; an entry that contains a parameter list, e.g.
     *       {@code ClassName.methodName(int)} or a full
     *       {@code com.foo.ClassName.methodName(int, String)}, matches only that one overload
     *       (whitespace between parameters is ignored).</li>
     *   <li><b>Loose</b> &mdash; any other entry, e.g. {@code ClassName.methodName} (case-insensitive)
     *       or an FQN prefix, matches every overload of that method.</li>
     * </ul>
     */
    public boolean isCore(CallNode node) {
        List<String> methods = state.coreMethods;
        if (methods == null || methods.isEmpty()) {
            return false;
        }
        String id = node.getShortId();
        String full = node.getSignature();
        // Synthetic <clinit>/<init> nodes have signatures without a parameter list.
        int open = full.indexOf('(');
        String shortSig = open >= 0 ? id + full.substring(open) : id;
        for (String e : methods) {
            if (e == null) {
                continue;
            }
            String t = e.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (looksLikeSignature(t)) {
                // An entry with a parameter list pins down one specific overload.
                if (signatureEquals(t, full) || signatureEquals(t, shortSig)) {
                    return true;
                }
            } else if (t.equalsIgnoreCase(id) || full.contains(t)) {
                // No parameter list -> matches all overloads (or an FQN prefix).
                return true;
            }
        }
        return false;
    }

    /** True when the entry looks like a signature that includes a parameter list, e.g. foo.bar(int). */
    private static boolean looksLikeSignature(String t) {
        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        return open > 0 && close > open;
    }

    /** Whitespace-insensitive comparison so {@code int, String} also matches {@code int,String}. */
    private static boolean signatureEquals(String a, String b) {
        return a.replace(" ", "").equals(b.replace(" ", ""));
    }
}
