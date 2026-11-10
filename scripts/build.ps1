$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path $root 'aquila-web'
$serverPom = Join-Path $root 'aquila-server\pom.xml'
$jar = Join-Path $root 'aquila-server\target\aquila-server-0.1.0-SNAPSHOT.jar'

Push-Location $web
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw "frontend typecheck failed with exit code $LASTEXITCODE" }
    & npm.cmd test -- --run
    if ($LASTEXITCODE -ne 0) { throw "frontend tests failed with exit code $LASTEXITCODE" }
    & npm.cmd run openapi:lint
    if ($LASTEXITCODE -ne 0) { throw "OpenAPI lint failed with exit code $LASTEXITCODE" }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "frontend build failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

& mvn -f $serverPom clean package
if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
if (-not (Test-Path $jar)) { throw "Expected application jar was not created: $jar" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entries = @($archive.Entries | ForEach-Object { $_.FullName })
    if ($entries -notcontains 'BOOT-INF/classes/static/index.html') {
        throw 'Packaged jar is missing BOOT-INF/classes/static/index.html'
    }
    if (-not ($entries | Where-Object { $_ -like 'BOOT-INF/classes/static/assets/*' })) {
        throw 'Packaged jar is missing built frontend assets'
    }
}
finally {
    $archive.Dispose()
}

Write-Output "Build and same-origin packaging verified: $jar"
