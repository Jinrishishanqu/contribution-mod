$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $projectRoot
$javaHome = Join-Path $workspaceRoot '.jdk\microsoft-jdk-25.0.4'

if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Project Java 25 was not found at: $javaHome"
}

$env:JAVA_HOME = $javaHome
$env:GRADLE_USER_HOME = Join-Path $workspaceRoot '.gradle-home'

& (Join-Path $projectRoot 'gradlew.bat') @args
exit $LASTEXITCODE
