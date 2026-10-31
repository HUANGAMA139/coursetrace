$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskOutput=Join-Path $taskRoot '.local/m2'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskFfmpeg=Join-Path $taskRoot '.tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
if(-not (Test-Path -LiteralPath $taskFfmpeg)){throw 'Run scripts/install-ffmpeg.ps1 first.'}
& $taskFfmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=960x540:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 20 -c:v libx264 -pix_fmt yuv420p -preset veryfast -crf 27 -c:a aac -b:a 96k -movflags +faststart -y (Join-Path $taskOutput 'sample-course.mp4')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $taskFfmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x234d3d:size=640x360:rate=24' -t 12 -c:v libx264 -pix_fmt yuv420p -movflags +faststart -y (Join-Path $taskOutput 'sample-silent.mp4')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $taskFfmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=12' -t 2 -c:v mpeg4 -y (Join-Path $taskOutput 'unsupported-codec.mp4')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Write-Output 'Created controlled MP4 verification samples in .local/m2. These are not course lectures.'
exit 0
