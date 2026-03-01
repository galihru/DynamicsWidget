param(
  [Parameter(Mandatory = $true)]
  [string]$ShortcutName,
  [Parameter(Mandatory = $true)]
  [string]$TargetPath,
  [string]$Arguments = "",
  [string]$WorkingDirectory = ""
)

$ErrorActionPreference = "Stop"

$startupDir = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startupDir "$ShortcutName.lnk"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $TargetPath
$shortcut.Arguments = $Arguments
if ($WorkingDirectory -ne "") {
  $shortcut.WorkingDirectory = $WorkingDirectory
}
$shortcut.WindowStyle = 7
$shortcut.Save()

Write-Host "Startup shortcut registered:" $shortcutPath -ForegroundColor Green

