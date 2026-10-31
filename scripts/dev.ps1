param([int]$ServerPort = 8080, [int]$ClientPort = 5173)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskRoot '.local\m1'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
foreach ($taskPort in @($ServerPort,$ClientPort)) {
    $taskClient = [Net.Sockets.TcpClient]::new()
    try {
        $taskAttempt = $taskClient.ConnectAsync('127.0.0.1',$taskPort)
        if ($taskAttempt.Wait(500) -and $taskClient.Connected) { throw "Port $taskPort is already in use. Use another port or stop the existing instance." }
    } catch [AggregateException] { }
    finally { $taskClient.Dispose() }
}
$taskJar = Join-Path $taskRoot 'server\target\coursetrace-server-0.0.1-SNAPSHOT.jar'
$taskRuntimeJar = Join-Path $taskOutput 'server-runtime.jar'
$taskVite = Join-Path $taskRoot 'client\node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run scripts/maven.ps1 verify first.' }
if (-not (Test-Path -LiteralPath $taskVite)) { throw 'Frontend dependencies are missing or their links broke after moving the folder. Run scripts/pnpm.ps1 --dir client install --frozen-lockfile --force.' }
$taskJavaHome = if ($env:COURSETRACE_JAVA_HOME) { $env:COURSETRACE_JAVA_HOME } else { $env:JAVA_HOME }
$taskJavaExe = Join-Path $taskJavaHome 'bin\java.exe'
# An ordinary PowerShell terminal may not inherit Codex's Node PATH.
$taskNodeCommand = Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$taskNodeExe = if ($taskNodeCommand) { $taskNodeCommand.Source } else { Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' }
if (-not (Test-Path -LiteralPath $taskNodeExe)) { throw 'Node.js was not found. Install Node.js >=22.12 or restore the existing Codex Node runtime.' }
$env:PATH = (Split-Path -Parent $taskNodeExe) + [IO.Path]::PathSeparator + $env:PATH
$env:SERVER_PORT = [string]$ServerPort
$env:COURSETRACE_API_TARGET = "http://127.0.0.1:$ServerPort"
$taskStarted = @()
try {
    # Windows locks a running JAR. Run a local copy so Maven can rebuild target independently.
    Copy-Item -LiteralPath $taskJar -Destination $taskRuntimeJar -Force
    $taskBackend = Start-Process -FilePath $taskJavaExe -ArgumentList @('-jar',('"' + $taskRuntimeJar + '"')) -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskOutput 'server.log') -RedirectStandardError (Join-Path $taskOutput 'server-error.log')
    $taskStarted += $taskBackend
    $taskFrontend = Start-Process -FilePath $taskNodeExe -ArgumentList @(('"' + $taskVite + '"'),'--host','127.0.0.1','--port',[string]$ClientPort,'--strictPort') -WorkingDirectory (Join-Path $taskRoot 'client') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskOutput 'client.log') -RedirectStandardError (Join-Path $taskOutput 'client-error.log')
    $taskStarted += $taskFrontend
    $taskProcesses = foreach ($taskProcess in $taskStarted) { @{id=$taskProcess.Id;startedAt=$taskProcess.StartTime.ToUniversalTime().ToString('o')} }
    $taskState = @{processes=@($taskProcesses);serverPort=$ServerPort;clientPort=$ClientPort}
    [IO.File]::WriteAllText((Join-Path $taskOutput 'dev-processes.json'),($taskState | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    foreach ($taskUrl in @("http://127.0.0.1:$ServerPort/actuator/health", "http://127.0.0.1:$ClientPort/")) {
        $taskDeadline = [DateTime]::UtcNow.AddSeconds(45)
        $taskReady = $false
        while ([DateTime]::UtcNow -lt $taskDeadline) {
            try { $taskResponse = Invoke-WebRequest -UseBasicParsing -Uri $taskUrl -TimeoutSec 2; if ($taskResponse.StatusCode -eq 200) { $taskReady = $true; break } } catch { }
            if ($taskStarted | Where-Object { $_.HasExited }) { throw 'A dev process exited. Inspect .local/m1 logs.' }
            Start-Sleep -Milliseconds 400
        }
        if (-not $taskReady) { throw "Readiness timed out for $taskUrl. Inspect .local/m1 logs." }
    }
    Write-Output "CourseTrace: http://127.0.0.1:$ClientPort"
    Write-Output "API: http://127.0.0.1:$ServerPort"
    Write-Output 'Stop only these project processes with scripts/stop-dev.ps1.'
} catch {
    foreach ($taskProcess in $taskStarted) { if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -ErrorAction SilentlyContinue } }
    throw
}
exit 0
