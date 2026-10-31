param([ValidatePattern('^[a-zA-Z0-9-]{1,48}$')][string]$RunId=$env:COURSETRACE_M7_RUN_ID)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOutput=Join-Path $taskRoot ('.local/m7/'+$RunId)
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$taskHashes=@{}
foreach($taskName in @('coursetrace-demo.mp4','coursetrace-full-flow.mp4')){
 $taskPath=Join-Path $taskOutput $taskName
 & $taskFfmpeg -hide_banner -loglevel error -i $taskPath -f null - *> (Join-Path $taskOutput ($taskName+'.decode.log'))
 if($LASTEXITCODE -ne 0){throw ('Decode failed: '+$taskName)}
 $taskHashes[$taskName]=(Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
& $taskFfmpeg -hide_banner -i (Join-Path $taskOutput 'coursetrace-demo.mp4') -af volumedetect -vn -f null - *> (Join-Path $taskOutput 'narration-volume.log')
if($LASTEXITCODE -ne 0){throw 'Narration decode failed'}
$taskVolume=Get-Content -LiteralPath (Join-Path $taskOutput 'narration-volume.log') -Raw
$taskPeak=[regex]::Match($taskVolume,'max_volume: (-?[0-9.]+) dB')
if(-not $taskPeak.Success){throw 'Narration is missing or silent'}
$taskDb=[double]::Parse($taskPeak.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)
if($taskDb -lt -60){throw 'Narration is effectively silent'}
$taskReport=@{status='passed';observedAt=[DateTime]::UtcNow.ToString('o');fullDecode=@('coursetrace-demo.mp4','coursetrace-full-flow.mp4');sha256=$taskHashes;maxVolumeDb=$taskDb}
[IO.File]::WriteAllText((Join-Path $taskOutput 'media-verification.json'),($taskReport|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$taskReport|ConvertTo-Json -Depth 5