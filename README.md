# FastRegex

A fast regex library for Java using Rust's `regex` crate under the hood
via JNI.

## Project Structure

-   `rust/`: Rust implementation of the JNI library.
-   `java/`: Java classes and wrapper for the native library.
-   `dist/`: Build artifacts (`fastregex.jar` and `fastregex.dll`).

## Building from source

Requirements: - Rust (Cargo) - JDK 17 or 21 (javac, jar) - PowerShell
(on Windows)

Run the build script:

``` powershell
.\build.ps1
```

This will create the `dist/` directory with: - `fastregex.jar`: Contains
the `me.naimad.fastregex.FastRegex` class. - `fastregex.dll`: The native
library (on Windows).

## Usage

1.  Add `fastregex.jar` to your classpath.
2.  Ensure the native library (`fastregex.dll` or `libfastregex.so`) is
    in your `java.library.path`.

Example code:

``` java
import me.naimad.fastregex.FastRegex;

// Compile a regex (once)
long handle = FastRegex.compile("(?i)hello\\s+world.*");

// Prepare a batch of strings
String[] batch = {"hello world", "nope", "HELLO   WORLD!"};
FastRegex.PackedUtf8 packed = FastRegex.packUtf8Direct(batch);

// Allocate space for results (bitset)
long[] outBits = new long[(batch.length + 63) / 64];

// Perform batch match
FastRegex.batchMatchesUtf8Direct(handle, packed.data, packed.offsets, packed.lengths, outBits);

// Check results
for (int i = 0; i < batch.length; i++) {
    boolean matches = FastRegex.getBit(outBits, i);
    System.out.println(batch[i] + " matches: " + matches);
}

// Release native resources
FastRegex.release(handle);
```

## Benchmarks (JMH)

Environment: - OS: Windows 10 - JVM: JDK 17.0.10 (HotSpot), JMH 1.37 -
Mode: Throughput (ops/ms), Warmup: 5×1s, Measurement: 8×1s, Forks: 2,
Threads: 1

### Summary

FastRegex vs JDK (`matches()` loop):

-   n=64: **2.7× to 3.2× faster**
-   n=512: **3.3× to 4.8× faster**

### Raw results (ops/ms)

Benchmark                      n regex           Score
  -------------------------- ----- ---------- ----------
fastregex_match_only          64 username      535.402
fastregex_match_only          64 HTTP         1133.116
fastregex_match_only          64 email         435.196
fastregex_match_only         512 username       78.851
fastregex_match_only         512 HTTP          210.821
fastregex_match_only         512 email          60.727
fastregex_pack_and_match      64 username      265.278
fastregex_pack_and_match      64 HTTP          363.485
fastregex_pack_and_match      64 email         248.459
fastregex_pack_and_match     512 username       40.504
fastregex_pack_and_match     512 HTTP           46.034
fastregex_pack_and_match     512 email          30.162
jdk_matches_loop              64 username      168.826
jdk_matches_loop              64 HTTP          356.790
jdk_matches_loop              64 email         157.884
jdk_matches_loop             512 username       20.740
jdk_matches_loop             512 HTTP           43.592
jdk_matches_loop             512 email          18.363

## Running the Demo

``` powershell
cd dist
java "-Djava.library.path=." -cp "fastregex.jar;..\java" me.naimad.fastregex.Demo
```
