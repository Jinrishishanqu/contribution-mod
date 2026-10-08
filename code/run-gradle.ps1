$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $projectRoot
$javaHome = Join-Path $workspaceRoot '.jdk\microsoft-jdk-25.0.4'

if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Project Java 25 was not found at: $javaHome"
}

$env:JAVA_HOME = $javaHome
$env:GRADLE_USER_HOME = Join-Path $workspaceRoot '.gradle-home'

Push-Location -LiteralPath $projectRoot
try {
    if (@($args | Where-Object { $_ -match '(^|:)build$' }).Count -gt 0) {
        & (Join-Path $projectRoot 'tools\update-documentation.ps1')
    }
    & (Join-Path $projectRoot 'gradlew.bat') @args
    $buildExit = $LASTEXITCODE
    if ($buildExit -eq 0 -and @($args | Where-Object { $_ -match '(^|:)build$' }).Count -gt 0) {
        & (Join-Path $projectRoot 'install-built-mod.ps1')
    }
} finally { Pop-Location }
exit $buildExit
