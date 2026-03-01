package me.naimad.fastregex;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
        } else if (osProp.contains("mac")) {
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

        String resourcePath = "/native/" + osName + "-" + arch + "/" + libPrefix + "fastregex" + libSuffix;
        try (InputStream is = FastRegex.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                File tempFile = Files.createTempFile("fastregex-native-", libSuffix).toFile();
                tempFile.deleteOnExit();
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.load(tempFile.getAbsolutePath());
                return;
            }
        } catch (Exception ignored) {
            // Fallback to searching in java.library.path
        }

        try {
            System.loadLibrary("fastregex");
        } catch (UnsatisfiedLinkError e) {
            throw new UnsatisfiedLinkError("Could not load fastregex native library for " + osName + "-" + arch +
                    ". Looked in JAR resource " + resourcePath + " and java.library.path.");
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