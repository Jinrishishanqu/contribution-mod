$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $project
$env:JAVA_HOME = Join-Path $workspace '.jdk\microsoft-jdk-25.0.4'
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle-home'
Push-Location -LiteralPath $project
try {
    & (Join-Path $project 'gradlew.bat') exportCommandReference --offline
    if ($LASTEXITCODE -ne 0) { throw 'Command export failed' }
    $runtime = Join-Path $env:LOCALAPPDATA '..\..\.cache\codex-runtimes\codex-primary-runtime\dependencies\node'
    $runtime = [IO.Path]::GetFullPath($runtime)
    $node = if ($env:CONTRIBUTION_DOC_NODE) { $env:CONTRIBUTION_DOC_NODE } else { Join-Path $runtime 'bin\node.exe' }
    $modules = if ($env:CONTRIBUTION_DOC_MODULES) { $env:CONTRIBUTION_DOC_MODULES } else { Join-Path $runtime 'node_modules' }
    if (-not (Test-Path -LiteralPath $node)) { throw 'Set CONTRIBUTION_DOC_NODE and CONTRIBUTION_DOC_MODULES to the artifact-tool runtime' }
    $link = Join-Path $PSScriptRoot 'node_modules'
    if (-not (Test-Path -LiteralPath $link)) { New-Item -ItemType Junction -Path $link -Target $modules | Out-Null }
    & $node (Join-Path $PSScriptRoot 'build-documentation.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'HTML/XLSX generation failed' }
} finally { Pop-Location }
