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
$packName = "CSU-YSU-items-$modVersion-resource-pack.zip"
$packArtifact = Join-Path $projectRoot "build\libs\$packName"
if (-not (Test-Path -LiteralPath $packArtifact -PathType Leaf)) { throw "Built resource pack is missing: $packArtifact" }
$targets = @(
    @{ Name = 'client'; Path = 'F:\我的世界\PCL2\.minecraft\versions\26.3-Fabric 0.19.5\mods'; Source = $artifact; FileName = $artifactName; Pattern = 'CSU-YSU-contribution-system-*.jar' },
    @{ Name = 'server'; Path = 'F:\server\server263\mods'; Source = $artifact; FileName = $artifactName; Pattern = 'CSU-YSU-contribution-system-*.jar' },
    @{ Name = 'resourcepacks'; Path = 'F:\我的世界\PCL2\.minecraft\versions\26.3-Fabric 0.19.5\resourcepacks'; Source = $packArtifact; FileName = $packName; Pattern = 'CSU-YSU-items-*.zip' }
)

# Preflight every exact target before moving any existing mod. Never touch other mod filenames.
foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target.Path -PathType Container)) { throw "Mods directory is missing: $($target.Path)" }
    $resolved = (Resolve-Path -LiteralPath $target.Path).Path
    if ($resolved.TrimEnd('\') -ne $target.Path.TrimEnd('\')) { throw 'Unexpected resolved mods path' }
    $target.Old = @(Get-ChildItem -LiteralPath $resolved -File -Filter $target.Pattern)
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
    $destination = Join-Path $target.Path $target.FileName
    $staging = Join-Path $target.Path ($target.FileName + '.new')
    Copy-Item -LiteralPath $target.Source -Destination $staging
    foreach ($old in $target.Old) {
        Move-Item -LiteralPath $old.FullName -Destination (Join-Path $backup $old.Name)
    }
    Move-Item -LiteralPath $staging -Destination $destination
    if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $target.Source).Hash) {
        throw "Installed JAR checksum mismatch: $destination"
    }
    Write-Host "Installed $destination"
}
Write-Host "Previous mod JARs backed up at $backupRoot. Restart Minecraft/server to load $modVersion."
