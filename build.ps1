# build.ps1 - Multi-platform build script for fastregex

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$rustDir = Join-Path $root "rust"
$javaDir = Join-Path $root "java"
$distDir = Join-Path $root "dist"
$jar = "jar"

# Detect OS and Arch for bundling
$isWindows = $env:OS -like "*Windows*"
$currentOs = if ($isWindows) { "windows" } else { "linux" }
$currentArch = if ($env:PROCESSOR_ARCHITECTURE -eq "AMD64" -or $env:PROCESSOR_ARCHITEW6432 -eq "AMD64") { "x86_64" } else { "aarch64" }

Write-Host "Checking requirements..."
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Error "cargo command not found. Please ensure Rust is installed."
}
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Error "javac command not found. Please ensure JDK is installed and in your PATH."
}
if (-not (Get-Command $jar -ErrorAction SilentlyContinue)) {
    # Try to find jar in common locations if it's not in PATH
    $possibleJarPaths = @(
        "$env:JAVA_HOME\bin\jar.exe",
        "C:\Program Files\Java\jdk*\bin\jar.exe",
        "C:\Program Files (x86)\Java\jdk*\bin\jar.exe"
    )
    foreach ($p in $possibleJarPaths) {
        if ($p -and (Test-Path $p)) {
            $found = Get-ChildItem -Path $p -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) {
                $jar = $found.FullName
                Write-Host "Found jar at: $jar"
                break
            }
        }
    }
}
if (-not (Get-Command $jar -ErrorAction SilentlyContinue)) {
    Write-Error "jar command not found and could not be located in common paths. Please ensure JDK is installed and jar is in your PATH or JAVA_HOME is set."
}

Write-Host "Creating dist directory..."
if (-not (Test-Path $distDir)) {
    New-Item -ItemType Directory -Path $distDir | Out-Null
}

Write-Host "Building Rust library for current platform ($currentOs-$currentArch)..."
# Tip: For cross-compilation from Windows to Linux, you can use cargo-zigbuild:
#   cargo install cargo-zigbuild
#   cargo zigbuild --target x86_64-unknown-linux-gnu --release
Push-Location $rustDir
cargo build --release
Pop-Location

# Prepare native library for JAR bundling
# We store it in java/native/{os}-{arch}/ so the JAR packager can find it easily
$nativeResBase = Join-Path $javaDir "native"
$nativeResDir = Join-Path $nativeResBase "$currentOs-$currentArch"
if (-not (Test-Path $nativeResDir)) {
    New-Item -ItemType Directory -Path $nativeResDir -Force | Out-Null
}

$libPrefix = if ($currentOs -eq "windows") { "" } else { "lib" }
$libExt = if ($currentOs -eq "windows") { ".dll" } else { ".so" }
$libName = $libPrefix + "fastregex" + $libExt
$builtLibPath = Join-Path $rustDir "target\release\$libName"

if (-not (Test-Path $builtLibPath)) {
    Write-Error "Could not find built library at $builtLibPath"
}
Copy-Item $builtLibPath (Join-Path $nativeResDir $libName) -Force
# Also copy to dist for convenience
Copy-Item $builtLibPath (Join-Path $distDir $libName) -Force

Write-Host "Compiling Java sources..."
Push-Location $javaDir
# Compiling all Java files at once for efficiency and to resolve dependencies
$javaFiles = Get-ChildItem -Recurse -Filter "*.java" | ForEach-Object { Resolve-Path $_.FullName -Relative }
javac $javaFiles

Write-Host "Packaging fastregex.jar with bundled native libraries..."
# Include all classes and the native/ directory structure
# This makes the JAR self-contained
& $jar cvf fastregex.jar me\naimad\fastregex\*.class native\
Copy-Item fastregex.jar (Join-Path $distDir "fastregex.jar")
Pop-Location

Write-Host "Build complete! Artifacts in $distDir"
Write-Host "To run the demo (native library loads automatically from JAR):"
Write-Host "  cd dist"
Write-Host "  java -cp fastregex.jar me.naimad.fastregex.Demo"
