# Publica el relay para Linux en deploy\relay\app y sube la carpeta al VPS.
#   powershell -ExecutionPolicy Bypass -File deploy\relay\publicar.ps1 [-Vps root@144.126.155.36]
param([string]$Vps = "root@144.126.155.36")
$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
$project = Join-Path $here "..\..\agent\src\PcRemote.Relay\PcRemote.Relay.csproj"
$app = Join-Path $here "app"

if (Test-Path $app) { Remove-Item $app -Recurse -Force }
dotnet publish $project -c Release -r linux-x64 --self-contained false -o $app --nologo
if ($LASTEXITCODE -ne 0) { throw "dotnet publish falló ($LASTEXITCODE)" }

ssh $Vps "mkdir -p /opt/pcremote-relay"
scp -r (Join-Path $here "Dockerfile") (Join-Path $here "docker-compose.yml") $app "${Vps}:/opt/pcremote-relay/"
if ($LASTEXITCODE -ne 0) { throw "scp falló ($LASTEXITCODE)" }
Write-Host "Subido. En el VPS: cd /opt/pcremote-relay && docker compose up -d --build"
