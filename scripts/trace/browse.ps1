<#
.SYNOPSIS
  把一条命令发给本地浏览器服务,并把**发出去的请求**与**收回来的响应**都留档到本地。

.DESCRIPTION
  服务端自己在 logs/trace/ 下也留了一份审计(见 CommandTraceLog),这个脚本是客户端这一侧的另一半:
  它记录「我到底发了什么」(发送前就落盘,连没连上服务都能看出来),并给每次调用编一个可读的序号,
  便于按顺序复看整个过程。

  每个会话一个目录 logs/agent/<会话名>/:
    NNN.req.json    第 NNN 次调用发出去的请求体(原文)
    NNN.res.json    第 NNN 次调用收回来的响应体(原文,含整页 data.text)
    steps.log       每次调用一行:时间/序号/任务/方法/成败/耗时/关键字段

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

.PARAMETER TimeoutSec
  HTTP 超时(秒),默认 300。

.EXAMPLE
  pwsh scripts/trace/browse.ps1 -PayloadFile tmp\step1.json

.EXAMPLE
  pwsh scripts/trace/browse.ps1 -PayloadJson '{"id":1001,"method":"get_url"}' -Compact
#>
[CmdletBinding(DefaultParameterSetName = 'File')]
param(
  [Parameter(ParameterSetName = 'File', Mandatory = $true)]
  [string]$PayloadFile,

  [Parameter(ParameterSetName = 'Json', Mandatory = $true)]
  [string]$PayloadJson,

  [string]$Session,

  [switch]$NewSession,

  [switch]$Compact,

  [int]$TimeoutSec = 300,

  [string]$BaseUrl = 'http://localhost:10049/playwright/command'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentRoot = Join-Path $repoRoot 'logs\agent'
$currentFile = Join-Path $agentRoot '.current'

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

# 请求先落盘:即使服务没起来/请求发不出去,也能看出「我发了什么」
[System.IO.File]::WriteAllText($reqPath, $payload, [System.Text.UTF8Encoding]::new($false))

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

$highlightKeys = @('url', 'title', 'seq', 'screenshot', 'state_file', 'changed', 'changeStatus', 'status',
  'errorCode', 'engine', 'count', 'succeeded', 'failed', 'pageIndex', 'requestId', 'path')

if ($null -ne $responseText) {
  [System.IO.File]::WriteAllText($resPath, $responseText, [System.Text.UTF8Encoding]::new($false))
  $json = $null
  try { $json = $responseText | ConvertFrom-Json } catch { }

  $line = "$time #$stamp id=$taskId $method "
  if ($null -ne $json) {
    $okText = if ($json.ok) { 'OK' } else { 'FAIL' }
    $line += "$okText ${elapsedMs}ms"
    if ($json.msg) { $line += " msg=$($json.msg -replace '\s+', ' ')" }
    if ($null -ne $json.data) {
      $hl = Get-Highlight -Obj $json.data -Keys $highlightKeys
      foreach ($k in $hl.Keys) { $line += " $k=$($hl[$k])" }
      if ($json.data.PSObject.Properties.Name -contains 'results') {
        foreach ($step in $json.data.results) {
          $mark = if ($step.ok) { 'ok' } else { 'FAIL' }
          $line += " | $($step.index):$($step.command)=$mark"
          if ($step.msg) { $line += "($($step.msg -replace '\s+', ' '))" }
        }
      }
    }
  }
  else {
    $line += "响应不是合法 JSON ${elapsedMs}ms"
  }
}
else {
  # 没拿到响应:把失败原因写进 res 文件,免得只剩一个空序号
  $line = "$time #$stamp id=$taskId $method SEND-FAILED ${elapsedMs}ms error=$failure"
  [System.IO.File]::WriteAllText($resPath, "{`"sendFailed`":true,`"error`":$(ConvertTo-Json $failure)}",
    [System.Text.UTF8Encoding]::new($false))
}

[System.IO.File]::AppendAllText($stepsLog, $line + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

if ($Compact) {
  Write-Output $line
}
else {
  Write-Output "---- #$stamp $method (会话 $(Split-Path -Leaf $sessionDir)) ----"
  Write-Output $line
  Write-Output ''
  if ($null -ne $responseText) { Write-Output $responseText } else { Write-Output "请求发送失败:$failure" }
}