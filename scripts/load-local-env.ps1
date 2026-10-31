# Dot-source this helper. Values remain in the current process and are never printed.
$taskConfigPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env.local'
if (Test-Path -LiteralPath $taskConfigPath) {
    foreach ($taskConfigLine in [IO.File]::ReadAllLines($taskConfigPath)) {
        if ($taskConfigLine -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
            $taskConfigName = $Matches[1]
            $taskConfigValue = $Matches[2]
            if ($taskConfigValue -match '^(["''])(.*)\1$') { $taskConfigValue = $Matches[2] }
            if ($taskConfigValue -and -not [Environment]::GetEnvironmentVariable($taskConfigName)) {
                [Environment]::SetEnvironmentVariable($taskConfigName,$taskConfigValue,'Process')
            }
        }
    }
}
