# Builds agent/publish/PcRemote.exe: one self-contained file, no .NET needed on
# the PC that runs it. Needs the .NET 10 SDK on the machine that builds it.
#
#   cd agent
#   .\build-exe.ps1            # tests + exe
#   .\build-exe.ps1 -SkipTests
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $SkipTests) {
    dotnet test PcRemote.Agent.slnx -c Release
    if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
}

Remove-Item -Recurse -Force publish -ErrorAction SilentlyContinue
dotnet publish src/PcRemote.Agent -p:PublishProfile=win-x64
if ($LASTEXITCODE -ne 0) { throw "Publish failed" }

$exe = Join-Path $PSScriptRoot 'publish\PcRemote.exe'
Write-Host ""
Write-Host "Listo: $exe ($([math]::Round((Get-Item $exe).Length / 1MB, 1)) MB)" -ForegroundColor Green
Write-Host "Cópialo donde quieras y ábrelo con doble clic."
