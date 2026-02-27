# build.ps1 - Simple build script for fastregex

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$rustDir = Join-Path $root "rust"
$javaDir = Join-Path $root "java"
$distDir = Join-Path $root "dist"

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
jar cvf fastregex.jar me\naimad\fastregex\FastRegex.class me\naimad\fastregex\FastRegex`$PackedUtf8.class
Copy-Item fastregex.jar (Join-Path $distDir "fastregex.jar")
Pop-Location

Write-Host "Build complete! Artifacts in $distDir"
Write-Host "To run the demo:"
Write-Host "  cd dist"
Write-Host "  java -cp 'fastregex.jar;..\java' -Djava.library.path=. me.naimad.fastregex.Demo"
