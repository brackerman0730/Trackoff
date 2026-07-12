# Trackoff iOS backend launcher
$ErrorActionPreference = "Stop"

$sqliteJar = Join-Path $PSScriptRoot "lib\sqlite-jdbc-3.53.2.0.jar"
$outRoot   = Join-Path $PSScriptRoot "out"

& java --enable-native-access=ALL-UNNAMED -cp "$outRoot;$sqliteJar" com.trackoffios.Main
