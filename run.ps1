# ============================================================
#  Rankify — Run Script (PowerShell)
# ============================================================

$JavaFxLib  = "C:\javafx-sdk-26.0.1\lib"
$LibDir     = "lib"
$OutDir     = "out"
$MainClass  = "com.rankify.Main"

if (-not (Test-Path "$OutDir\com\rankify\Main.class")) {
    Write-Host "No compiled classes found. Running compile first..." -ForegroundColor Yellow
    & "$PSScriptRoot\compile.ps1"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not (Test-Path "$JavaFxLib\javafx.controls.jar")) {
    Write-Host "*** ERROR: JavaFX not found at $JavaFxLib" -ForegroundColor Red
    exit 1
}

# Find the SQLite jar (whatever version is present)
$SqliteJar = Get-ChildItem -Path $LibDir -Filter "sqlite-jdbc-*.jar" -ErrorAction SilentlyContinue |
             Select-Object -First 1
if (-not $SqliteJar) {
    Write-Host "*** ERROR: sqlite-jdbc-*.jar not found in $LibDir\" -ForegroundColor Red
    exit 1
}

# Ensure Trackoff's data directory exists (SQLite DB + OAuth tokens live here)
$DataDir = Join-Path $env:USERPROFILE ".trackoff"
if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir | Out-Null
    Write-Host "Created data directory: $DataDir" -ForegroundColor DarkGray
}

# Classpath: compiled output + sqlite jar (semicolon-separated on Windows)
$ClassPath = "$OutDir;$($SqliteJar.FullName)"

Write-Host ""
Write-Host "=== Launching Rankify ===" -ForegroundColor Cyan
& java `
    --module-path $JavaFxLib `
    --add-modules javafx.controls,javafx.media,java.desktop `
    "-Djava.net.useSystemProxies=true" `
    -cp $ClassPath `
    $MainClass