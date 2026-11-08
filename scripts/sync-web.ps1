# Sync the built web front-end into app/src/main/assets/www/terminal/.
#
# Convention: app/src/main/assets/www/ is generated output. Never edit it by hand;
# change web/src/ and run this script.
#
# Usage:  .\scripts\sync-web.ps1               # install deps and rebuild
#         .\scripts\sync-web.ps1 -SkipInstall
param(
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot      # ZeroLinux/
$web = Join-Path $root 'web'
$dest = Join-Path $root 'app\src\main\assets\www\terminal'

if (-not (Test-Path $web)) { throw "web/ not found: $web" }

Push-Location $web
try {
    if (-not $SkipInstall) {
        Write-Host '> npm install'
        npm install --no-audit --no-fund | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'npm install failed' }
    }
    Write-Host '> node build.mjs'
    node build.mjs
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
} finally {
    Pop-Location
}

$dist = Join-Path $web 'dist'
if (-not (Test-Path $dist)) { throw "build output missing: $dist" }

New-Item -ItemType Directory -Force -Path $dest | Out-Null
Get-ChildItem $dest -Recurse -File | Remove-Item -Force

$files = Get-ChildItem $dist -Recurse -File
foreach ($f in $files) {
    $rel = $f.FullName.Substring($dist.Length).TrimStart('\', '/')
    $target = Join-Path $dest $rel
    $dir = Split-Path -Parent $target
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    Copy-Item $f.FullName $target -Force
    Write-Host ("  + {0}  {1:N0} B" -f $rel, $f.Length)
}

Write-Host "synced $($files.Count) file(s) -> $dest"
