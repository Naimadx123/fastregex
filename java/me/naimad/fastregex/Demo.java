package me.naimad.fastregex;

public class Demo {
    public static void main(String[] args) {
        long h = FastRegex.compile("(?i)hello\\s+world.*");

        String[] batch = new String[] {
                "hello world!!!",
                "nope",
                "HeLLo   WoRLD and more",
                "xyz"
        };

        FastRegex.PackedUtf8 packed = FastRegex.packUtf8Direct(batch);

        long[] outBits = new long[(batch.length + 63) / 64];

        FastRegex.batchMatchesUtf8Direct(h, packed.data, packed.offsets, packed.lengths, outBits);

        for (int i = 0; i < batch.length; i++) {
            System.out.println(i + " => " + FastRegex.getBit(outBits, i) + " | " + batch[i]);
        }

        FastRegex.release(h);
    }
}