# Trackoff launcher

$ErrorActionPreference = "Stop"

$javafxLib = "C:\javafx-sdk-26.0.1\lib"
$sqliteJar = Join-Path $PSScriptRoot "lib\sqlite-jdbc-3.53.2.0.jar"
$outRoot   = Join-Path $PSScriptRoot "out"
$classpath = "$outRoot;$sqliteJar"

# Ensure Trackoff's data directory exists before Java touches it.
$dataDir = Join-Path $env:USERPROFILE ".trackoff"
if (-not (Test-Path $dataDir)) {
    New-Item -ItemType Directory -Path $dataDir | Out-Null
}

& java `
    --module-path "$javafxLib" `
    --add-modules javafx.controls,javafx.media,java.desktop `
    --enable-native-access=javafx.graphics `
    --enable-native-access=ALL-UNNAMED `
    "-Djavax.net.ssl.trustStoreType=Windows-ROOT" `
    "-Djava.net.useSystemProxies=true" `
    "-cp" "$classpath" `
    "com.trackoff.Main"