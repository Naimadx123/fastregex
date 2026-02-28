# build.ps1 - Simple build script for fastregex

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$rustDir = Join-Path $root "rust"
$javaDir = Join-Path $root "java"
$distDir = Join-Path $root "dist"
$jar = "jar"

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

Write-Host "Building Rust library..."
Push-Location $rustDir
cargo build --release
Pop-Location

$dllPath = Join-Path $rustDir "target\release\fastregex.dll"
if (-not (Test-Path $dllPath)) {
    Write-Error "Could not find fastregex.dll at $dllPath"
}
Copy-Item $dllPath (Join-Path $distDir "fastregex.dll")

Write-Host "Compiling Java sources..."
Push-Location $javaDir
# Compiling all Java files
Get-ChildItem -Recurse -Filter "*.java" | ForEach-Object { javac $_.FullName }

Write-Host "Packaging fastregex.jar..."
# Only include FastRegex and its inner classes in the jar
& $jar cvf fastregex.jar me\naimad\fastregex\FastRegex.class me\naimad\fastregex\FastRegex`$PackedUtf8.class
Copy-Item fastregex.jar (Join-Path $distDir "fastregex.jar")
Pop-Location

Write-Host "Build complete! Artifacts in $distDir"
Write-Host "To run the demo:"
Write-Host "  cd dist"
Write-Host "  java '-Djava.library.path=.' -cp 'fastregex.jar;..\java' me.naimad.fastregex.Demo"
