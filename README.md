# FastRegex

A fast regex library for Java using Rust's `regex` crate under the hood via JNI.

## Project Structure

- `rust/`: Rust implementation of the JNI library.
- `java/`: Java classes and wrapper for the native library.
- `dist/`: Build artifacts (`fastregex.jar` and `fastregex.dll`).

## Building from source

Requirements:
- Rust (Cargo)
- JDK (javac, jar)
- PowerShell (on Windows)

Run the build script:
```powershell
.\build.ps1
```

This will create the `dist/` directory with:
- `fastregex.jar`: Contains the `me.naimad.fastregex.FastRegex` class.
- `fastregex.dll`: The native library (on Windows).

## Usage

1. Add `fastregex.jar` to your classpath.
2. Ensure the native library (`fastregex.dll` or `libfastregex.so`) is in your `java.library.path`.

Example code:

```java
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

```powershell
cd dist
java "-Djava.library.path=." -cp "fastregex.jar;..\java" me.naimad.fastregex.Demo
```
