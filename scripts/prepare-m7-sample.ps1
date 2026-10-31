param([string]$OutputDirectory='.local/m7')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskOutput=[IO.Path]::GetFullPath((Join-Path $taskRoot $OutputDirectory))
if(-not $taskOutput.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Output must be inside the project'}
[IO.Directory]::CreateDirectory($taskOutput) | Out-Null
$taskSource=Join-Path $taskRoot 'docs/demo/lesson-source.txt'
$taskHash=(Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash
$taskMetaPath=Join-Path $taskOutput 'sample.json'
$taskVideo=Join-Path $taskOutput 'sample-course.mp4'
if((Test-Path -LiteralPath $taskMetaPath) -and (Test-Path -LiteralPath $taskVideo)){
 $taskPrevious=Get-Content -LiteralPath $taskMetaPath -Raw | ConvertFrom-Json
 if($taskPrevious.sourceSha256 -eq $taskHash){Write-Output 'Existing M7 original synthetic lesson reused.';exit 0}
 throw 'Existing sample uses different source text. Choose another output directory.'
}
Add-Type -AssemblyName System.Speech
$taskSpeaker=New-Object System.Speech.Synthesis.SpeechSynthesizer
try{
 $taskVoice=$taskSpeaker.GetInstalledVoices() | Where-Object {$_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN'} | Select-Object -First 1
 if(-not $taskVoice){throw 'A Chinese Windows speech voice is required to reproduce this fixture'}
 $taskSpeaker.SelectVoice($taskVoice.VoiceInfo.Name)
 $taskSpeaker.Rate=1
 $taskSpeaker.SetOutputToWaveFile((Join-Path $taskOutput 'speech.wav'))
 $taskSpeaker.Speak([IO.File]::ReadAllText($taskSource))
}finally{$taskSpeaker.Dispose()}
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$taskProbe=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'
& $taskFfmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=0x254b3b:s=1280x720:r=15' -i (Join-Path $taskOutput 'speech.wav') -map '0:v:0' -map '1:a:0' -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart $taskVideo
if($LASTEXITCODE -ne 0){throw 'Could not generate the M7 video'}
$taskSeconds=[double](& $taskProbe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $taskVideo)
$taskMeta=@{kind='original-text-and-synthetic-voice';source='docs/demo/lesson-source.txt';sourceSha256=$taskHash;videoSha256=(Get-FileHash -LiteralPath $taskVideo -Algorithm SHA256).Hash;durationSeconds=$taskSeconds;voice=$taskVoice.VoiceInfo.Name;createdAt=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText($taskMetaPath,($taskMeta|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$taskMeta | ConvertTo-Json
