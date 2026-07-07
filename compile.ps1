# ============================================================
#  Rankify — Compile Script (PowerShell)
# ============================================================

$JavaFxLib = "C:\javafx-sdk-26.0.1\lib"
$LibDir    = "lib"
$SrcDir    = "src"
$OutDir    = "out"

Write-Host ""
Write-Host "=== Cleaning previous build ===" -ForegroundColor Cyan
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir | Out-Null

Write-Host ""
Write-Host "=== Verifying JavaFX SDK ===" -ForegroundColor Cyan
if (-not (Test-Path "$JavaFxLib\javafx.controls.jar")) {
    Write-Host "*** ERROR: JavaFX not found at $JavaFxLib" -ForegroundColor Red
    exit 1
}
Write-Host "Found JavaFX at $JavaFxLib"

Write-Host ""
Write-Host "=== Verifying third-party libs ===" -ForegroundColor Cyan
$SqliteJar = Get-ChildItem -Path $LibDir -Filter "sqlite-jdbc-*.jar" -ErrorAction SilentlyContinue |
             Select-Object -First 1
if (-not $SqliteJar) {
    Write-Host "*** ERROR: sqlite-jdbc-*.jar not found in $LibDir\" -ForegroundColor Red
    Write-Host "    Download from https://github.com/xerial/sqlite-jdbc/releases" -ForegroundColor Yellow
    exit 1
}
Write-Host "Found $($SqliteJar.Name)"

# Full classpath for compile (only needs the sqlite jar; JavaFX comes in via module path)
$ClassPath = $SqliteJar.FullName

Write-Host ""
Write-Host "=== Finding source files ===" -ForegroundColor Cyan
$sources = Get-ChildItem -Path $SrcDir -Filter *.java -Recurse |
           ForEach-Object { $_.FullName }
Write-Host "Found $($sources.Count) source files"

Write-Host ""
Write-Host "=== Compiling ===" -ForegroundColor Cyan
& javac `
    --module-path $JavaFxLib `
    --add-modules javafx.controls,javafx.media `
    -cp $ClassPath `
    -d $OutDir `
    $sources

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "*** COMPILE FAILED ***" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "=== Compile succeeded ===" -ForegroundColor Green
Write-Host "Output in: $OutDir\"