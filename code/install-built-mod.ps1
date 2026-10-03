$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $projectRoot
$versionLine = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') |
    Where-Object { $_ -match '^mod_version=([0-9]+\.[0-9]+\.[0-9]+)$' } | Select-Object -First 1
if (-not $versionLine) { throw 'Missing or invalid mod_version' }
$modVersion = $versionLine.Split('=')[1]
$artifactName = "CSU-YSU-contribution-system-$modVersion.jar"
$artifact = Join-Path $projectRoot "build\libs\$artifactName"
if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Built JAR is missing: $artifact" }
$targets = @(
    @{ Name = 'client'; Path = 'F:\我的世界\PCL2\.minecraft\versions\26.3-Fabric 0.19.5\mods' },
    @{ Name = 'server'; Path = 'F:\server\server263\mods' }
)

# Preflight every exact target before moving any existing mod. Never touch other mod filenames.
foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target.Path -PathType Container)) { throw "Mods directory is missing: $($target.Path)" }
    $resolved = (Resolve-Path -LiteralPath $target.Path).Path
    if ($resolved.TrimEnd('\') -ne $target.Path.TrimEnd('\')) { throw 'Unexpected resolved mods path' }
    $target.Old = @(Get-ChildItem -LiteralPath $resolved -File -Filter 'CSU-YSU-contribution-system-*.jar')
    foreach ($old in $target.Old) {
        if ($old.DirectoryName.TrimEnd('\') -ne $resolved.TrimEnd('\')) { throw 'Old JAR is outside the exact mods directory' }
        try {
            $handle = [IO.File]::Open($old.FullName, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $handle.Dispose()
        } catch { throw "JAR is in use. Stop Minecraft/server, then run install-built-mod.ps1: $($old.FullName)" }
    }
}
$backupRoot = Join-Path $workspaceRoot ("mod-backups\" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
foreach ($target in $targets) {
    $backup = Join-Path $backupRoot $target.Name
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    $destination = Join-Path $target.Path $artifactName
    $staging = Join-Path $target.Path ($artifactName + '.new')
    Copy-Item -LiteralPath $artifact -Destination $staging
    foreach ($old in $target.Old) {
        Move-Item -LiteralPath $old.FullName -Destination (Join-Path $backup $old.Name)
    }
    Move-Item -LiteralPath $staging -Destination $destination
    if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $artifact).Hash) {
        throw "Installed JAR checksum mismatch: $destination"
    }
    Write-Host "Installed $destination"
}
Write-Host "Previous mod JARs backed up at $backupRoot. Restart Minecraft/server to load $modVersion."
