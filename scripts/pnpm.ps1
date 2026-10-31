# Preserve native pnpm flags without PowerShell parameter binding.
$ErrorActionPreference = 'Stop'
$taskPnpmCommand = Get-Command pnpm -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($taskPnpmCommand) {
    & $taskPnpmCommand.Source @args
    exit $LASTEXITCODE
}
# Reuse the installed runtime without changing the user's global PATH.
$taskNodeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node'
$taskNodeExe = Join-Path $taskNodeRoot 'bin\node.exe'
$taskPnpmModule = Join-Path $taskNodeRoot 'node_modules\pnpm\bin\pnpm.mjs'
if (-not (Test-Path -LiteralPath $taskNodeExe) -or -not (Test-Path -LiteralPath $taskPnpmModule)) {
    throw 'pnpm was not found. Install Node.js >=22.12 and pnpm 11.25.0, or restore the existing Codex Node runtime.'
}
$env:PATH = (Split-Path -Parent $taskNodeExe) + [IO.Path]::PathSeparator + $env:PATH
& $taskNodeExe $taskPnpmModule @args
exit $LASTEXITCODE
