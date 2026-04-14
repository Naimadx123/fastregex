package me.naimad.fastregex;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class Demo {
    public static void main(String[] args) {
        String[] batch = new String[] {
                "hello world!!!",
                "nope",
                "HeLLo   WoRLD and more",
                "xyz"
        };

        System.out.println("--- Using High-Level Friendly API ---");
        try (FastRegex.Regex regex = FastRegex.compile("(?i)hello\\s+world.*")) {
            boolean[] results = regex.batchMatches(batch);
            for (int i = 0; i < batch.length; i++) {
                System.out.println(i + " => " + results[i] + " | " + batch[i]);
            }

            boolean single = regex.matches("hello world from java");
            System.out.println("Single match: " + single);
        }

        System.out.println("\n--- Using Low-Level API (Maximum Performance) ---");
        long h = FastRegex.compileNative("(?i)hello\\s+world.*");
        try (FastRegex.PackedUtf8 packed = FastRegex.packUtf8(batch)) {
            try (Arena arena = Arena.ofConfined()) {
                int words = (batch.length + 63) / 64;
                MemorySegment outBits = arena.allocate(ValueLayout.JAVA_LONG, words);

                FastRegex.batchMatchesUtf8(h, packed.data, packed.offsets, packed.lengths, outBits, (long) packed.num);

                System.out.println("Batch match (low-level) result at index 0: " + FastRegex.getBit(outBits, 0));
            }
        }
        FastRegex.release(h);
    }
}
