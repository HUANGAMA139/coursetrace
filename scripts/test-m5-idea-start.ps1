$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskOutput=Join-Path $taskRoot '.local/m5'
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskOriginalPath=$env:PATH
try {
    # Reproduce a regular IDEA terminal, whose PATH has no Node/pnpm entry.
    $env:PATH=(($env:PATH -split ';' | Where-Object { $_ -notmatch '(?i)codex.*dependencies[\\/]node[\\/]bin|[\\/]nodejs([\\/]|$)' }) -join ';')
    $taskNodeOnPath=[bool](Get-Command node -CommandType Application -ErrorAction SilentlyContinue)
    $taskPowerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $taskScript=Join-Path $PSScriptRoot 'idea-start.ps1'
    $taskProcess=Start-Process -FilePath $taskPowerShell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $taskScript + '"'),'-NoBrowser') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskOutput 'idea-start-final.log') -RedirectStandardError (Join-Path $taskOutput 'idea-start-final-error.log')
    $taskDeadline=[DateTime]::UtcNow.AddSeconds(90)
    while (-not $taskProcess.HasExited -and [DateTime]::UtcNow -lt $taskDeadline) { Start-Sleep -Milliseconds 400; $taskProcess.Refresh() }
    if (-not $taskProcess.HasExited) { throw 'IDEA Start did not finish within 90 seconds. Inspect .local/m5 logs.' }
    $taskProcess.WaitForExit()
    if ($taskProcess.ExitCode -ne 0) { Get-Content (Join-Path $taskOutput 'idea-start-final-error.log') -Tail 12; throw "IDEA Start exited $($taskProcess.ExitCode)" }
    $taskHealth=Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5
    $taskWeb=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:5173' -TimeoutSec 5
    if ($taskHealth.status -ne 'UP' -or $taskWeb.StatusCode -ne 200) { throw 'Development endpoints are not ready.' }
    $taskQdrant=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:6333/readyz' -TimeoutSec 5
    $taskReport=@{qdrant=$taskQdrant.StatusCode;observedAt=[DateTime]::UtcNow.ToString('o');status='passed';interpreter='Windows PowerShell';nodeOnPath=$taskNodeOnPath;launcherExited=$taskProcess.HasExited;launcherExitCode=$taskProcess.ExitCode;backend=$taskHealth.status;frontend=$taskWeb.StatusCode}
    [IO.File]::WriteAllText((Join-Path $taskOutput 'idea-start-verification.json'),($taskReport | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $taskReport | ConvertTo-Json
} finally { $env:PATH=$taskOriginalPath }
