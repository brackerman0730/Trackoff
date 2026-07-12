# Trackoff iOS backend build script
$ErrorActionPreference = "Stop"

$sqliteJar = Join-Path $PSScriptRoot "lib\sqlite-jdbc-3.53.2.0.jar"
$srcRoot   = Join-Path $PSScriptRoot "src"
$outRoot   = Join-Path $PSScriptRoot "out"

if (Test-Path $outRoot) { Remove-Item -Recurse -Force $outRoot }
New-Item -ItemType Directory -Path $outRoot | Out-Null

$sources = Get-ChildItem -Path $srcRoot -Filter *.java -Recurse | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) Java files..." -ForegroundColor Cyan

& javac -encoding UTF-8 -cp "$sqliteJar" -d $outRoot $sources

if ($LASTEXITCODE -ne 0) {
    Write-Host "Compilation failed." -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "Build complete." -ForegroundColor Green
