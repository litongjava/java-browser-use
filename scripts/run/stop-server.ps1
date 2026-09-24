<#
.SYNOPSIS
  停掉 deepseek-browser-use 服务(先关任务与共享浏览器,再结束进程,不留孤儿浏览器)

.DESCRIPTION
  顺序很重要:**先关浏览器,再杀服务**。浏览器是独立进程,直接 taskkill 服务会把它留下来,
  它继续占着 profile 目录,下一次 start 可能卡到启动超时(默认 60 秒)。

  所以这里:
    1. 调一次 /playwright/command 的 shutdown(关掉全部任务与共享浏览器);
    2. 再按端口找到监听进程,连同它的父进程树一起结束(开发态下是 cmd → mvn → java 三层);
    3. 清理 pid 文件。

  注意:关掉最后一个任务时浏览器就退出了,而 session cookie 型的登录态(12306 等)会随之失效 ——
  持久 cookie 不受影响。所以「重启服务」这个动作本身是有代价的,别把它当成无痛操作。

.PARAMETER Port
  服务端口,默认 10049。

.PARAMETER KeepBrowser
  只杀服务进程,不动浏览器(默认不做:那正是会留下孤儿浏览器的做法)。

.EXAMPLE
  pwsh -File scripts/run/stop-server.ps1
#>
param(
  [int]$Port = 10049,
  [switch]$KeepBrowser,
  [switch]$Quiet
)

$ErrorActionPreference = 'Continue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pidFile = Join-Path $repoRoot "logs\server\server-$Port.pid"

function Write-Info { param([string]$Text) if (-not $Quiet) { Write-Host $Text } }

# 1) 先让服务自己把任务与浏览器关掉(不留孤儿)
if (-not $KeepBrowser) {
  $body = '{"method":"shutdown","params":{}}'
  try {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/playwright/command" -Method POST `
      -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 30
    Write-Info "shutdown 回执:$($response.Content)"
  } catch {
    Write-Info "shutdown 没成功(服务可能已经没在跑):$($_.Exception.Message)"
  }
  Start-Sleep -Milliseconds 800
}

# 2) 按端口找监听进程,连同父进程树一起结束
$owners = @()
try {
  $owners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique
} catch {
  $owners = @()
}

if (-not $owners -or $owners.Count -eq 0) {
  Write-Info "端口 $Port 上没有监听进程,服务应该已经停了。"
} else {
  foreach ($ownerPid in $owners) {
    $proc = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
    if ($proc) {
      Write-Info "结束进程 pid=$ownerPid($($proc.ProcessName))以及它的子进程"
    }
    # /T 连子进程一起结束:开发态是 cmd → mvn → java,只杀 java 会留下 mvn
    & taskkill.exe /PID $ownerPid /T /F 2>&1 | ForEach-Object { Write-Info "  $_" }
  }
}

# 3) 清 pid 文件(顺带把生成出来的启动器也删掉)
if (Test-Path $pidFile) {
  Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}
$launcher = Join-Path $repoRoot "logs\server\run-$Port.cmd"
if (Test-Path $launcher) {
  Remove-Item $launcher -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Milliseconds 500
$still = $null
try {
  $still = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/playwright/health" -UseBasicParsing -TimeoutSec 3).StatusCode
} catch {
  $still = $null
}
if ($still) {
  Write-Warning "端口 $Port 还在应答,可能没杀干净(用 Get-NetTCPConnection -LocalPort $Port 看看)"
  exit 1
}
Write-Info "服务已停止($Port)。"
exit 0
