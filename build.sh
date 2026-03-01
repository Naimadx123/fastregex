#!/bin/bash
set -e

# build.sh - Build script for fastregex on Linux/macOS

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="$ROOT/rust"
JAVA_DIR="$ROOT/java"
DIST_DIR="$ROOT/dist"

echo "Checking requirements..."
if ! command -v cargo &> /dev/null; then
    echo "cargo command not found. Please ensure Rust is installed."
    exit 1
fi
if ! command -v javac &> /dev/null; then
    echo "javac command not found. Please ensure JDK is installed and in your PATH."
    exit 1
fi
if ! command -v jar &> /dev/null; then
    echo "jar command not found. Please ensure JDK is installed and in your PATH."
    exit 1
fi

echo "Creating dist directory..."
mkdir -p "$DIST_DIR"

# Detect OS and Arch
OS_NAME=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH_NAME=$(uname -m)

if [ "$OS_NAME" == "darwin" ]; then
    OS="macos"
else
    OS="linux"
fi

# Normalize arch
if [ "$ARCH_NAME" == "x86_64" ] || [ "$ARCH_NAME" == "amd64" ]; then
    ARCH="x86_64"
elif [ "$ARCH_NAME" == "aarch64" ] || [ "$ARCH_NAME" == "arm64" ]; then
    ARCH="aarch64"
else
    ARCH="$ARCH_NAME"
fi

echo "Building Rust library for current platform ($OS-$ARCH)..."
cd "$RUST_DIR"
cargo build --release
cd "$ROOT"

# Prepare native library for JAR bundling
NATIVE_RES_DIR="$JAVA_DIR/me/naimad/fastregex/native_bin/$OS-$ARCH"
mkdir -p "$NATIVE_RES_DIR"

LIB_PREFIX="lib"
if [ "$OS" == "macos" ]; then
    LIB_EXT=".dylib"
else
    LIB_EXT=".so"
fi
LIB_NAME="${LIB_PREFIX}fastregex${LIB_EXT}"
BUILT_LIB_PATH="$RUST_DIR/target/release/$LIB_NAME"

if [ ! -f "$BUILT_LIB_PATH" ]; then
    echo "Could not find built library at $BUILT_LIB_PATH"
    exit 1
fi

cp "$BUILT_LIB_PATH" "$NATIVE_RES_DIR/$LIB_NAME"
cp "$BUILT_LIB_PATH" "$DIST_DIR/$LIB_NAME"

echo "Compiling Java sources..."
cd "$JAVA_DIR"
# Clean up any old class files
rm -f me/naimad/fastregex/*.class
# Explicitly list all files to ensure they are compiled together in correct order
javac -d . me/naimad/fastregex/FastRegexLoader.java me/naimad/fastregex/FastRegex.java me/naimad/fastregex/Demo.java

echo "Packaging fastregex.jar with bundled native libraries..."
jar cvf fastregex.jar me/naimad/fastregex/*.class me/naimad/fastregex/native_bin/
cp fastregex.jar "$DIST_DIR/fastregex.jar"
cd "$ROOT"

echo "Build complete! Artifacts in $DIST_DIR"
echo "To run the demo:"
echo "  cd dist"
echo "  java -cp fastregex.jar me.naimad.fastregex.Demo"
