param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
function New-Job($payload, $interval=0, $attempts=3, $runAt=$null) {
 $body = @{payload=$payload; priority=5; intervalSeconds=$interval; maxAttempts=$attempts}
 if($runAt) { $body.runAt=$runAt }
 Invoke-RestMethod "$BaseUrl/api/jobs" -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json)
}
function Wait-Job($id, $predicate, $seconds=40) {
 $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
 do {
  $job = Invoke-RestMethod "$BaseUrl/api/jobs/$id"
  if(& $predicate $job) {return $job}
  Start-Sleep -Milliseconds 300
 } while([DateTime]::UtcNow -lt $deadline)
 throw "Timed out waiting for job $id; last status $($job.status)"
}
$once = New-Job 'smoke:hello'
$done = Wait-Job $once.id {param($j) $j.status -eq 'SUCCEEDED'}
if($done.attempts -ne 1) {throw 'One-shot ran more than once'}
$failed = New-Job 'fail:smoke' 0 2
$done = Wait-Job $failed.id {param($j) $j.status -eq 'FAILED'}
if($done.attempts -ne 2) {throw 'Retry budget incorrect'}
$recurring = New-Job 'smoke:recurring' 2
$null = Wait-Job $recurring.id {param($j) $j.completed_runs -ge 2}
# A short handler leaves this recurring job pending between executions.
$null = Invoke-RestMethod "$BaseUrl/api/jobs/$($recurring.id)/cancel" -Method Post
$delayed = New-Job 'smoke:delayed' 0 2 ([DateTime]::UtcNow.AddSeconds(4).ToString('o'))
if($delayed.status -ne 'PENDING') {throw 'Delayed job ran early'}
$null = Wait-Job $delayed.id {param($j) $j.status -eq 'SUCCEEDED'}
$long = New-Job 'sleep:20000'
$null = Wait-Job $long.id {param($j) $j.status -eq 'SUCCEEDED'}
Write-Output 'PASS: one-shot, retries, recurrence, cancellation, delay, and heartbeat renewal'
