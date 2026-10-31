# Forward native CLI flags unchanged; named PowerShell parameters would consume flags such as -d.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDockerCommand = Get-Command docker -ErrorAction SilentlyContinue | Select-Object -First 1
$taskDockerPath = if ($taskDockerCommand) { $taskDockerCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $taskDockerPath)) { throw 'Docker CLI was not found. Open Docker Desktop and verify its installation path.' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot '.env.local'))) { throw 'Run scripts/prepare-local.ps1 first.' }
& $taskDockerPath compose --project-name coursetrace --env-file (Join-Path $taskRoot '.env.local') -f (Join-Path $taskRoot 'infra\compose.yml') @args
exit $LASTEXITCODE
