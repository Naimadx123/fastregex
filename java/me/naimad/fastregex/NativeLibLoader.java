package me.naimad.fastregex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;


public class NativeLibLoader {
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    public static void load() {
        if (!LOADED.compareAndSet(false, true)) {
            return;
        }

        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String os;
        String extension;
        if (osName.contains("win")) {
            os = "windows";
            extension = ".dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "macos";
            extension = ".dylib";
        } else if (osName.contains("linux")) {
            os = "linux";
            extension = ".so";
        } else {
            os = "linux"; // Fallback to linux
            extension = ".so";
        }

        String arch;
        if (osArch.equals("x86_64") || osArch.equals("amd64") || osArch.equals("x64")) {
            arch = "x86_64";
        } else if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            arch = "aarch64";
        } else {
            arch = osArch;
        }

        String mappedLibraryName = (os.equals("windows") ? "" : "lib") + "fastregex" + extension;
        String resourcePath = "/me/naimad/fastregex/native/" + os + "-" + arch + "/" + mappedLibraryName;

        try (InputStream is = FastRegex.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                String classpath = System.getProperty("java.class.path");
                throw new UnsatisfiedLinkError(String.format(
                    "Could not find native library resource for os=%s, arch=%s at path=%s. Classpath: %s",
                    os, arch, resourcePath, classpath
                ));
            }

            Path tempDir = Files.createTempDirectory("fastregex-natives");
            tempDir.toFile().deleteOnExit();

            Path tempFile = tempDir.resolve("fastregex-" + System.nanoTime() + extension);
            tempFile.toFile().deleteOnExit();

            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

            System.load(tempFile.toAbsolutePath().toString());
        } catch (IOException e) {
            LOADED.set(false);
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        } catch (Throwable t) {
            LOADED.set(false);
            if (t instanceof UnsatisfiedLinkError) {
                throw (UnsatisfiedLinkError) t;
            }
            throw new UnsatisfiedLinkError(t.getMessage());
        }
    }
}
