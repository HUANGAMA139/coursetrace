param(
 [ValidateSet('Build','Flow','Fault','Regression','Refine','Demo','Report')][string]$Mode='Build',
 [ValidatePattern('^[a-zA-Z0-9-]{1,48}$')][string]$RunId='acceptance'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskOutput=Join-Path $taskRoot ('.local/m7/'+$RunId)
[IO.Directory]::CreateDirectory($taskOutput)|Out-Null
$env:COURSETRACE_M7_RUN_ID=$RunId
$taskPowerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$taskNodeCommand=Get-Command node -CommandType Application -ErrorAction SilentlyContinue|Select-Object -First 1
$taskNode=if($taskNodeCommand){$taskNodeCommand.Source}else{Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'}
if(-not(Test-Path -LiteralPath $taskNode)){throw 'Node.js is required. Install Node >=22.12 or restore the configured runtime.'}
function Invoke-LoggedStep([string]$Label,[string]$Executable,[string[]]$NativeArguments){
 $taskLog=Join-Path $taskOutput ($Label+'.log')
 # Windows PowerShell 5.1 surfaces native stderr as error records. Tools can
 # emit warnings on stderr and still succeed; their exit code is authoritative.
 $taskSavedErrorAction=$ErrorActionPreference
 try {
  $ErrorActionPreference='Continue'
  & $Executable @NativeArguments *> $taskLog
  $taskExit=$LASTEXITCODE
 } finally { $ErrorActionPreference=$taskSavedErrorAction }
 Get-Content -LiteralPath $taskLog -Tail 8
 if($taskExit -ne 0){throw ('Step failed: '+$Label+'. See '+$taskLog)}
}
switch($Mode){
 'Build' {
  $taskOriginalLive=$env:COURSETRACE_LIVE_AI;$env:COURSETRACE_LIVE_AI='false'
  try{
   Invoke-LoggedStep 'backend-tests' $taskPowerShell @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'maven.ps1'),'test')
   Invoke-LoggedStep 'frontend-build' $taskPowerShell @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'pnpm.ps1'),'--dir','client','build')
   $taskTests=@{tests=0;failures=0;errors=0;skipped=0}
   foreach($taskFile in Get-ChildItem (Join-Path $taskRoot 'server/target/surefire-reports') -Filter 'TEST-*.xml'){
    [xml]$taskSuite=Get-Content -LiteralPath $taskFile.FullName -Raw
    foreach($taskField in @('tests','failures','errors','skipped')){$taskTests[$taskField]+=[int]$taskSuite.testsuite.GetAttribute($taskField)}
   }
   foreach($taskFile in Get-ChildItem $PSScriptRoot -Filter '*m7*.mjs'){
    & $taskNode --check $taskFile.FullName
    if($LASTEXITCODE -ne 0){throw ('JavaScript syntax failed: '+$taskFile.Name)}
   }
   $taskReport=@{runId=$RunId;observedAt=[DateTime]::UtcNow.ToString('o');status='passed';backend=$taskTests;paidAiOptIn=$false;frontend='passed';mode='Build';sideEffects='Build artifacts only; no container changes or new paid requests'}
   [IO.File]::WriteAllText((Join-Path $taskOutput 'build-verification.json'),($taskReport|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
   $taskReport|ConvertTo-Json -Depth 6
  }finally{$env:COURSETRACE_LIVE_AI=$taskOriginalLive}
 }
 'Flow' {
  Invoke-LoggedStep 'sample' $taskPowerShell @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'prepare-m7-sample.ps1'))
  Invoke-LoggedStep 'flow' $taskNode @((Join-Path $PSScriptRoot 'test-m7-browser.mjs'))
 }
 'Fault' {
  Invoke-LoggedStep 'regression-m3' $taskNode @((Join-Path $PSScriptRoot 'test-m3-browser.mjs'))
  Copy-Item -LiteralPath (Join-Path $taskRoot '.local/m3/browser-verification.json') -Destination (Join-Path $taskOutput 'regression-m3.json')
  Invoke-LoggedStep 'fault-recovery' $taskNode @((Join-Path $PSScriptRoot 'test-m7-recovery.mjs'))
 }
 'Regression' {
  foreach($taskMilestone in @(1,2,4,5,6)){
   Invoke-LoggedStep ('regression-m'+$taskMilestone) $taskNode @((Join-Path $PSScriptRoot ('test-m'+$taskMilestone+'-browser.mjs')))
   Copy-Item -LiteralPath (Join-Path $taskRoot ('.local/m'+$taskMilestone+'/browser-verification.json')) -Destination (Join-Path $taskOutput ('regression-m'+$taskMilestone+'.json'))
  }
  foreach($taskEntry in @(@{Script='test-frontend-review.mjs';Name='frontend-review';Report='.local/frontend-review/verification.json'},@{Script='test-study-layout.mjs';Name='study-layout';Report='.local/study-layout/verification.json'})){
   Invoke-LoggedStep $taskEntry.Name $taskNode @((Join-Path $PSScriptRoot $taskEntry.Script))
   Copy-Item -LiteralPath (Join-Path $taskRoot $taskEntry.Report) -Destination (Join-Path $taskOutput ($taskEntry.Name+'.json'))
  }
 }
 'Refine' {Invoke-LoggedStep 'refinement' $taskNode @((Join-Path $PSScriptRoot 'test-m7-refinement.mjs'))}
 'Demo' {
  Invoke-LoggedStep 'demo' $taskNode @((Join-Path $PSScriptRoot 'record-m7-demo.mjs'))
  Invoke-LoggedStep 'recording-render' $taskPowerShell @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'render-m7-recordings.ps1'),'-RunId',$RunId)
  Invoke-LoggedStep 'media' $taskPowerShell @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'verify-m7-media.ps1'),'-RunId',$RunId)
 }
 'Report' {Invoke-LoggedStep 'report' $taskNode @((Join-Path $PSScriptRoot 'report-m7.mjs'))}
}
exit 0
