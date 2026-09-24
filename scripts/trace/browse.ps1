<#
.SYNOPSIS
  把一条命令发给本地浏览器服务,并把**发出去的请求**与**收回来的响应**都留档到本地。
  顺带把几件每次都要手写的小事也包进来:换端口(-Port)、只取业务结果(-Json / -Index)、
  探活(-Health)、复看上一次(-Last)。

.DESCRIPTION
  服务端自己在 logs/trace/ 下也留了一份审计(见 CommandTraceLog),这个脚本是客户端这一侧的另一半:
  它记录「我到底发了什么」(发送前就落盘,连没连上服务都能看出来),并给每次调用编一个可读的序号,
  便于按顺序复看整个过程。

  每个会话一个目录 logs/agent/<会话名>/:
    NNN.req.json    第 NNN 次调用发出去的请求体(原文)
    NNN.res.json    第 NNN 次调用收回来的响应体(原文,含整页 data.text)
    steps.log       每次调用一行:时间/序号/任务/方法/成败/耗时/关键字段

  两种「不发命令」的用法:
    -Health  只 GET 一次健康检查地址(/playwright/health),报告 ok/失败后退出,用来判断服务起没起来;
    -Last    不重发,直接把该会话最新一份 NNN.res.json 重新打出来(同样认 -Json / -Index)。
  这两种用法都不会新建会话目录、不会写 .current、不会写新的 NNN 文件。

  结果取值口径(给 -Json / -Index 用,免得每个调用方各写一遍 ConvertFrom-Json):
    单条命令      data.result,没有 result 就退到 data;
    commands 批次 data.results[i].data.result,同样没有 result 就退到那一条的 data。

.PARAMETER PayloadFile
  请求体 JSON 文件的路径(UTF-8)。用文件而不是命令行参数,是为了中文与引号不用层层转义。
  例:{"id":1001,"method":"go_to_url","params":{"url":"https://example.com"}}

.PARAMETER PayloadJson
  直接用一段 JSON 字符串当请求体(简单调用时方便,复杂的还是用 -PayloadFile)。

.PARAMETER Session
  会话目录名;默认沿用 logs/agent/.current 里记着的那一个,没有就按时间戳新建。

.PARAMETER NewSession
  强制新开一个会话目录。

.PARAMETER Compact
  只在屏幕上打印摘要(时间线那一行 + 关键字段),不打印完整响应体。默认打印完整响应 JSON —— 因为
  data.text 这类内容正是调用方要读的。

.PARAMETER Json
  只打印**命令结果**本身(漂亮 JSON),不打印摘要行、不打印文件路径、不打印脱敏提示,方便直接管道给
  别的工具(例如 | ConvertFrom-Json、| jq)。落盘的那份请求/响应照旧写,脱敏照旧生效。
  commands 批次打印「每条命令的结果」组成的数组;单条命令打印 data.result(没有就 data)。

.PARAMETER Index
  只输出 commands 批次里第 N 条命令的结果(N 从 0 起)。配 -Json 时就是「只吐这一条」;
  不配 -Json 时打印摘要行 + 这一条的结果。不填就是整批/整条输出。越界会报错并以 1 退出。

.PARAMETER Last
  不重发命令,直接把该会话最新一份 NNN.res.json(按文件名序号取最新)重新打出来。
  用于「刚才那次到底回了什么」的复查;-Json / -Index 同样生效,输出是当时那份(已脱敏的)原文。
  不给 -Session 时用 .current 记着的会话;会话目录不存在或没有 NNN.res.json 时报错并以 1 退出。

.PARAMETER Health
  只做一次健康检查:GET <base>/../health,打印 ok/失败与响应体后退出,不发命令、不落盘。
  加 -Json 时只打印响应体。服务没起来或响应异常时以 1 退出。

.PARAMETER Port
  服务端口,默认 10049。给了 -Port 又没给 -BaseUrl 时,地址按 http://localhost:<Port>/playwright/command
  拼;两个都给时 -BaseUrl 优先(自定义路径/远端地址还是用 -BaseUrl)。

.PARAMETER BaseUrl
  命令地址,默认 http://localhost:10049/playwright/command(即默认端口 10049)。
  与 -Port 同时给出时以本参数为准。

.PARAMETER NoRedact
  关掉落盘前的脱敏。**默认是脱敏的**:写进 .req.json / .res.json / steps.log 之前,手机号、18 位
  身份证号或统一社会信用代码、邮箱、16~19 位长数字会被替换成 ***,与服务端 CommandTraceLog 用同一套
  规则(服务端默认也脱敏)。屏幕上打印的内容**始终是原文**,方便你当场看值;只有落盘的那份被掩。
  需要「日志里也要原文」时加这个开关(例如排查「这个值到底传没传对」)。

.PARAMETER RedactPattern
  追加的自定义脱敏正则(可给多个),在内置规则之后生效。例:公司名、商标名。

.PARAMETER TimeoutSec
  HTTP 超时(秒),默认 300。超时或响应不是合法 JSON 时,会打印一行带 URL 与耗时的错误并以 1 退出。

.EXAMPLE
  pwsh scripts/trace/browse.ps1 -PayloadFile tmp\step1.json

.EXAMPLE
  pwsh scripts/trace/browse.ps1 -PayloadJson '{"id":1001,"method":"get_url"}' -Compact

.EXAMPLE
  pwsh scripts/trace/browse.ps1 -PayloadFile tmp\step1.json -RedactPattern '某某科技有限公司','某某课堂'

.EXAMPLE
  # 服务起在 10050 上:只给端口就行,不用再手写整条 BaseUrl
  pwsh scripts/trace/browse.ps1 -PayloadFile tmp\payloads\list-unsubmitted.json -Session verify-browse -Port 10050

.EXAMPLE
  # 只要批次里第 0 条命令的业务结果,直接给下游用
  pwsh scripts/trace/browse.ps1 -PayloadFile tmp\payloads\list-unsubmitted.json -Session verify-browse -Port 10050 -Json -Index 0

.EXAMPLE
  # 不重跑,复查上一次到底回了什么
  pwsh scripts/trace/browse.ps1 -Last -Session verify-browse -Json

.EXAMPLE
  # 一条命令判断服务起没起来(ok/失败 + 响应体)
  pwsh scripts/trace/browse.ps1 -Health -Port 10049
#>
[CmdletBinding(DefaultParameterSetName = 'File')]
param(
  [Parameter(ParameterSetName = 'File', Mandatory = $true)]
  [string]$PayloadFile,

  # 参数集名字从 Json 改成 Inline:下面新增的 -Json 开关是另一回事,名字分开免得看混
  [Parameter(ParameterSetName = 'Inline', Mandatory = $true)]
  [string]$PayloadJson,

  # 只探活,不发命令
  [Parameter(ParameterSetName = 'Health', Mandatory = $true)]
  [switch]$Health,

  # 复看:不重发,重新打印该会话最新一份响应
  [Parameter(ParameterSetName = 'Last', Mandatory = $true)]
  [switch]$Last,

  [string]$Session,

  [switch]$NewSession,

  [switch]$Compact,

  [switch]$Json,

  # -1 = 没给 -Index,整批/整条输出
  [int]$Index = -1,

  [switch]$NoRedact,

  [string[]]$RedactPattern,

  [int]$TimeoutSec = 300,

  [int]$Port = 10049,

  [string]$BaseUrl
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentRoot = Join-Path $repoRoot 'logs\agent'
$currentFile = Join-Path $agentRoot '.current'

# 地址:两者都给时 -BaseUrl 优先;只给 -Port 就按端口拼;都不给时 -Port 的默认值 10049 与原来的默认地址一致
if (-not $BaseUrl) {
  $BaseUrl = "http://localhost:$Port/playwright/command"
}

# 落盘脱敏:与服务端 CommandTraceLog.BUILT_IN_PATTERNS 保持同一套规则,两边日志的口径才一致
# 边界写成「前后不能还是数字/字母」而不是 \b:实测 1[3-9]\d{9} 会把 13 位的毫秒时间戳
# (2026 年的 1790…,开头正好是 17)当成手机号,把 recordedSince 掩成 ***87,反而看不懂。
$script:redactMask = '***'
$script:redactHits = 0
$builtInPatterns = @(
  '(?<!\d)1[3-9]\d{9}(?!\d)',
  '(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?![0-9A-Za-z])',
  '(?<![0-9A-Za-z])[0-9A-HJ-NPQRTUWXY]{18}(?![0-9A-Za-z])',
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}',
  '(?<!\d)\d{16,19}(?!\d)'
)

<#
  把要落盘的文本里的个人信息替换成掩码。
  只作用于**写文件**的内容:屏幕上打印的仍是原文,免得排查时反而看不到值。
#>
function Protect-Text {
  param([string]$Text)
  if ($NoRedact -or [string]::IsNullOrEmpty($Text)) { return $Text }
  $patterns = @($builtInPatterns)
  if ($RedactPattern) { $patterns += $RedactPattern }
  $result = $Text
  foreach ($pattern in $patterns) {
    if ([string]::IsNullOrWhiteSpace($pattern)) { continue }
    try {
      $hits = [regex]::Matches($result, $pattern).Count
      if ($hits -gt 0) {
        $script:redactHits += $hits
        $result = [regex]::Replace($result, $pattern, $script:redactMask)
      }
    }
    catch {
      # 单条规则写坏不该让整条日志丢掉
    }
  }
  return $result
}

# 摘要行里要露一眼的字段
$highlightKeys = @('url', 'title', 'seq', 'screenshot', 'state_file', 'changed', 'changeStatus', 'status',
  'errorCode', 'engine', 'count', 'succeeded', 'failed', 'pageIndex', 'requestId', 'path')

<#
  从对象里挑几个关键字段拼成一行用的紧凑串。
#>
function Get-Highlight {
  param($Obj, [string[]]$Keys)
  $picked = [ordered]@{}
  foreach ($k in $Keys) {
    $v = $Obj.$k
    if ($null -ne $v -and "$v" -ne '') {
      $s = "$v"
      if ($s.Length -gt 300) { $s = $s.Substring(0, 300) + '…' }
      $picked[$k] = $s
    }
  }
  return $picked
}

<#
  摘要行:时间/序号/任务/方法/成败/耗时/关键字段。本次调用与 -Last 复看共用这一份格式,
  免得两边的输出长得不一样。
#>
function Format-SummaryLine {
  param($ResObj, [string]$Stamp, [string]$TaskId, [string]$Method, [string]$Time, [string]$ElapsedText)
  $line = "$Time #$Stamp id=$TaskId $Method "
  if ($null -ne $ResObj) {
    $okText = if ($ResObj.ok) { 'OK' } else { 'FAIL' }
    $line += "$okText $ElapsedText"
    if ($ResObj.msg) { $line += " msg=$($ResObj.msg -replace '\s+', ' ')" }
    if ($null -ne $ResObj.data) {
      $hl = Get-Highlight -Obj $ResObj.data -Keys $highlightKeys
      foreach ($k in $hl.Keys) { $line += " $k=$($hl[$k])" }
      if ($ResObj.data.PSObject.Properties.Name -contains 'results') {
        foreach ($step in $ResObj.data.results) {
          $mark = if ($step.ok) { 'ok' } else { 'FAIL' }
          $line += " | $($step.index):$($step.command)=$mark"
          if ($step.msg) { $line += "($($step.msg -replace '\s+', ' '))" }
        }
      }
    }
  }
  else {
    $line += "响应不是合法 JSON $ElapsedText"
  }
  return $line
}

<#
  取「业务结果」:有 data.result 就取 result,没有就退到 data,两者都没有就把节点原样给出。
  批次里每条命令也走这个规则,-Json 与 -Index 的取值口径才一致。
#>
function Get-ResultNode {
  param($Node)
  if ($null -eq $Node) { return $null }
  $names = @($Node.PSObject.Properties.Name)
  if ($names -contains 'data') {
    $data = $Node.data
    if ($null -eq $data) { return $null }
    if (@($data.PSObject.Properties.Name) -contains 'result') { return $data.result }
    return $data
  }
  return $Node
}

<#
  算出「要打印什么」:整批(results 数组)/ 单条 / -Index 指定的那一条。
  Valid=$false 表示 -Index 越界,由调用方报错并退出 1。
  用 ArrayList 装批次,单个元素也不会被 PowerShell 拆包(拆了就序列化不出数组)。
#>
function Select-Output {
  param($ResObj)
  $batch = $null
  if ($null -ne $ResObj -and $null -ne $ResObj.data -and
      (@($ResObj.data.PSObject.Properties.Name) -contains 'results')) {
    $batch = New-Object System.Collections.ArrayList
    foreach ($step in @($ResObj.data.results)) { [void]$batch.Add((Get-ResultNode -Node $step)) }
  }
  $count = 1
  if ($null -ne $batch) { $count = $batch.Count }
  $valid = $Index -lt $count
  $picked = $null
  if ($valid -and $Index -ge 0) {
    if ($null -ne $batch) { $picked = $batch[$Index] } else { $picked = Get-ResultNode -Node $ResObj }
  }
  $value = $null
  if ($Index -ge 0) { $value = $picked }
  elseif ($null -ne $batch) { $value = $batch }
  else { $value = Get-ResultNode -Node $ResObj }
  return [pscustomobject]@{ Batch = $batch; Picked = $picked; Value = $value; Count = $count; Valid = $valid }
}

<#
  健康检查地址 = 把命令地址里的 /playwright/command 换成 /playwright/health。
  认不出来就在主机根下拼 /playwright/health,不至于因为自定义路径直接报错。
#>
function Get-HealthUrl {
  param([string]$Url)
  if ($Url -match '^(?<root>.*)/playwright/command/?$') { return $Matches['root'] + '/playwright/health' }
  if ($Url -match '^(?<root>.*)/command/?$') { return $Matches['root'] + '/playwright/health' }
  return $Url.TrimEnd('/') + '/playwright/health'
}

function Resolve-SessionDir {
  param([string]$Name, [switch]$Force)
  if (-not $Name) {
    if (-not $Force -and (Test-Path $currentFile)) {
      $Name = (Get-Content $currentFile -Raw).Trim()
    }
    if (-not $Name) {
      $Name = Get-Date -Format 'yyyyMMdd-HHmmss'
    }
  }
  $dir = Join-Path $agentRoot $Name
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  # 一律写无 BOM 的 UTF-8,免得下游工具把 BOM 当成内容的一部分
  [System.IO.File]::WriteAllText($currentFile, $Name, [System.Text.UTF8Encoding]::new($false))
  return $dir
}

<#
  只算会话目录路径:不建目录、不写 .current。给 -Last 复看用,免得顺手改了「当前会话」指针。
#>
function Get-SessionDirPath {
  param([string]$Name)
  if (-not $Name -and (Test-Path $currentFile)) {
    $Name = (Get-Content $currentFile -Raw).Trim()
  }
  if (-not $Name) { return $null }
  return (Join-Path $agentRoot $Name)
}

<#
  从文件名里取序号;-Last 按序号取最新,不看 LastWriteTime(复制/同步过的目录时间戳不可靠)。
#>
function Get-ResSeq {
  param([string]$Name)
  if ($Name -match '^(\d+)\.res\.json$') { return [int]$Matches[1] }
  return -1
}

<#
  一行错误:超时/连不上/响应不是 JSON 时统一这么报,带 URL 与耗时,便于一眼定位。
  不抛异常,免得 PowerShell 打一堆堆栈出来。
#>
function Write-Fail {
  param([string]$Message)
  Write-Output ('ERROR ' + (($Message -replace '\s+', ' ').Trim()))
}

<#
  打一份响应:-Json 只吐结果 JSON(可配 -Index 取某一条),否则沿用原来的「标题行 + 摘要行 + 完整响应」。
  -Index 不配 -Json 时打印「摘要行 + 这一条的结果」,因为 -Index 的用意就是只要那一条。
#>
function Write-Response {
  param([string]$Text, $Sel, [string]$Line, [string]$Title)
  if ($Json) {
    Write-Output (ConvertTo-Json -InputObject $Sel.Value -Depth 100)
    return
  }
  if ($Compact -and $Index -lt 0) {
    Write-Output $Line
    return
  }
  Write-Output $Title
  Write-Output $Line
  Write-Output ''
  if ($Index -ge 0) {
    Write-Output (ConvertTo-Json -InputObject $Sel.Picked -Depth 100)
  }
  else {
    Write-Output $Text
  }
}

# ---------------- 探活:不发命令、不落盘 ----------------
if ($Health) {
  $healthUrl = Get-HealthUrl -Url $BaseUrl
  $healthStarted = Get-Date
  $healthBody = $null
  $healthError = $null
  try {
    $healthResp = Invoke-WebRequest -Uri $healthUrl -Method Get -UseBasicParsing -TimeoutSec $TimeoutSec
    $healthBody = $healthResp.Content
  }
  catch {
    $healthError = $_.Exception.Message
  }
  $healthMs = [int]((Get-Date) - $healthStarted).TotalMilliseconds
  if ($null -eq $healthBody) {
    Write-Fail "健康检查失败 url=$healthUrl elapsed=${healthMs}ms error=$healthError"
    exit 1
  }
  $healthObj = $null
  try { $healthObj = $healthBody | ConvertFrom-Json } catch { }
  $healthOk = $true
  if ($null -ne $healthObj -and $healthObj.PSObject.Properties.Name -contains 'ok') {
    $healthOk = [bool]$healthObj.ok
  }
  if ($Json) {
    Write-Output $healthBody
  }
  elseif ($healthOk) {
    Write-Output "ok url=$healthUrl elapsed=${healthMs}ms"
    Write-Output $healthBody
  }
  else {
    Write-Fail "健康检查不 ok url=$healthUrl elapsed=${healthMs}ms body=$(($healthBody -replace '\s+', ' ').Trim())"
  }
  if ($healthOk) { exit 0 }
  exit 1
}

# ---------------- 复看:只读最新一份 NNN.res.json ----------------
if ($Last) {
  $lastDir = Get-SessionDirPath -Name $Session
  if (-not $lastDir -or -not (Test-Path $lastDir)) {
    Write-Fail "会话目录不存在:$lastDir"
    exit 1
  }
  $lastFile = Get-ChildItem -Path $lastDir -Filter '*.res.json' -ErrorAction SilentlyContinue |
    Sort-Object -Property @{ Expression = { Get-ResSeq -Name $_.Name } }, Name -Descending |
    Select-Object -First 1
  if ($null -eq $lastFile) {
    Write-Fail "会话目录里没有 NNN.res.json:$lastDir"
    exit 1
  }
  $lastStamp = (Get-ResSeq -Name $lastFile.Name).ToString('000')
  $lastText = [System.IO.File]::ReadAllText($lastFile.FullName, [System.Text.UTF8Encoding]::new($false))
  $lastObj = $null
  try { $lastObj = $lastText | ConvertFrom-Json } catch { }
  if ($null -eq $lastObj) {
    Write-Fail "保存的响应不是合法 JSON:$($lastFile.FullName)"
    exit 1
  }
  # 序号旁边的 id/method 从对应的 .req.json 里取,摘要行才和当初那次长得一样
  $lastMethod = '?'
  $lastTaskId = '?'
  $lastReq = Join-Path $lastDir "$lastStamp.req.json"
  if (Test-Path $lastReq) {
    try {
      $lastReqObj = (Get-Content $lastReq -Raw -Encoding utf8) | ConvertFrom-Json
      if ($lastReqObj.method) { $lastMethod = $lastReqObj.method }
      if ($null -ne $lastReqObj.id) { $lastTaskId = [string]$lastReqObj.id }
    }
    catch { }
  }
  $lastLine = Format-SummaryLine -ResObj $lastObj -Stamp $lastStamp -TaskId $lastTaskId -Method $lastMethod `
    -Time $lastFile.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss.fff') -ElapsedText '?ms'
  $lastSel = Select-Output -ResObj $lastObj
  if (-not $lastSel.Valid) {
    Write-Fail "-Index $Index 越界:#$lastStamp 只回了 $($lastSel.Count) 条结果"
    exit 1
  }
  $title = "---- #$lastStamp $lastMethod (会话 $(Split-Path -Leaf $lastDir),复看 $(Split-Path -Leaf $lastFile.FullName)) ----"
  if ($Index -ge 0) {
    $title = "---- #$lastStamp $lastMethod idx=$Index (会话 $(Split-Path -Leaf $lastDir),复看 $(Split-Path -Leaf $lastFile.FullName)) ----"
  }
  Write-Response -Text $lastText -Sel $lastSel -Line $lastLine -Title $title
  exit 0
}

# ---------------- 正常发命令 ----------------
if ($PSCmdlet.ParameterSetName -eq 'File') {
  if (-not (Test-Path $PayloadFile)) { throw "请求体文件不存在:$PayloadFile" }
  $payload = Get-Content $PayloadFile -Raw -Encoding utf8
}
else {
  $payload = $PayloadJson
}

$sessionDir = Resolve-SessionDir -Name $Session -Force:$NewSession

# 序号 = 目录里已有的响应文件数 + 1
$seq = @(Get-ChildItem -Path $sessionDir -Filter '*.res.json' -ErrorAction SilentlyContinue).Count + 1
$stamp = $seq.ToString('000')

$reqPath = Join-Path $sessionDir "$stamp.req.json"
$resPath = Join-Path $sessionDir "$stamp.res.json"
$stepsLog = Join-Path $sessionDir 'steps.log'

# 请求先落盘:即使服务没起来/请求发不出去,也能看出「我发了什么」。落盘前脱敏
[System.IO.File]::WriteAllText($reqPath, (Protect-Text $payload), [System.Text.UTF8Encoding]::new($false))

$method = '?'
$taskId = '?'
try {
  $parsed = $payload | ConvertFrom-Json
  if ($parsed.method) { $method = $parsed.method }
  if ($null -ne $parsed.id) { $taskId = [string]$parsed.id }
}
catch { }

$started = Get-Date
$responseText = $null
$failure = $null
try {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
  $resp = Invoke-WebRequest -Uri $BaseUrl -Method Post -Body $bytes `
    -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec $TimeoutSec
  $responseText = $resp.Content
}
catch {
  $failure = $_.Exception.Message
}

$elapsedMs = [int]((Get-Date) - $started).TotalMilliseconds
$time = $started.ToString('yyyy-MM-dd HH:mm:ss.fff')

# 注意:解析出来的响应放在 $resObj 里,不能叫 $json —— PowerShell 变量不分大小写,会和 -Json 开关撞车
$resObj = $null
$sendFailed = $false
if ($null -ne $responseText) {
  [System.IO.File]::WriteAllText($resPath, (Protect-Text $responseText), [System.Text.UTF8Encoding]::new($false))
  try { $resObj = $responseText | ConvertFrom-Json } catch { }
  $line = Format-SummaryLine -ResObj $resObj -Stamp $stamp -TaskId $taskId -Method $method -Time $time -ElapsedText "${elapsedMs}ms"
}
else {
  # 没拿到响应:把失败原因写进 res 文件,免得只剩一个空序号
  $sendFailed = $true
  $line = "$time #$stamp id=$taskId $method SEND-FAILED ${elapsedMs}ms error=$failure"
  [System.IO.File]::WriteAllText($resPath, "{`"sendFailed`":true,`"error`":$(ConvertTo-Json $failure)}",
    [System.Text.UTF8Encoding]::new($false))
}

# 落盘的 steps.log 同样脱敏(URL 里也可能带手机号/令牌);屏幕上那行保持原文
if ($script:redactHits -gt 0) {
  $line += " redacted=$($script:redactHits)"
}
[System.IO.File]::AppendAllText($stepsLog, (Protect-Text $line) + [Environment]::NewLine,
  [System.Text.UTF8Encoding]::new($false))

$title = "---- #$stamp $method (会话 $(Split-Path -Leaf $sessionDir)) ----"
if ($Index -ge 0) { $title = "---- #$stamp $method idx=$Index (会话 $(Split-Path -Leaf $sessionDir)) ----" }

$exitCode = 0
if ($sendFailed) {
  # 超时/连不上:一行错误(带 URL 与耗时),不吐堆栈
  Write-Fail "请求发送失败 url=$BaseUrl elapsed=${elapsedMs}ms $method #$stamp error=$failure"
  $exitCode = 1
}
elseif ($null -eq $resObj) {
  # 响应不是合法 JSON:一行错误(带 URL 与耗时),非 -Json 时把原文也打出来便于看个究竟
  Write-Fail "响应不是合法 JSON url=$BaseUrl elapsed=${elapsedMs}ms $method #$stamp"
  if (-not $Json -and -not $Compact) {
    Write-Output $title
    Write-Output $line
    Write-Output ''
    Write-Output $responseText
  }
  $exitCode = 1
}
else {
  $sel = Select-Output -ResObj $resObj
  if (-not $sel.Valid) {
    Write-Fail "-Index $Index 越界:#$stamp $method 只回了 $($sel.Count) 条结果"
    $exitCode = 1
  }
  else {
    Write-Response -Text $responseText -Sel $sel -Line $line -Title $title
    if (-not $Json -and $script:redactHits -gt 0 -and -not $NoRedact) {
      Write-Output "# 落盘日志已脱敏 $($script:redactHits) 处(手机号/证件号/邮箱/长数字);要看原文加 -NoRedact"
    }
  }
}

exit $exitCode
