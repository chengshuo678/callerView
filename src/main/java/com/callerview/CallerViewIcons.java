package com.callerview;

import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Bundled icons. Loaded from {@code /icons/*.svg} on the plugin classpath.
 * SVG support has been part of the platform since 2019.x, so this is safe for the 2020.3+ range.
 */
public final class CallerViewIcons {

    public static final @NotNull Icon ACTION = IconLoader.getIcon("/icons/callChain.svg", CallerViewIcons.class);
    public static final @NotNull Icon TOOL_WINDOW = IconLoader.getIcon("/icons/callChain.svg", CallerViewIcons.class);

    private CallerViewIcons() {
    }
}
