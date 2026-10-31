$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
$taskDestination = Join-Path $taskTools 'ffmpeg'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
$taskExisting = Get-ChildItem -LiteralPath $taskDestination -Filter ffmpeg.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($taskExisting) {
    $taskVersion = & $taskExisting.FullName -version
    $taskExit = $LASTEXITCODE
    $taskVersion | Select-Object -First 1
    exit $taskExit
}
$taskZip = Join-Path $taskTools 'ffmpeg-release-essentials.zip'
$taskHashFile = $taskZip + '.sha256'
Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256' -OutFile $taskHashFile -TimeoutSec 45
Invoke-WebRequest 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip' -OutFile $taskZip -TimeoutSec 240
$taskHashMatch = [regex]::Match((Get-Content -LiteralPath $taskHashFile -Raw), '\b[0-9a-fA-F]{64}\b')
if (-not $taskHashMatch.Success -or (Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne $taskHashMatch.Value) {
    throw 'FFmpeg archive checksum verification failed.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskResolvedDestination = [IO.Path]::GetFullPath($taskDestination) + [IO.Path]::DirectorySeparatorChar
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskZip)
try {
    foreach ($taskEntry in $taskArchive.Entries) {
        $taskEntryTarget = [IO.Path]::GetFullPath([IO.Path]::Combine($taskResolvedDestination, $taskEntry.FullName))
        if (-not $taskEntryTarget.StartsWith($taskResolvedDestination, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive entry is outside the project tool directory.' }
    }
} finally { $taskArchive.Dispose() }
Expand-Archive -LiteralPath $taskZip -DestinationPath $taskDestination -Force
$taskBinary = Get-ChildItem -LiteralPath $taskDestination -Filter ffmpeg.exe -Recurse | Select-Object -First 1
if (-not $taskBinary) { throw 'FFmpeg binary was not found after extraction.' }
$taskVersion = & $taskBinary.FullName -version
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg executable validation failed.' }
$taskVersion | Select-Object -First 1
Write-Output "Portable installation: $($taskBinary.DirectoryName)"
