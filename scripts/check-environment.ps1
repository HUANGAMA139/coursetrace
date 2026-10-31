$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskRoot '.local\m0'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskTools = foreach ($taskName in @('git','java','javac','mvn','node','pnpm','npm','docker','wsl','ffmpeg')) {
    $taskCommand = Get-Command $taskName -ErrorAction SilentlyContinue | Select-Object -First 1
    [PSCustomObject]@{ name=$taskName; found=[bool]$taskCommand; path=$taskCommand.Source }
}
$taskPorts = foreach ($taskPort in @(3306,3307,6379,9092,9000,9001,6333)) {
    $taskClient = [Net.Sockets.TcpClient]::new()
    try {
        $taskConnect = $taskClient.ConnectAsync('127.0.0.1',$taskPort)
        $taskReady = $taskConnect.Wait(500)
        [PSCustomObject]@{ host='127.0.0.1'; port=$taskPort; open=($taskReady -and $taskClient.Connected) }
    } catch {
        [PSCustomObject]@{ host='127.0.0.1'; port=$taskPort; open=$false }
    } finally { $taskClient.Dispose() }
}
$taskReport = [ordered]@{
    observedAt=[DateTime]::UtcNow.ToString('o')
    tools=$taskTools
    javaHome=$env:JAVA_HOME
    apiKeyPresent=(-not [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY))
    portableFfmpeg=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\ffmpeg') -Filter ffmpeg.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)
    discoveredDocker=(Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe')
    ports=$taskPorts
    note='Open TCP ports are not authentication or service protocol validation. No credentials are recorded.'
}
$taskJson = $taskReport | ConvertTo-Json -Depth 6
$taskReportPath = Join-Path $taskOutput 'environment.json'
[IO.File]::WriteAllText($taskReportPath,$taskJson,[Text.UTF8Encoding]::new($false))
$taskJson
