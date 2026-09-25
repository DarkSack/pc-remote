# Builds the agent as ONE self-contained executable: dist\PcRemote.exe.
# The PC that runs it needs nothing installed (no .NET); double-click and it
# sits in the tray. Run from any folder:
#
#   powershell -ExecutionPolicy Bypass -File agent\publish.ps1
#
param(
    [string]$Runtime = "win-x64",
    [string]$Output = (Join-Path $PSScriptRoot "..\dist")
)
$ErrorActionPreference = "Stop"

$project = Join-Path $PSScriptRoot "src\PcRemote.Agent\PcRemote.Agent.csproj"
$staging = Join-Path ([System.IO.Path]::GetTempPath()) "pcremote-publish-$([guid]::NewGuid().ToString('N'))"

dotnet publish $project -c Release -r $Runtime -p:PublishSingleFile=true -o $staging --nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed ($LASTEXITCODE)" }

New-Item -ItemType Directory -Force $Output | Out-Null
$exe = Join-Path $Output "PcRemote.exe"
Copy-Item (Join-Path $staging "PcRemote.Agent.exe") $exe -Force
Remove-Item $staging -Recurse -Force

$size = [math]::Round((Get-Item $exe).Length / 1MB, 1)
Write-Host "Listo: $exe ($size MB)"
