param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
# These settings affect only this launcher and its children, never the system.
$env:CI = 'true'
$taskPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
function Invoke-ProjectStep {
    param([string]$Script, [string[]]$NativeArguments = @())
    & $taskPowerShell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $Script) @NativeArguments
    if ($LASTEXITCODE -ne 0) { throw "Project step failed: $Script (exit $LASTEXITCODE). See the output above." }
}
Write-Output '[1/4] Start CourseTrace MySQL, Redis, MinIO, Kafka and Qdrant. Docker Desktop must be running.'
Invoke-ProjectStep -Script 'docker.ps1' -NativeArguments @('up','-d','--wait','mysql','redis','minio','kafka','qdrant')
$taskMinioDeadline = [DateTime]::UtcNow.AddSeconds(45)
$taskMinioReady = $false
while ([DateTime]::UtcNow -lt $taskMinioDeadline) {
    try { $taskHealth = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:9000/minio/health/ready' -TimeoutSec 2; if ($taskHealth.StatusCode -eq 200) { $taskMinioReady = $true; break } } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $taskMinioReady) { throw 'MinIO readiness timed out. Inspect Docker Desktop container logs.' }
$taskQdrantDeadline = [DateTime]::UtcNow.AddSeconds(45)
$taskQdrantReady = $false
while ([DateTime]::UtcNow -lt $taskQdrantDeadline) {
    try { $taskQdrantHealth = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:6333/readyz' -TimeoutSec 2; if ($taskQdrantHealth.StatusCode -eq 200) { $taskQdrantReady = $true; break } } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $taskQdrantReady) { throw 'Qdrant readiness timed out. Inspect Docker Desktop container logs.' }
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
foreach ($taskMediaTool in @(@{Name='FFMPEG_PATH';Default='.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'},@{Name='FFPROBE_PATH';Default='.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'})) {
    $taskToolPath=[Environment]::GetEnvironmentVariable($taskMediaTool.Name)
    if (-not $taskToolPath) { $taskToolPath=Join-Path $taskRoot $taskMediaTool.Default }
    if (-not (Test-Path -LiteralPath $taskToolPath)) { throw ('Media tool not found: '+$taskMediaTool.Name+'. Use scripts/install-ffmpeg.ps1.') }
    & $taskToolPath -version *> $null
    if ($LASTEXITCODE -ne 0) { throw ('Media tool could not run: '+$taskMediaTool.Name) }
}
Write-Output '[2/4] Prepare frontend dependencies.'
$taskInstallArguments = @('--dir','client','install','--frozen-lockfile','--prefer-offline')
# pnpm junctions can break when the project directory is moved or renamed.
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'client\node_modules\vite\bin\vite.js'))) {
    $taskInstallArguments += '--force'
}
Invoke-ProjectStep -Script 'pnpm.ps1' -NativeArguments $taskInstallArguments
Write-Output '[3/4] Build the current backend. No paid AI tests are run.'
Invoke-ProjectStep -Script 'maven.ps1' -NativeArguments @('-DskipTests','package')
Write-Output '[4/4] Restart the development frontend and backend.'
Invoke-ProjectStep -Script 'stop-dev.ps1'
Invoke-ProjectStep -Script 'dev.ps1'
Write-Output 'Ready: http://127.0.0.1:5173'
Write-Output 'Use the IDEA configuration CourseTrace - Stop to stop the frontend and backend.'
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173' }
exit 0
