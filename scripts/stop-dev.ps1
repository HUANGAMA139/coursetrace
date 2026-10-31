$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskStatePath = Join-Path $taskRoot '.local\m1\dev-processes.json'
if (-not (Test-Path -LiteralPath $taskStatePath)) { Write-Output 'No owned development processes recorded.'; exit 0 }
$taskState = Get-Content -LiteralPath $taskStatePath -Raw | ConvertFrom-Json
foreach ($taskEntry in $taskState.processes) {
    $taskProcess = Get-Process -Id $taskEntry.id -ErrorAction SilentlyContinue
    if (-not $taskProcess) { continue }
    # Preserve UTC when PowerShell automatically deserializes ISO JSON dates into DateTime.
    $taskExpected = if ($taskEntry.startedAt -is [DateTime]) {
        $taskEntry.startedAt.ToUniversalTime()
    } else {
        [DateTimeOffset]::Parse([string]$taskEntry.startedAt,[Globalization.CultureInfo]::InvariantCulture).UtcDateTime
    }
    if ([Math]::Abs(($taskProcess.StartTime.ToUniversalTime() - $taskExpected).TotalSeconds) -gt 1) {
        Write-Output "Skipped reused process ID $($taskEntry.id)."
        continue
    }
    Stop-Process -Id $taskProcess.Id
    Write-Output "Stopped project process $($taskEntry.id)."
}
exit 0
