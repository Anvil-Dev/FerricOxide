package dev.anvilcraft.oxide.ferric.webui;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Loads the {@code ferric_oxide_native} JNI library.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code ferricoxide.native.path} system property pointing at the library file (used in
 *       dev runs and for debugging).</li>
 *   <li>The per-OS/arch resource packaged in the mod jar under {@code /natives/}, extracted to
 *       a temp file and loaded via {@link System#load}.</li>
 * </ol>
 */
public final class NativeLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LIB_NAME = "ferric_oxide_native";

    private static volatile boolean loaded;

    private NativeLoader() {}

    /** Attempts to load the native library, ignoring failure. Must run on the client only. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            String override = System.getProperty("ferricoxide.native.path");
            if (override != null && !override.isBlank()) {
                System.load(Path.of(override).toAbsolutePath().toString());
            } else {
                loadFromResources();
            }
            loaded = true;
            LOGGER.info("Loaded native WebView library '{}'", LIB_NAME);
        } catch (Throwable t) {
            LOGGER.error("Failed to load native WebView library '{}' - web UI features disabled", LIB_NAME, t);
        }
    }

    /** Whether the library has been successfully loaded. */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void loadFromResources() {
        String resource = resourcePathForCurrentPlatform();
        String suffix = resource.substring(resource.lastIndexOf('.'));

        Path temp;
        try {
            temp = Files.createTempFile(LIB_NAME, suffix);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create temp file for native library", e);
        }

        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                    "native library " + resource + " not found in mod jar; run the buildRustNative Gradle task");
            }
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to extract native library " + resource, e);
        }

        temp.toFile().deleteOnExit();
        System.load(temp.toAbsolutePath().toString());
    }

    private static String resourcePathForCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String platform;
        String file;
        if (os.contains("win")) {
            platform = "windows";
            file = LIB_NAME + ".dll";
        } else if (os.contains("mac")) {
            platform = "macos";
            file = "lib" + LIB_NAME + ".dylib";
        } else {
            platform = "linux";
            file = "lib" + LIB_NAME + ".so";
        }

        String normalizedArch = switch (arch) {
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException("unsupported CPU architecture for native webview: " + arch);
        };

        return "/natives/" + platform + "/" + normalizedArch + "/" + file;
    }
}
