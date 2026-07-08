# Trackoff build script
# Compiles src\**\*.java into out\, then copies resource files so they
# land next to the compiled classes (getResourceAsStream() looks on the
# classpath, and out\ IS the classpath at runtime).

$ErrorActionPreference = "Stop"

$javafxLib  = "C:\javafx-sdk-26.0.1\lib"
$sqliteJar  = Join-Path $PSScriptRoot "lib\sqlite-jdbc-3.53.2.0.jar"
$srcRoot    = Join-Path $PSScriptRoot "src"
$outRoot    = Join-Path $PSScriptRoot "out"

if (-not (Test-Path $sqliteJar)) {
    Write-Host "SQLite JDBC not found at: $sqliteJar" -ForegroundColor Red
    exit 1
}

# Fresh output dir every build — cheap and avoids stale class weirdness.
if (Test-Path $outRoot) { Remove-Item -Recurse -Force $outRoot }
New-Item -ItemType Directory -Path $outRoot | Out-Null

# ---- 1. Compile ----
$sources = Get-ChildItem -Path $srcRoot -Filter *.java -Recurse | ForEach-Object { $_.FullName }

Write-Host "Compiling $($sources.Count) Java files..." -ForegroundColor Cyan

& javac `
    --module-path "$javafxLib" `
    --add-modules javafx.controls,javafx.media,java.desktop,javafx.fxml,javafx.swing `
    -cp "$sqliteJar" `
    -d $outRoot `
    $sources

if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

# ---- 2. Copy non-Java resources into out\ ----
# SQL migration scripts, CSS, etc. Anything under src\ that isn't .java.
Write-Host "Copying resources..." -ForegroundColor Cyan
Get-ChildItem -Path $srcRoot -Recurse -File |
    Where-Object { $_.Extension -ne ".java" } |
    ForEach-Object {
        $rel  = $_.FullName.Substring($srcRoot.Length + 1)
        $dest = Join-Path $outRoot $rel
        $destDir = Split-Path $dest -Parent
        if (-not (Test-Path $destDir)) {
            New-Item -ItemType Directory -Path $destDir -Force | Out-Null
        }
        Copy-Item -Path $_.FullName -Destination $dest -Force
    }

Write-Host "Build complete." -ForegroundColor Green

