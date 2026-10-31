param([ValidatePattern('^[a-zA-Z0-9-]{1,48}$')][string]$RunId=$env:COURSETRACE_M7_RUN_ID)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOutput=Join-Path $taskRoot ('.local/m7/'+$RunId)
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$taskProbe=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'
$taskDemo=Get-Content -LiteralPath (Join-Path $taskOutput 'demo-verification.json') -Raw -Encoding UTF8|ConvertFrom-Json
if($taskDemo.status -ne 'passed'){throw 'A passed real browser demo is required'}
$taskChapters=Get-Content -LiteralPath (Join-Path $taskRoot 'docs/demo/narration.json') -Raw -Encoding UTF8|ConvertFrom-Json
Add-Type -AssemblyName System.Speech
$taskSpeaker=New-Object System.Speech.Synthesis.SpeechSynthesizer
$taskConcat=@();$taskIndex=0
try{
 $taskVoice=$taskSpeaker.GetInstalledVoices()|Where-Object {$_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN'}|Select-Object -First 1
 if(-not $taskVoice){throw 'Chinese narration voice unavailable'}
 $taskSpeaker.SelectVoice($taskVoice.VoiceInfo.Name);$taskSpeaker.Rate=1
 foreach($taskChapter in $taskChapters){
  $taskChapterSeconds=[double]$taskDemo.chapters[$taskIndex].actualSeconds
  $taskRaw=Join-Path $taskOutput ('narration-'+$taskIndex+'.wav')
  $taskClip=Join-Path $taskOutput ('chapter-'+$taskIndex+'.wav')
  $taskSpeaker.SetOutputToWaveFile($taskRaw);$taskSpeaker.Speak([string]$taskChapter.text);$taskSpeaker.SetOutputToNull()
  $taskDuration=[double](& $taskProbe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $taskRaw)
  if($taskDuration -gt [double]$taskChapterSeconds){throw ('Narration too long for chapter '+$taskIndex)}
  & $taskFfmpeg -hide_banner -loglevel error -y -i $taskRaw -af apad -t $taskChapterSeconds.ToString([Globalization.CultureInfo]::InvariantCulture) -ar 48000 -ac 1 $taskClip
  if($LASTEXITCODE -ne 0){throw 'Narration preparation failed'}
  $taskConcat+="file 'chapter-$taskIndex.wav'";$taskIndex++
 }
}finally{$taskSpeaker.Dispose()}
$taskList=Join-Path $taskOutput 'narration-list.txt'
[IO.File]::WriteAllText($taskList,($taskConcat -join [char]10),[Text.UTF8Encoding]::new($false))
$taskMp4=Join-Path $taskOutput 'coursetrace-demo.mp4'
& $taskFfmpeg -hide_banner -loglevel error -y -i $taskDemo.webm -f concat -safe 0 -i $taskList -map 0:v:0 -map 1:a:0 -af apad -c:v libx264 -preset veryfast -crf 24 -c:a aac -movflags +faststart -shortest $taskMp4
if($LASTEXITCODE -ne 0){throw 'Demo MP4 rendering failed'}
$taskFlow=Get-Content -LiteralPath (Join-Path $taskOutput 'flow-verification.json') -Raw -Encoding UTF8|ConvertFrom-Json
$taskFlowMp4=Join-Path $taskOutput 'coursetrace-full-flow.mp4'
& $taskFfmpeg -hide_banner -loglevel error -y -i $taskFlow.recordingPath -c:v libx264 -preset veryfast -crf 24 -an -movflags +faststart $taskFlowMp4
if($LASTEXITCODE -ne 0){throw 'Flow MP4 rendering failed'}
$taskReport=@{runId=$RunId;status='passed';observedAt=[DateTime]::UtcNow.ToString('o');demo=$taskMp4;fullFlow=$taskFlowMp4;narration='Windows Chinese synthetic voice, seven aligned chapters';voice=$taskVoice.VoiceInfo.Name;flowAudio='silent browser recording, actual waits retained'}
[IO.File]::WriteAllText((Join-Path $taskOutput 'recordings.json'),($taskReport|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$taskReport|ConvertTo-Json
