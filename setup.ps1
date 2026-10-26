# The setup script. Download missing libraries and compile the Java server.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force backend/lib, backend/build | Out-Null
$packages = @(
    @('sqlite-jdbc-3.46.1.0.jar', 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar'),
    @('slf4j-api-2.0.13.jar', 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar'),
    @('slf4j-nop-2.0.13.jar', 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar')
)
# dependencies
# Reuse existing JARs so later builds work offline.
foreach ($package in $packages) {
    $destination = Join-Path 'backend/lib' $package[0]
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -Uri $package[1] -OutFile $destination
    }
}
# compiling
# Put compiled classes in backend/build and check the compiler exit code.
& javac -encoding UTF-8 -cp 'backend/lib/*' -d backend/build backend/StoreBackend.java
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
Write-Host 'Setup complete. Run .\run.ps1 to start the application.'
