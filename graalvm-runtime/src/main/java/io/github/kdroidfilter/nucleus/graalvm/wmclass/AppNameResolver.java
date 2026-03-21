package io.github.kdroidfilter.nucleus.graalvm.wmclass;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the real application name for use as X11 WM_CLASS.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>{@code nucleus.app.id} system property (injected by the Nucleus Gradle plugin)</li>
 *   <li>{@code nucleus/nucleus-app.properties} classpath resource ({@code app.id} key)</li>
 *   <li>Executable name from {@code /proc/self/exe}</li>
 *   <li>Fallback: {@code "NucleusApp"}</li>
 * </ol>
 */
final class AppNameResolver {

    private static volatile String cached;

    static String resolve() {
        String name = cached;
        if (name != null) return name;

        name = doResolve();
        cached = name;
        return name;
    }

    private static String doResolve() {
        // 1) System property injected by the Nucleus plugin
        String appId = System.getProperty("nucleus.app.id");
        if (appId != null && !appId.isBlank()) {
            return appId;
        }

        // 2) Classpath resource written by the plugin at build time
        try {
            InputStream stream = AppNameResolver.class
                    .getClassLoader()
                    .getResourceAsStream("nucleus/nucleus-app.properties");
            if (stream != null) {
                try (stream) {
                    Properties props = new Properties();
                    props.load(stream);
                    String resId = props.getProperty("app.id");
                    if (resId != null && !resId.isBlank()) {
                        return resId;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3) Executable name from /proc/self/exe
        try {
            File exe = new File("/proc/self/exe").getCanonicalFile();
            String name = exe.getName();
            if (name != null && !name.isEmpty()) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                return name;
            }
        } catch (Exception ignored) {
        }

        // 4) Fallback
        return "NucleusApp";
    }

    private AppNameResolver() {
    }
}
