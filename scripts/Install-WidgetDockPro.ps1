param(
  [string]$InstallDir = "$env:LOCALAPPDATA\WidgetDockPro",
  [switch]$SkipRuntimeInstall,
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

function Ensure-WingetPackage {
  param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$Label
  )

  Write-Host "Checking $Label..." -ForegroundColor Cyan
  winget list --id $Id --exact | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Installing $Label..." -ForegroundColor Yellow
    winget install --id $Id --exact --accept-package-agreements --accept-source-agreements
  }
}

if (-not $SkipRuntimeInstall) {
  Write-Host "Runtime bootstrap..." -ForegroundColor Cyan
  if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw "winget tidak ditemukan. Install App Installer (Microsoft Store) atau jalankan dengan -SkipRuntimeInstall."
  }

  Ensure-WingetPackage -Id "EclipseAdoptium.Temurin.17.JRE" -Label "Java Runtime 17"
  Ensure-WingetPackage -Id "Microsoft.EdgeWebView2Runtime" -Label "WebView2 Runtime"
  Ensure-WingetPackage -Id "OpenJS.NodeJS.LTS" -Label "Node.js LTS"
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$scalaCliCandidates = @(
  (Join-Path $projectRoot "scala-cli.exe"),
  (Join-Path $projectRoot "..\scala-cli.exe")
)

$scalaCli = $null
foreach ($candidate in $scalaCliCandidates) {
  if (Test-Path $candidate) {
    $scalaCli = (Resolve-Path $candidate).Path
    break
  }
}

if (-not $scalaCli) {
  $cmd = Get-Command scala-cli -ErrorAction SilentlyContinue
  if ($cmd) { $scalaCli = $cmd.Source }
}

if (-not $SkipBuild) {
  if (-not $scalaCli) {
    throw "scala-cli tidak ditemukan. Install scala-cli atau jalankan installer dengan -SkipBuild dan sediakan WidgetDockPro.jar."
  }

  Write-Host "Building assembly JAR..." -ForegroundColor Cyan
  & $scalaCli package "$projectRoot\src\main\scala" --scala 3.3.1 --assembly -o "$projectRoot\WidgetDockPro.jar" --force
  if ($LASTEXITCODE -ne 0) {
    throw "Build JAR gagal."
  }
}

$jarCandidates = @(
  (Join-Path $projectRoot "WidgetDockPro.jar"),
  (Join-Path $projectRoot "target\scala-3.3.1\widgetdockpro_3-0.1.0.jar")
)

$jarPath = $null
foreach ($candidate in $jarCandidates) {
  if (Test-Path $candidate) {
    $jarPath = $candidate
    break
  }
}

if (-not $jarPath) {
  throw "JAR belum ada. Jalankan build: ..\\scala-cli.exe package src\\main\\scala --scala 3.3.1 --assembly -o WidgetDockPro.jar --force"
}

Write-Host "Preparing install directory: $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
$appDir = Join-Path $InstallDir "app"
New-Item -ItemType Directory -Path $appDir -Force | Out-Null

$jarDest = Join-Path $appDir "WidgetDockPro.jar"
Copy-Item $jarPath $jarDest -Force

$javaHome = $env:JAVA_HOME
$javaw = if ($javaHome) { Join-Path $javaHome "bin\javaw.exe" } else { "javaw" }

$launcherPath = Join-Path $InstallDir "Run-WidgetDockPro.bat"
$launcherContent = @"
@echo off
cd /d "$appDir"
start "" "$javaw" -jar "$jarDest"
"@
Set-Content -Path $launcherPath -Value $launcherContent -Encoding ASCII

$startupScript = Join-Path $PSScriptRoot "Register-Startup.ps1"
& $startupScript `
  -ShortcutName "WidgetDockPro" `
  -TargetPath $launcherPath `
  -WorkingDirectory $appDir

Write-Host "Starting WidgetDockPro in background..." -ForegroundColor Cyan
Start-Process -WindowStyle Hidden -FilePath $javaw -ArgumentList "-jar `"$jarDest`"" -WorkingDirectory $appDir

Write-Host "Installation complete." -ForegroundColor Green
Write-Host "Opening install folder..." -ForegroundColor Cyan
Start-Process explorer.exe $InstallDir
