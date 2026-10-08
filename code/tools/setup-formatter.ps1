$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$directory = Join-Path $workspace '.test-tools'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$target = Join-Path $directory 'google-java-format-1.37.0-all-deps.jar'
if (-not (Test-Path -LiteralPath $target)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.37.0/google-java-format-1.37.0-all-deps.jar' -OutFile $target
}
Write-Output "Formatter ready: $target"
