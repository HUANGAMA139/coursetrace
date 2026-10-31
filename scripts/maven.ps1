# Forward all Maven flags unchanged.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJavaHome = if ($env:COURSETRACE_JAVA_HOME) { $env:COURSETRACE_JAVA_HOME } else { $env:JAVA_HOME }
if (-not $taskJavaHome -or -not (Test-Path -LiteralPath (Join-Path $taskJavaHome 'bin\javac.exe'))) {
    throw 'Set COURSETRACE_JAVA_HOME or JAVA_HOME to a JDK directory.'
}
$env:JAVA_HOME = $taskJavaHome
$env:Path = (Join-Path $taskJavaHome 'bin') + [IO.Path]::PathSeparator + $env:Path
$taskCache = Join-Path $taskRoot '.tools\m2'
& mvn '-B' '-ntp' '-f' (Join-Path $taskRoot 'server\pom.xml') "-Dmaven.repo.local=$taskCache" @args
exit $LASTEXITCODE
