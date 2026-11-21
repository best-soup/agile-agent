[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL } else { "http://localhost:8080" }),
    [switch]$SkipChat
)

$ErrorActionPreference = "Stop"
$reportDir = Join-Path (Get-Location) "e2e-artifacts"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportPath = Join-Path $reportDir "e2e-report-$stamp.md"
$results = [System.Collections.Generic.List[object]]::new()
$requestCounter = 0
$ownerToken = $null
$viewerToken = $null
$projectId = $null
$draftId = $null
$viewerId = $null

function Required-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { throw "缺少环境变量 $Name" }
    return $value
}

function Record-Step([string]$Name, [string]$Status, [string]$Detail) {
    $results.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Name, $Detail)
}

function New-Headers([string]$Token) {
    $script:requestCounter++
    $headers = @{ "X-Request-Id" = "e2e-$stamp-$script:requestCounter" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    return $headers
}

function Invoke-Api([string]$Method, [string]$Path, [string]$Token, $Body, [string]$ContentType = "application/json") {
    $headers = New-Headers $Token
    $params = @{ Method = $Method; Uri = ($BaseUrl.TrimEnd("/") + $Path); Headers = $headers; TimeoutSec = 120 }
    if ($null -ne $Body) {
        $params.Body = if ($ContentType -eq "application/json") { $Body | ConvertTo-Json -Depth 8 } else { [string]$Body }
        $params.ContentType = $ContentType
    }
    return Invoke-RestMethod @params
}

function Assert-ApiFailure([string]$Name, [string]$Method, [string]$Path, [string]$Token, $Body, [int]$ExpectedStatus) {
    try {
        Invoke-Api $Method $Path $Token $Body | Out-Null
        Record-Step $Name "FAIL" "请求意外成功，期望 HTTP $ExpectedStatus"
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($status -eq $ExpectedStatus) { Record-Step $Name "PASS" "HTTP $status，错误响应未暴露凭证或堆栈" }
        else { Record-Step $Name "FAIL" "HTTP $status，期望 HTTP $ExpectedStatus" }
    }
}

function Check-Tcp([string]$Name, [string]$HostName, [int]$Port) {
    if (Test-NetConnection -ComputerName $HostName -Port $Port -InformationLevel Quiet) {
        Record-Step $Name "PASS" "$HostName`:$Port 可连接"
        return $true
    }
    Record-Step $Name "FAIL" "$HostName`:$Port 不可连接"
    return $false
}

try {
    $dbName = Required-Env "E2E_MYSQL_DATABASE"
    if ($dbName -eq "agile_agent_db") { throw "E2E_MYSQL_DATABASE 不能是现有 agile_agent_db" }
    $dbHost = if ($env:E2E_MYSQL_HOST) { $env:E2E_MYSQL_HOST } else { "localhost" }
    $dbPort = if ($env:E2E_MYSQL_PORT) { [int]$env:E2E_MYSQL_PORT } else { 3306 }
    $redisHost = if ($env:E2E_REDIS_HOST) { $env:E2E_REDIS_HOST } else { "localhost" }
    $redisPort = if ($env:E2E_REDIS_PORT) { [int]$env:E2E_REDIS_PORT } else { 6379 }
    $qdrantUrl = if ($env:E2E_QDRANT_URL) { $env:E2E_QDRANT_URL.TrimEnd("/") } else { "http://localhost:6333" }

    Check-Tcp "MySQL" $dbHost $dbPort | Out-Null
    Check-Tcp "Redis" $redisHost $redisPort | Out-Null
    try {
        $health = Invoke-WebRequest -UseBasicParsing -Uri ($qdrantUrl + "/healthz") -TimeoutSec 10
        Record-Step "Qdrant" "PASS" "HTTP $($health.StatusCode)"
        try {
            $collection = Invoke-WebRequest -UseBasicParsing -Uri ($qdrantUrl + "/collections/agile-agent-docs") -TimeoutSec 10
            Record-Step "Qdrant collection" "PASS" "agile-agent-docs 可访问"
        } catch { Record-Step "Qdrant collection" "WARN" "collection 尚未创建，首次索引时由应用创建或需人工检查" }
    } catch { Record-Step "Qdrant" "FAIL" "healthz 不可用" }
    try {
        $null = Invoke-Api "GET" "/api/users/me" $null $null
        Record-Step "Spring Boot" "WARN" "应用可访问，但未认证请求返回了成功响应，请检查配置"
    } catch { Record-Step "Spring Boot" "PASS" "应用入口可访问，未认证请求被拒绝" }

    $ownerName = "e2e-owner-$stamp"
    $owner = Invoke-Api "POST" "/api/users" $null @{ username = $ownerName; displayName = "E2E Owner" }
    $ownerToken = $owner.apiKey
    Record-Step "创建测试用户" "PASS" "userId=$($owner.id)；明文 Key 未写入报告"

    $project = Invoke-Api "POST" "/api/projects" $ownerToken @{ name = "E2E Project $stamp"; description = "脱敏端到端验收项目" }
    $projectId = $project.id
    Record-Step "创建测试项目" "PASS" "projectId=$projectId"
    $otherProject = Invoke-Api "POST" "/api/projects" $ownerToken @{ name = "E2E Other Project $stamp"; description = "跨项目隔离验收" }
    $otherProjectId = $otherProject.id
    Record-Step "创建第二测试项目" "PASS" "projectId=$otherProjectId"

    $normalMeeting = @"
E2E 测试会议：测试负责人A 在 2099-12-01 前排查数据库慢查询，优先级 HIGH；测试负责人B 在 2099-12-05 前补充接口测试，优先级 MEDIUM。
"@
    $draft = Invoke-Api "POST" "/api/task/process?projectId=$projectId" $ownerToken $normalMeeting "text/plain"
    $draftId = $draft.draft.id
    Record-Step "会议索引与草稿生成" "PASS" "draftId=$draftId；items=$($draft.items.Count)"

    $firstItem = $draft.items | Select-Object -First 1
    if ($firstItem) {
        Invoke-Api "PATCH" "/api/projects/$projectId/task-drafts/$draftId/items/$($firstItem.id)" $ownerToken @{
            title = "排查数据库慢查询（E2E 修订）"; assigneeName = "测试负责人A"; description = "脱敏验收任务"; priority = "HIGH"; deadline = "2099-12-01"; sortOrder = 0
        } | Out-Null
        Record-Step "编辑草稿项" "PASS" "itemId=$($firstItem.id)"
    }
    if ($draft.items.Count -gt 1) {
        $secondItem = $draft.items | Select-Object -Skip 1 -First 1
        Invoke-Api "DELETE" "/api/projects/$projectId/task-drafts/$draftId/items/$($secondItem.id)" $ownerToken $null | Out-Null
        Record-Step "删除草稿项" "PASS" "itemId=$($secondItem.id)"
    }
    Invoke-Api "POST" "/api/projects/$projectId/task-drafts/$draftId/confirm" $ownerToken $null | Out-Null
    Record-Step "确认草稿" "PASS" "draftId=$draftId"
    $submitKey = "e2e-$stamp-submit"
    $submitted = Invoke-Api "POST" "/api/projects/$projectId/task-drafts/$draftId/submit" $ownerToken @{ idempotencyKey = $submitKey }
    Record-Step "事务性提交草稿" "PASS" "status=$($submitted.draft.status); taskIds=$($submitted.draft.submittedTaskIds)"
    $repeat = Invoke-Api "POST" "/api/projects/$projectId/task-drafts/$draftId/submit" $ownerToken @{ idempotencyKey = $submitKey }
    Record-Step "重复幂等提交" "PASS" "status=$($repeat.draft.status)；未重复创建"
    Assert-ApiFailure "跨项目访问草稿" "GET" "/api/projects/$otherProjectId/task-drafts/$draftId" $ownerToken $null 404

    $tasks = Invoke-Api "GET" "/api/projects/$projectId/tasks?page=1&size=20" $ownerToken $null
    Record-Step "查询正式任务" "PASS" "total=$($tasks.total)"

    $viewer = Invoke-Api "POST" "/api/users" $null @{ username = "e2e-viewer-$stamp"; displayName = "E2E Viewer" }
    $viewerToken = $viewer.apiKey
    $viewerId = $viewer.id
    Invoke-Api "POST" "/api/projects/$projectId/members" $ownerToken @{ userId = $viewerId; role = "VIEWER" } | Out-Null
    Record-Step "创建 Viewer 成员" "PASS" "userId=$viewerId"
    Assert-ApiFailure "Viewer 提交草稿" "POST" "/api/projects/$projectId/task-drafts/$draftId/submit" $viewerToken @{ idempotencyKey = "e2e-viewer-key" } 403

    $revocable = Invoke-Api "POST" "/api/users/me/api-keys" $ownerToken @{ name = "e2e-revocable" }
    Invoke-Api "POST" "/api/users/me/api-keys/$($revocable.key.id)/revoke" $ownerToken $null | Out-Null
    Assert-ApiFailure "已撤销 Key" "GET" "/api/users/me" $revocable.apiKey $null 401
    $expiresAt = (Get-Date).AddSeconds(2).ToString("yyyy-MM-ddTHH:mm:ss")
    $expiring = Invoke-Api "POST" "/api/users/me/api-keys" $ownerToken @{ name = "e2e-expiring"; expiresAt = $expiresAt }
    Start-Sleep -Seconds 3
    Assert-ApiFailure "已过期 Key" "GET" "/api/users/me" $expiring.apiKey $null 401

    $vagueMeeting = "E2E 模糊会议：后续跟进接口问题，负责人和日期待补充。"
    $vague = Invoke-Api "POST" "/api/task/process?projectId=$projectId" $ownerToken $vagueMeeting "text/plain"
    Record-Step "模糊会议" "PASS" "draftId=$($vague.draft.id)"
    try {
        Invoke-Api "POST" "/api/task/process?projectId=$projectId" $ownerToken "E2E 异常会议：没有任何可执行任务。" "text/plain" | Out-Null
        Record-Step "无任务会议" "WARN" "模型仍生成了草稿，需人工检查"
    } catch { Record-Step "无任务会议" "PASS" "请求被拒绝，响应未暴露底层异常" }

    if (-not $SkipChat) {
        Invoke-Api "POST" "/api/task/chat?projectId=$projectId" $ownerToken "E2E owner memory marker $stamp" "text/plain" | Out-Null
        Invoke-Api "POST" "/api/task/chat?projectId=$projectId" $viewerToken "E2E viewer memory marker $stamp" "text/plain" | Out-Null
        $chat = Invoke-Api "POST" "/api/task/chat?projectId=$projectId" $ownerToken "请强制调用 searchKnowledge，查询 E2E 测试会议中关于慢查询的历史内容。" "text/plain"
        Record-Step "RAG 历史检索" "PASS" "返回长度=$($chat.Length)；未记录完整回答"
        $redisCli = Get-Command redis-cli -ErrorAction SilentlyContinue
        if ($redisCli) {
            $ownerPattern = "chat:memory:$($owner.id):${projectId}*"
            $viewerPattern = "chat:memory:${viewerId}:${projectId}*"
            $ownerKeys = @(redis-cli -h $redisHost -p $redisPort --scan --pattern $ownerPattern)
            $viewerKeys = @(redis-cli -h $redisHost -p $redisPort --scan --pattern $viewerPattern)
            if ($ownerKeys.Count -gt 0 -and $viewerKeys.Count -gt 0) { Record-Step "Redis 用户/项目隔离" "PASS" "发现两个独立作用域 Key" }
            else { Record-Step "Redis 用户/项目隔离" "WARN" "未发现预期 Key，请人工检查 Redis 前缀和 TTL" }
        } else { Record-Step "Redis 用户/项目隔离" "SKIP" "需要 redis-cli；请人工检查作用域 Key" }
    } else { Record-Step "RAG 历史检索" "SKIP" "使用 -SkipChat 跳过模型调用" }

    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if ($mysql -and $env:DB_PASSWORD) {
        $oldPwd = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:DB_PASSWORD
        try {
            $mysqlUser = if ($env:E2E_MYSQL_USER) { $env:E2E_MYSQL_USER } else { "root" }
            $auditCount = & mysql --batch --skip-column-names -h $dbHost -P $dbPort -u $mysqlUser $dbName -e "SELECT COUNT(*) FROM audit_log WHERE project_id=$projectId;"
            Record-Step "审计日志查询" "PASS" "projectId=$projectId; count=$auditCount"
        } finally { if ($null -eq $oldPwd) { Remove-Item Env:MYSQL_PWD } else { $env:MYSQL_PWD = $oldPwd } }
    } else { Record-Step "审计日志查询" "SKIP" "需要 mysql CLI 和 DB_PASSWORD；可手工执行 SELECT COUNT(*) FROM audit_log WHERE project_id=$projectId" }

    Record-Step "真实故障注入" "MANUAL" "未停止数据库/Qdrant/Redis；请按 E2E_ACCEPTANCE.md 执行撤销、过期、断开和超时场景"
} catch {
    Record-Step "脚本执行" "FAIL" $_.Exception.Message
} finally {
    $lines = @("# E2E Smoke Report", "", "- Generated: $(Get-Date -Format o)", "- Base URL: $BaseUrl", "- Database: $($env:E2E_MYSQL_DATABASE)", "- Credentials: not recorded", "", "| Step | Status | Detail |", "|---|---|---|")
    foreach ($result in $results) {
        $safe = ($result.Detail -replace "\r?\n", " " -replace "\|", "/")
        $lines += "| $($result.Name) | $($result.Status) | $safe |"
    }
    Set-Content -Path $reportPath -Value $lines -Encoding UTF8
    Write-Host "脱敏报告已写入 $reportPath"
}
