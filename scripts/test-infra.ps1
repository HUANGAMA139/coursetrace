param([switch]$CliOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDockerScript = Join-Path $PSScriptRoot 'docker.ps1'
$taskResults = [ordered]@{}
function Invoke-LocalDockerProbe([string[]]$NativeArguments) {
    $taskOutput = & $taskDockerScript @NativeArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Local Docker probe failed: $($taskOutput -join ' ')" }
    return ($taskOutput -join "`n")
}
try {
    $taskSql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names -e "SELECT VERSION(), DATABASE(), 1"'
    $taskSqlResult = Invoke-LocalDockerProbe -NativeArguments @('exec','-T','mysql','sh','-c',$taskSql)
    if ($taskSqlResult -notmatch 'coursetrace\s+1') { throw 'Unexpected MySQL query result.' }
    $taskResults.mysql = @{status='passed';authenticatedQuery=$true;summary=$taskSqlResult}
} catch { $taskResults.mysql = @{status='failed';message=$_.Exception.Message} }
try {
    $taskRedisResult = Invoke-LocalDockerProbe -NativeArguments @('exec','-T','redis','redis-cli','ping')
    if ($taskRedisResult.Trim() -ne 'PONG') { throw 'Redis did not return PONG.' }
    $taskResults.redis = @{status='passed';ping='PONG'}
} catch { $taskResults.redis = @{status='failed';message=$_.Exception.Message} }
$taskTopic = 'coursetrace.m0.' + [Guid]::NewGuid().ToString('N')
$taskCreated = $false
try {
    Invoke-LocalDockerProbe -NativeArguments @('exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:9092','--create','--topic',$taskTopic,'--partitions','1','--replication-factor','1') | Out-Null
    $taskCreated = $true
    $taskProduce = "printf 'CourseTrace-M0\n' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $taskTopic"
    Invoke-LocalDockerProbe -NativeArguments @('exec','-T','kafka','sh','-c',$taskProduce) | Out-Null
    $taskConsumed = Invoke-LocalDockerProbe -NativeArguments @('exec','-T','kafka','/opt/kafka/bin/kafka-console-consumer.sh','--bootstrap-server','localhost:9092','--topic',$taskTopic,'--from-beginning','--max-messages','1','--timeout-ms','15000')
    if ($taskConsumed -notmatch 'CourseTrace-M0') { throw 'Kafka probe message was not received.' }
    $taskResults.kafka = @{status='passed';produceAndConsume=$true;message='CourseTrace-M0'}
} catch { $taskResults.kafka = @{status='failed';message=$_.Exception.Message} }
finally {
    if ($taskCreated) {
        try { Invoke-LocalDockerProbe -NativeArguments @('exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:9092','--delete','--topic',$taskTopic) | Out-Null }
        catch { $taskResults.kafka.cleanupError = $_.Exception.Message }
    }
}
$taskOutputDir = Join-Path $taskRoot '.local\m0'
New-Item -ItemType Directory -Path $taskOutputDir -Force | Out-Null
$taskJson = @{observedAt=[DateTime]::UtcNow.ToString('o');results=$taskResults} | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $taskOutputDir 'infra-cli.json'),$taskJson,[Text.UTF8Encoding]::new($false))
$taskJson
if ($taskResults.Values | Where-Object { $_.status -ne 'passed' }) { exit 1 }
if ($CliOnly) { exit 0 }
& node (Join-Path $PSScriptRoot 'test-infra.mjs')
exit $LASTEXITCODE
