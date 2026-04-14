package me.naimad.fastregex;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * FastRegex provides high-performance regex matching using Rust's regex engine via FFM API.
 */
public class FastRegex {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;
    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(StandardCharsets.UTF_8::newEncoder);

    static {
        NativeLibLoader.load();
        LOOKUP = SymbolLookup.loaderLookup();
    }

    private static final MethodHandle MH_COMPILE = LINKER.downcallHandle(
            LOOKUP.find("fastregex_compile").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );

    private static final MethodHandle MH_RELEASE = LINKER.downcallHandle(
            LOOKUP.find("fastregex_release").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle MH_MATCHES = LINKER.downcallHandle(
            LOOKUP.find("fastregex_matches_utf8").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle MH_BATCH_MATCHES = LINKER.downcallHandle(
            LOOKUP.find("fastregex_batch_matches_utf8").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );

    /**
     * Compiles a regex pattern into a high-level {@link Regex} object.
     * The returned object is {@link AutoCloseable} and should be used in a try-with-resources block.
     */
    public static Regex compile(String pattern) {
        return new Regex(pattern, compileNative(pattern));
    }

    /**
     * Low-level compilation. Returns a raw handle that must be released with {@link #release(long)}.
     */
    public static long compileNative(String pattern) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cPattern = arena.allocateFrom(pattern);
            long res = (long) MH_COMPILE.invokeExact(cPattern);
            if (res == 0) {
                throw new IllegalArgumentException("Failed to compile regex: " + pattern);
            }
            return res;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Releases the native resources associated with the regex handle.
     */
    public static void release(long handle) {
        if (handle == 0) return;
        try {
            MH_RELEASE.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Matches a single UTF-8 string represented by a MemorySegment.
     */
    public static boolean matchesUtf8(long handle, MemorySegment data) {
        try {
            int res = (int) MH_MATCHES.invokeExact(handle, data, data.byteSize());
            return res != 0;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Matches a batch of UTF-8 strings.
     *
     * @param handle     Regex handle
     * @param data       A single MemorySegment containing all concatenated UTF-8 strings
     * @param offsets    MemorySegment of int offsets into data
     * @param lengths    MemorySegment of int lengths into data
     * @param outBits    MemorySegment of long words for bitset results
     * @param num        Number of strings in the batch
     */
    public static void batchMatchesUtf8(long handle, MemorySegment data, MemorySegment offsets, MemorySegment lengths, MemorySegment outBits, long num) {
        try {
            MH_BATCH_MATCHES.invokeExact(handle, data, data.byteSize(), offsets, lengths, outBits, num);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // --- Convenience methods ---

    public static boolean matchesUtf8Direct(long handle, ByteBuffer directBuf, int offset, int len) {
        MemorySegment segment = MemorySegment.ofBuffer(directBuf);
        MemorySegment slice = segment.asSlice(offset, len);
        return matchesUtf8(handle, slice);
    }

    public static void batchMatchesUtf8Direct(long handle, ByteBuffer dataBuf, int[] offsets, int[] lengths, long[] outBits) {
        int num = offsets.length;
        if (lengths.length != num) throw new IllegalArgumentException("offsets and lengths size mismatch");
        int neededWords = (num + 63) / 64;
        if (outBits.length < neededWords) throw new IllegalArgumentException("outBits too small");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataSegment = MemorySegment.ofBuffer(dataBuf);
            MemorySegment offsetsSegment = arena.allocateFrom(ValueLayout.JAVA_INT, offsets);
            MemorySegment lengthsSegment = arena.allocateFrom(ValueLayout.JAVA_INT, lengths);
            MemorySegment outBitsSegment = arena.allocate(ValueLayout.JAVA_LONG, neededWords);

            batchMatchesUtf8(handle, dataSegment, offsetsSegment, lengthsSegment, outBitsSegment, (long) num);

            for (int i = 0; i < neededWords; i++) {
                outBits[i] = outBitsSegment.getAtIndex(ValueLayout.JAVA_LONG, i);
            }
        }
    }

    /**
     * Represents a batch of UTF-8 strings packed into a single native memory block.
     * Should be closed after use to free native memory.
     */
    public static class PackedUtf8 implements AutoCloseable {
        public final Arena arena;
        public final MemorySegment data;
        public final MemorySegment offsets;
        public final MemorySegment lengths;
        public final int num;

        private PackedUtf8(Arena arena, MemorySegment data, MemorySegment offsets, MemorySegment lengths, int num) {
            this.arena = arena;
            this.data = data;
            this.offsets = offsets;
            this.lengths = lengths;
            this.num = num;
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /**
     * Packs an array of strings into a single native memory block for efficient batch matching.
     * This method is optimized to minimize allocations and copying.
     */
    public static PackedUtf8 packUtf8(String[] batch) {
        Arena arena = Arena.ofConfined();
        try {
            int num = batch.length;
            int[] lengthsArr = new int[num];
            long totalBytes = 0;
            for (int i = 0; i < num; i++) {
                String s = batch[i];
                int bytes = 0;
                for (int j = 0; j < s.length(); j++) {
                    char c = s.charAt(j);
                    if (c < 0x80) bytes++;
                    else if (c < 0x800) bytes += 2;
                    else if (Character.isHighSurrogate(c)) { bytes += 4; j++; }
                    else bytes += 3;
                }
                lengthsArr[i] = bytes;
                totalBytes += bytes;
            }

            MemorySegment data = arena.allocate(totalBytes);
            MemorySegment offsets = arena.allocate(ValueLayout.JAVA_INT, num);
            MemorySegment lengths = arena.allocate(ValueLayout.JAVA_INT, num);

            CharsetEncoder encoder = ENCODER.get();
            long pos = 0;
            for (int i = 0; i < num; i++) {
                offsets.setAtIndex(ValueLayout.JAVA_INT, i, (int) pos);
                lengths.setAtIndex(ValueLayout.JAVA_INT, i, lengthsArr[i]);
                
                if (lengthsArr[i] > 0) {
                    ByteBuffer buf = data.asSlice(pos, lengthsArr[i]).asByteBuffer();
                    encoder.reset();
                    encoder.encode(CharBuffer.wrap(batch[i]), buf, true);
                    encoder.flush(buf);
                }
                pos += lengthsArr[i];
            }

            return new PackedUtf8(arena, data, offsets, lengths, num);
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    /**
     * Legacy method for compatibility.
     */
    public static PackedUtf8 packUtf8Direct(String[] batch) {
        return packUtf8(batch);
    }

    /**
     * High-level Regex object that handles native resources automatically.
     */
    public static class Regex implements AutoCloseable {
        private final long handle;
        private final String pattern;

        private Regex(String pattern, long handle) {
            this.pattern = pattern;
            this.handle = handle;
        }

        public boolean matches(String text) {
            if (text == null) return false;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocateFrom(text);
                return FastRegex.matchesUtf8(handle, segment);
            }
        }

        public boolean[] batchMatches(String[] batch) {
            if (batch == null || batch.length == 0) return new boolean[0];
            try (PackedUtf8 packed = FastRegex.packUtf8(batch)) {
                int num = batch.length;
                int words = (num + 63) / 64;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment outBits = arena.allocate(ValueLayout.JAVA_LONG, words);
                    FastRegex.batchMatchesUtf8(handle, packed.data, packed.offsets, packed.lengths, outBits, num);
                    boolean[] res = new boolean[num];
                    for (int i = 0; i < num; i++) {
                        res[i] = FastRegex.getBit(outBits, i);
                    }
                    return res;
                }
            }
        }

        public long handle() {
            return handle;
        }

        public String pattern() {
            return pattern;
        }

        @Override
        public void close() {
            FastRegex.release(handle);
        }
    }

    public static boolean getBit(long[] outBits, int i) {
        return (outBits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    /**
     * Gets a bit from a MemorySegment representing a bitset.
     */
    public static boolean getBit(MemorySegment outBits, int i) {
        long word = outBits.getAtIndex(ValueLayout.JAVA_LONG, i >>> 6);
        return (word & (1L << (i & 63))) != 0;
    }
}
