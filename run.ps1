# The launcher. Run .\run.ps1 -Port 8081 to select a different local port.
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
# starting
# Compile on first use; run setup.ps1 again after Java edits.
if (!(Test-Path backend/build/StoreBackend.class)) { & "$PSScriptRoot/setup.ps1" }
& java -cp 'backend/build;backend/lib/*' StoreBackend $Port
