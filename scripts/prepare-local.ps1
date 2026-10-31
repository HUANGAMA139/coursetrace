$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskConfigPath = Join-Path $taskRoot '.env.local'
$taskContent = if (Test-Path -LiteralPath $taskConfigPath) { [IO.File]::ReadAllText($taskConfigPath) } else { "# Local development credentials; ignored by Git.`n" }
foreach ($taskName in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','MINIO_ROOT_PASSWORD')) {
    if ($taskContent -notmatch "(?m)^$taskName=.+$") {
        if ($taskContent -match "(?m)^$taskName=\s*$") { throw "Fill or remove the empty $taskName setting in .env.local." }
        $taskPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        $taskContent += "$taskName=$taskPassword`n"
    }
}
[IO.File]::WriteAllText($taskConfigPath,$taskContent,[Text.UTF8Encoding]::new($false))
Write-Output 'Local credentials are configured in ignored .env.local; values are not displayed.'
