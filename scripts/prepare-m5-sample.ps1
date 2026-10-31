$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOut=Join-Path $taskRoot '.local/m5'
[IO.Directory]::CreateDirectory($taskOut) | Out-Null
if(Test-Path -LiteralPath (Join-Path $taskOut 'sample-multitopic.mp4')) {Write-Output 'Existing M5 synthetic lesson reused.';exit 0}
Add-Type -AssemblyName System.Speech
$taskSpeaker=New-Object System.Speech.Synthesis.SpeechSynthesizer
try{
 $taskVoice=$taskSpeaker.GetInstalledVoices() | Where-Object {$_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN'} | Select-Object -First 1
 if(-not $taskVoice){throw 'Chinese speech voice is unavailable.'}
 $taskSpeaker.SelectVoice($taskVoice.VoiceInfo.Name)
 $taskSpeaker.Rate=0
 $taskSpeaker.SetOutputToWaveFile((Join-Path $taskOut 'speech-multitopic.wav'))
 $taskSpeaker.Speak([IO.File]::ReadAllText((Join-Path $taskOut 'speech-source.txt')))
}finally{$taskSpeaker.Dispose()}
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
& $taskFfmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=0x254b3b:s=640x360:r=10' -i (Join-Path $taskOut 'speech-multitopic.wav') -map '0:v:0' -map '1:a:0' -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart (Join-Path $taskOut 'sample-multitopic.mp4')
if($LASTEXITCODE -ne 0){throw 'Could not create M5 spoken video'}
Write-Output 'Prepared original synthetic speech lesson; not a human recording.'
