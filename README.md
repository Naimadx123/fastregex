# FastRegex

A fast regex library for Java using Rust's `regex` crate under the hood
via JNI.

## Project Structure

-   `rust/`: Rust implementation of the JNI library.
-   `java/`: Java classes and wrapper for the native library.
-   `dist/`: Build artifacts (`fastregex.jar` and `fastregex.dll`).

## Building from source

Requirements: - Rust (Cargo) - JDK 17 or 21 (javac, jar) - PowerShell
(on Windows) or Bash (on Linux)

Run the build script:

**Windows:**
```powershell
.\build.ps1
```

**Linux / macOS:**
```bash
./build.sh
```

This will create the `dist/` directory with a self-contained
`fastregex.jar` that includes the native library for your current
platform.

### Multi-platform JAR

The GitHub Actions workflow automatically builds and packages native libraries for:
-   Windows (x86_64)
-   Linux (x86_64)
-   macOS (x86_64 and aarch64)

The resulting `fastregex.jar` is a single file that works across all these platforms.

To create it manually:
1.  Run `.\build.ps1` on Windows.
2.  Run `./build.sh` on Linux and/or macOS.
3.  Ensure all binaries are placed in `java/native/{os}-{arch}/`.
4.  The final `fastregex.jar` will contain all native libraries found in `java/native/`.

## Usage

1.  Add `fastregex.jar` to your classpath.
2.  The library will automatically extract and load the correct native
    binary for your OS and architecture from the JAR.

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

## Running the Demo

``` bash
# Running the demo from the self-contained JAR
java -cp dist/fastregex.jar me.naimad.fastregex.Demo
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

| Benchmark | n | regex | Score (ops/ms) |
| --------- | -:| ----- | --------------:|
| fastregex_match_only | 64 | ^[A-Za-z0-9_]{3,16}$ | 535.402 |
| fastregex_match_only | 64 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 1133.116 |
| fastregex_match_only | 64 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 435.196 |
| fastregex_match_only | 512 | ^[A-Za-z0-9_]{3,16}$ | 78.851 |
| fastregex_match_only | 512 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 210.821 |
| fastregex_match_only | 512 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 60.727 |
| fastregex_pack_and_match | 64 | ^[A-Za-z0-9_]{3,16}$ | 265.278 |
| fastregex_pack_and_match | 64 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 363.485 |
| fastregex_pack_and_match | 64 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 248.459 |
| fastregex_pack_and_match | 512 | ^[A-Za-z0-9_]{3,16}$ | 40.504 |
| fastregex_pack_and_match | 512 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 46.034 |
| fastregex_pack_and_match | 512 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 30.162 |
| jdk_matches_loop | 64 | ^[A-Za-z0-9_]{3,16}$ | 168.826 |
| jdk_matches_loop | 64 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 356.790 |
| jdk_matches_loop | 64 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 157.884 |
| jdk_matches_loop | 512 | ^[A-Za-z0-9_]{3,16}$ | 20.740 |
| jdk_matches_loop | 512 | ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 43.592 |
| jdk_matches_loop | 512 | ^[^@\s]{1,64}@[^@\s]{1,255}$ | 18.363 |

### Speedup table

**FastRegex is 2.7× to 4.8× faster than Java's built-in regex in these benchmarks.**

| Regex | n | FastRegex match_only (ops/ms) | JDK matches (ops/ms) | Speedup |
| ----- | -:| ----------------------------:| --------------------:| -------:|
| ^[A-Za-z0-9_]{3,16}$ | 64 | 535.402 | 168.826 | 3.17× |
| ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 64 | 1133.116 | 356.790 | 3.18× |
| ^[^@\s]{1,64}@[^@\s]{1,255}$ | 64 | 435.196 | 157.884 | 2.76× |
| ^[A-Za-z0-9_]{3,16}$ | 512 | 78.851 | 20.740 | 3.80× |
| ^(?:GET\|POST)\s+/[A-Za-z0-9/_-]{1,64}\s+HTTP/1\.[01]$ | 512 | 210.821 | 43.592 | 4.84× |
| ^[^@\s]{1,64}@[^@\s]{1,255}$ | 512 | 60.727 | 18.363 | 3.31× |

