package me.naimad.fastregex;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper to load the native fastregex library from the JAR.
 */
public class FastRegexLoader {
    private static boolean loaded = false;

    /**
     * Loads the native library if it hasn't been loaded yet.
     * Idempotent: safe to call multiple times.
     */
    public static synchronized void load() {
        if (loaded) return;

        String osProp = System.getProperty("os.name").toLowerCase();
        String archProp = System.getProperty("os.arch").toLowerCase();

        String os;
        String prefix = "lib";
        String suffix = ".so";

        if (osProp.contains("win")) {
            os = "windows";
            prefix = "";
            suffix = ".dll";
        } else if (osProp.contains("mac") || osProp.contains("darwin")) {
            os = "macos";
            suffix = ".dylib";
        } else {
            os = "linux";
        }

        String arch;
        if (archProp.matches("^(x86_64|amd64|x64)$")) {
            arch = "x86_64";
        } else if (archProp.matches("^(aarch64|arm64)$")) {
            arch = "aarch64";
        } else {
            arch = archProp;
        }

        String filename = prefix + "fastregex" + suffix;
        String osArch = os + "-" + arch;

        // Possible resource paths (absolute, with forward slashes)
        List<String> paths = new ArrayList<>();
        paths.add("/me/naimad/fastregex/native_bin/" + osArch + "/" + filename);
        paths.add("/native_bin/" + osArch + "/" + filename);
        paths.add("/me/naimad/fastregex/native/" + osArch + "/" + filename);
        paths.add("/native/" + osArch + "/" + filename);

        // 1. Check system property override first
        String explicitPath = System.getProperty("fastregex.native.path");
        if (explicitPath != null) {
            File f = new File(explicitPath);
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                    loaded = true;
                    return;
                } catch (UnsatisfiedLinkError e) {
                    System.err.println("[FastRegex] Error loading from explicit path: " + explicitPath + " - " + e.getMessage());
                }
            }
        }

        // 2. Try resources
        List<String> triedPaths = new ArrayList<>();
        for (String path : paths) {
            triedPaths.add(path);
            try (InputStream is = FastRegexLoader.class.getResourceAsStream(path)) {
                if (is != null) {
                    File temp = extractToTempFile(is, suffix);
                    System.load(temp.getAbsolutePath());
                    loaded = true;
                    return;
                }
            } catch (Throwable t) {
                // Ignore and try next path
            }
        }

        // 3. Last fallback: System.loadLibrary (depends on java.library.path)
        try {
            System.loadLibrary("fastregex");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError e) {
            StringBuilder msg = new StringBuilder();
            msg.append("Could not load fastregex native library for ").append(osArch).append(".\n");
            msg.append("Attempted resource paths:\n");
            for (String p : triedPaths) {
                msg.append("  - ").append(p).append("\n");
            }
            msg.append("System.loadLibrary(\"fastregex\") failed as well.\n");
            msg.append("Environment: OS=").append(osProp).append(", Arch=").append(archProp).append("\n");
            msg.append("System Property 'fastregex.native.path' was: ").append(explicitPath != null ? explicitPath : "not set").append("\n");
            
            UnsatisfiedLinkError error = new UnsatisfiedLinkError(msg.toString());
            error.initCause(e);
            throw error;
        }
    }

    private static File extractToTempFile(InputStream is, String suffix) throws Exception {
        File temp = Files.createTempFile("fastregex-native-", suffix).toFile();
        temp.deleteOnExit();
        Files.copy(is, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return temp;
    }
}
