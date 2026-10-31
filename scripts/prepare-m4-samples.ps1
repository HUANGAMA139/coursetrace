$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOut=Join-Path $taskRoot '.local/m4'
[IO.Directory]::CreateDirectory($taskOut) | Out-Null
Add-Type -AssemblyName System.Speech
$taskSpeaker=New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $taskVoice=$taskSpeaker.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'zh-CN' } | Select-Object -First 1
    if ($taskVoice) {
        $taskSpeaker.SelectVoice($taskVoice.VoiceInfo.Name)
        $taskText='今天我们学习二分查找。二分查找的前提是数组已经排好序。我们先设置左边界和右边界，然后计算中间位置。如果中间元素等于目标值，就返回这个位置。如果目标值更小，就把右边界移动到中间位置的左边；否则把左边界移动到右边。每次比较都会把搜索范围缩小一半，所以时间复杂度是对数级。需要注意空数组，以及循环结束后仍没有找到目标的情况。'
    } else {
        $taskText='Today we study binary search. The input array must be sorted. Set the left and right boundaries, then find the middle element. If it equals the target, return its position. If the target is smaller, move the right boundary to the left of the middle. Otherwise move the left boundary to the right. Each comparison removes half of the remaining search range, so the time complexity is logarithmic. Remember to handle an empty array and a target that is not present.'
    }
    $taskSpeaker.Rate=-1
    $taskSpeaker.SetOutputToWaveFile((Join-Path $taskOut 'speech.wav'))
    $taskSpeaker.Speak($taskText)
    [IO.File]::WriteAllText((Join-Path $taskOut 'speech-source.txt'),$taskText,[Text.UTF8Encoding]::new($false))
} finally { $taskSpeaker.Dispose() }
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$taskFfprobe=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'
& $taskFfmpeg -hide_banner -loglevel error -y -i (Join-Path $taskOut 'speech.wav') -ac 1 -ar 16000 -sample_fmt s16 -c:a flac (Join-Path $taskOut 'speech.flac')
if($LASTEXITCODE -ne 0) {throw 'Could not prepare FLAC'}
$taskDuration=[double](& $taskFfprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 (Join-Path $taskOut 'speech.wav'))
foreach($taskDelay in @(0,2)) {
    $taskName=if($taskDelay -eq 0) {'sample-speech.mp4'} else {'sample-delayed.mp4'}
    & $taskFfmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=0x254b3b:s=640x360:r=15' -itsoffset $taskDelay -i (Join-Path $taskOut 'speech.wav') -map '0:v:0' -map '1:a:0' -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -t ($taskDuration+$taskDelay+1).ToString([Globalization.CultureInfo]::InvariantCulture) -movflags +faststart (Join-Path $taskOut $taskName)
    if($LASTEXITCODE -ne 0) {throw 'Could not prepare speech video'}
}
Write-Output 'Prepared synthetic spoken lesson and two real AAC/H264 video fixtures.'
