package me.naimad.fastregex;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

public class FastRegex {

    static {
        loadNativeLibrary();
    }

    private static void loadNativeLibrary() {
        String osProp = System.getProperty("os.name").toLowerCase();
        String archProp = System.getProperty("os.arch").toLowerCase();

        String libPrefix = "lib";
        String libSuffix = ".so";
        String osName = "linux";

        if (osProp.contains("win")) {
            libPrefix = "";
            libSuffix = ".dll";
            osName = "windows";
        } else if (osProp.contains("mac") || osProp.contains("darwin")) {
            libSuffix = ".dylib";
            osName = "macos";
        }

        // Normalize architecture names
        String arch;
        if (archProp.matches("^(x86_64|amd64|x64)$")) {
            arch = "x86_64";
        } else if (archProp.matches("^(aarch64|arm64)$")) {
            arch = "aarch64";
        } else {
            arch = archProp;
        }

        String libName = libPrefix + "fastregex" + libSuffix;
        // Search in the package-relative native directory
        String resourcePath = "/me/naimad/fastregex/native/" + osName + "-" + arch + "/" + libName;

        // 1. Try system property override
        String explicitPath = System.getProperty("fastregex.native.path");
        if (explicitPath != null) {
            File f = new File(explicitPath);
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                    return;
                } catch (UnsatisfiedLinkError e) {
                    System.err.println("[FastRegex] Failed to load library from explicit path: " + explicitPath + " - " + e.getMessage());
                }
            } else {
                System.err.println("[FastRegex] Explicit native path does not exist: " + explicitPath);
            }
        }

        // 2. Try loading from JAR resources
        try {
            loadFromResource(resourcePath, libSuffix);
            return;
        } catch (Throwable t) {
            // 3. Last fallback: System.loadLibrary
            try {
                System.loadLibrary("fastregex");
            } catch (UnsatisfiedLinkError e) {
                StringBuilder msg = new StringBuilder();
                msg.append("Could not load fastregex native library for ").append(osName).append("-").append(arch).append(".\n");
                msg.append("1. System property 'fastregex.native.path' was: ").append(explicitPath).append("\n");
                msg.append("2. JAR resource not found: ").append(resourcePath).append(" (error: ").append(t.getMessage()).append(")\n");
                msg.append("3. System.loadLibrary(\"fastregex\") failed (error: ").append(e.getMessage()).append(")\n\n");
                msg.append("Ensure the native library is present in the JAR at '").append(resourcePath).append("' ");
                msg.append("or in java.library.path.");
                
                UnsatisfiedLinkError error = new UnsatisfiedLinkError(msg.toString());
                error.initCause(t);
                throw error;
            }
        }
    }

    private static void loadFromResource(String path, String suffix) throws Exception {
        // Try Class.getResourceAsStream first - most reliable for resources in the same JAR
        try (InputStream is = FastRegex.class.getResourceAsStream(path)) {
            if (is != null) {
                loadFromStream(is, suffix);
                return;
            }
        }

        String strippedPath = path.startsWith("/") ? path.substring(1) : path;
        List<ClassLoader> loaders = Arrays.asList(
                Thread.currentThread().getContextClassLoader(),
                FastRegex.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        );

        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try (InputStream is = loader.getResourceAsStream(strippedPath)) {
                if (is != null) {
                    loadFromStream(is, suffix);
                    return;
                }
            } catch (Exception ignored) {}
        }

        throw new java.io.FileNotFoundException("Resource not found: " + path);
    }

    private static void loadFromStream(InputStream is, String suffix) throws Exception {
        File tempFile = Files.createTempFile("fastregex-native-", suffix).toFile();
        tempFile.deleteOnExit();
        try {
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.getAbsolutePath());
        } catch (Throwable t) {
            tempFile.delete();
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception("Native load error", t);
        }
    }

    public static native long compile(String pattern);
    public static native void release(long handle);
    public static native boolean matchesUtf8Direct(long handle, ByteBuffer directBuf, int offset, int len);
    public static native void batchMatchesUtf8Direct(long handle, ByteBuffer dataBuf, int[] offsets, int[] lengths, long[] outBits);

    public static class PackedUtf8 {
        public ByteBuffer data;
        public int[] offsets;
        public int[] lengths;
    }

    public static PackedUtf8 packUtf8Direct(String[] batch) {
        int totalLen = 0;
        byte[][] bytesArray = new byte[batch.length][];
        for (int i = 0; i < batch.length; i++) {
            bytesArray[i] = batch[i].getBytes(StandardCharsets.UTF_8);
            totalLen += bytesArray[i].length;
        }

        ByteBuffer data = ByteBuffer.allocateDirect(totalLen);
        int[] offsets = new int[batch.length];
        int[] lengths = new int[batch.length];
        int currentPos = 0;
        for (int i = 0; i < batch.length; i++) {
            offsets[i] = currentPos;
            lengths[i] = bytesArray[i].length;
            data.put(bytesArray[i]);
            currentPos += bytesArray[i].length;
        }

        PackedUtf8 res = new PackedUtf8();
        res.data = data;
        res.offsets = offsets;
        res.lengths = lengths;
        return res;
    }

    public static boolean getBit(long[] outBits, int i) {
        int wordIdx = i / 64;
        int bitIdx = i % 64;
        return (outBits[wordIdx] & (1L << bitIdx)) != 0;
    }
}