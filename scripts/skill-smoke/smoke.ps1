<#
  deepseek-browser-use 技能冒烟测试

  用 HTTP 接口把技能文档（.agents/skills/deepseek-browser-use/SKILL.md）里承诺的行为跑一遍：
  端点形态、start 的「一任务一实例」、get_browser_state 的页签块、自动截图与结构化文本落盘、
  批量指令、以及几种错误响应。

  文档与源码的「方法名/参数名」一致性由 playwright-server 里的 CommandTableTest 与
  SkillDocConsistencyTest 在构建阶段拦截；这个脚本负责跑真实的 HTTP 行为。

  前置：服务已启动，二选一
    1) 发行版：在 dist 目录执行  java -jar deepseek-browser-use-<版本>-<平台>.jar
    2) 开发态：在 playwright-server 目录执行  mvn spring-boot:run

  用法：
    pwsh -File scripts/skill-smoke/smoke.ps1
    pwsh -File scripts/skill-smoke/smoke.ps1 -Base http://localhost:10050 -Id 900002

  执行策略被拦时（不能改策略的机器）：
    cd 到本目录，然后
    & ([scriptblock]::Create((Get-Content -Raw -Encoding UTF8 .\smoke.ps1)))

  退出码：0 全部通过，1 有断言失败，2 环境不可用
#>
param(
  [string]$Base = 'http://localhost:10049',
  [long]$Id = 900001,
  [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Command = "$Base/playwright/command"
$script:Passed = 0
$script:Failed = 0

function Invoke-Command {
  param([string]$Method, [hashtable]$Params = @{})
  $payload = @{ id = $Id; method = $Method; params = $Params } | ConvertTo-Json -Depth 12 -Compress
  return Invoke-RawCommand -Json $payload
}

function Invoke-RawCommand {
  param([string]$Json)
  try {
    return Invoke-RestMethod -Uri $Command -Method Post -Body $Json `
      -ContentType 'application/json; charset=utf-8' -TimeoutSec 180
  } catch {
    throw "调用 $Command 失败：$($_.Exception.Message) $($_.ErrorDetails.Message)"
  }
}

function Assert-That {
  param([string]$Name, [bool]$Condition, [string]$Detail = '')
  if ($Condition) {
    $script:Passed++
    Write-Host "  [OK]   $Name" -ForegroundColor Green
  } else {
    $script:Failed++
    Write-Host "  [FAIL] $Name -- $Detail" -ForegroundColor Red
  }
}

function Assert-Ok {
  param([string]$Name, $Response)
  Assert-That $Name ($Response.ok -eq $true -and $Response.code -eq 1) "ok=$($Response.ok) msg=$($Response.msg)"
}

function Section {
  param([string]$Title)
  Write-Host ''
  Write-Host "== $Title" -ForegroundColor Cyan
}

# ---------------------------------------------------------------- 前置检查

Section '前置检查'
try {
  $health = Invoke-RestMethod -Uri "$Base/playwright/health" -TimeoutSec 10
  Assert-That 'GET /playwright/health 可用' ($health.ok -eq $true) "got $($health | ConvertTo-Json -Compress)"
} catch {
  Write-Host "服务不可达：$Base" -ForegroundColor Red
  Write-Host "请先启动服务（java -jar 发行包，或在 playwright-server 目录执行 mvn spring-boot:run）" -ForegroundColor Red
  exit 2
}

# 同一个 id 不能重复 start，先把可能残留的实例关掉
try { Invoke-Command -Method 'close' | Out-Null } catch { }

# ---------------------------------------------------------------- 一任务一实例

Section 'start：一个任务一个实例'
$started = Invoke-Command -Method 'start' -Params @{ headless = $true }
Assert-Ok 'start 成功' $started
Assert-That 'start 回传的就是请求里指定的 id' ("$($started.data.id)" -eq "$Id") "got $($started.data.id)"

$again = Invoke-Command -Method 'start' -Params @{ headless = $true }
Assert-That '同一个 id 重复 start 会失败' ($again.ok -eq $false) "got $($again | ConvertTo-Json -Compress)"
Assert-That '重复 start 的中文提示给出下一步' ($again.msg -like '*已经有正在运行的浏览器实例*') "msg=$($again.msg)"

# ---------------------------------------------------------------- 导航与自动截图

Section '导航：会改变页面的方法自动截图'
$goto = Invoke-Command -Method 'go_to_url' -Params @{ url = 'https://example.com' }
Assert-Ok 'go_to_url 成功' $goto
Assert-That 'go_to_url 带回了截图序号 1' ($goto.data.seq -eq 1) "seq=$($goto.data.seq)"
Assert-That 'go_to_url 带回了截图 URL' ($goto.data.screenshot -eq "/data/$Id/1.png") "screenshot=$($goto.data.screenshot)"

$png = Invoke-WebRequest -Uri "$Base/data/$Id/1.png" -UseBasicParsing -TimeoutSec 30
Assert-That 'GET /data/<id>/1.png 能取到截图' ($png.StatusCode -eq 200 -and $png.RawContentLength -gt 1000) `
  "status=$($png.StatusCode) len=$($png.RawContentLength)"

# ---------------------------------------------------------------- get_browser_state

Section 'get_browser_state：页签块 + 结构化文本 + 落盘'
$state = Invoke-Command -Method 'get_browser_state'
Assert-Ok 'get_browser_state 成功' $state

$browserState = [string]$state.data.browser_state
Assert-That 'browser_state 第一行是 Browser tab: 1' ($browserState -like 'Browser tab: 1, *') "got '$browserState'"
Assert-That 'browser_state 有 current tab is: 行' ($browserState -like '*current tab is: 1*') "got '$browserState'"
Assert-That 'browser_state 里有 Title 与 URL' ($browserState -like '*Title: "*' -and $browserState -like '*URL: "*') "got '$browserState'"

$text = [string]$state.data.text
Assert-That 'text 里有可交互元素索引' ($text -match '\[\d+\]<') 'text 里没有 [index]<...> 行'

$seq = $state.data.seq
Assert-That 'get_browser_state 序号自增到 2' ($seq -eq 2) "seq=$seq"
Assert-That 'state_file 与截图同名、后缀 txt' ($state.data.state_file -eq "/data/$Id/$seq.txt") "state_file=$($state.data.state_file)"

$txt = Invoke-WebRequest -Uri "$Base/data/$Id/$seq.txt" -UseBasicParsing -TimeoutSec 30
Assert-That 'GET /data/<id>/<seq>.txt 能取到结构化文本' ($txt.StatusCode -eq 200) "status=$($txt.StatusCode)"
Assert-That 'txt 内容以页签块开头' ($txt.Content -like 'Browser tab: 1*') "got '$($txt.Content.Substring(0, [Math]::Min(60, $txt.Content.Length)))'"
Assert-That 'txt 内容包含可交互结构化文本' ($txt.Content -match '\[\d+\]<') 'txt 里没有 [index] 行'

# ---------------------------------------------------------------- 页签

Section '页签信息'
$newTab = Invoke-Command -Method 'new_tab' -Params @{ url = 'https://example.org' }
Assert-Ok 'new_tab 成功' $newTab
Assert-That 'new_tab 返回 0 基的 pageIndex=1' ($newTab.data.pageIndex -eq 1) "pageIndex=$($newTab.data.pageIndex)"

$two = Invoke-Command -Method 'get_browser_state'
$twoState = [string]$two.data.browser_state
Assert-That '两个页签时输出 Browser tab: 1 与 Browser tab: 2' `
  ($twoState -like '*Browser tab: 1,*' -and $twoState -like '*Browser tab: 2,*') "got '$twoState'"
Assert-That 'current tab is: 2（文本是 1 基）' ($twoState -like '*current tab is: 2*') "got '$twoState'"
Assert-That 'data.tabs 仍然是 0 基' ($two.data.tabs[1].index -eq 1) "tabs[1].index=$($two.data.tabs[1].index)"

$switched = Invoke-Command -Method 'switch_tab' -Params @{ pageIndex = 0 }
Assert-Ok 'switch_tab pageIndex=0 成功' $switched

# ---------------------------------------------------------------- 批量指令

Section 'commands：批量指令'
$batch = Invoke-RawCommand -Json (@{
    id     = $Id
    method = 'commands'
    params = @{
      stopOnError = $false
      commands    = @(
        @{ get_title = @{} },
        @{ get_url = @{} },
        @{ click_element_by_index = @{ index = 999999 } },
        @{ get_browser_state = @{} }
      )
    }
  } | ConvertTo-Json -Depth 12 -Compress)

Assert-That '批量里有一条失败时整体 ok=false' ($batch.ok -eq $false) "ok=$($batch.ok)"
Assert-That '批量返回了全部 4 步结果' ($batch.data.count -eq 4) "count=$($batch.data.count)"
Assert-That '批量统计 succeeded=3 / failed=1' ($batch.data.succeeded -eq 3 -and $batch.data.failed -eq 1) `
  "succeeded=$($batch.data.succeeded) failed=$($batch.data.failed)"
Assert-That '批量里 get_browser_state 也落了 txt' ($batch.data.results[3].data.state_file -like "/data/$Id/*.txt") `
  "state_file=$($batch.data.results[3].data.state_file)"
Assert-That '批量里的失败原因是中文' ($batch.data.results[2].msg -like '*索引越界*') "msg=$($batch.data.results[2].msg)"

# ---------------------------------------------------------------- 错误响应

Section '错误响应：都是 JSON，不再 500'
$noMethod = Invoke-RawCommand -Json (@{ id = $Id } | ConvertTo-Json -Compress)
Assert-That '缺 method 报中文错' ($noMethod.ok -eq $false -and $noMethod.msg -like '*缺少参数 method*') "msg=$($noMethod.msg)"

$unknown = Invoke-Command -Method 'no_such_method'
Assert-That '未知方法报「不支持的方法」' ($unknown.msg -like '*不支持的方法*') "msg=$($unknown.msg)"

$missing = Invoke-Command -Method 'click_element_by_index' -Params @{}
Assert-That '缺必填参数报「缺少参数 index」' ($missing.msg -like '*缺少参数 index*') "msg=$($missing.msg)"

$notFound = Invoke-RawCommand -Json (@{ id = 987654321; method = 'get_title' } | ConvertTo-Json -Compress)
Assert-That '实例不存在时提示中文原因' ($notFound.msg -like '*没有找到对应的浏览器实例*') "msg=$($notFound.msg)"

$badJson = Invoke-RawCommand -Json '{ this is not json'
Assert-That '请求体不是合法 JSON 时给中文提示' ($badJson.msg -like '*不是合法 JSON*') "msg=$($badJson.msg)"

# ---------------------------------------------------------------- 收尾

Section '收尾'
if ($Keep) {
  Write-Host "  -Keep 指定，保留实例 id=$Id（记得自己 close）" -ForegroundColor Yellow
} else {
  $closed = Invoke-Command -Method 'close'
  Assert-Ok 'close 成功' $closed
}

Write-Host ''
$total = $script:Passed + $script:Failed
if ($script:Failed -eq 0) {
  Write-Host "全部通过：$($script:Passed)/$total" -ForegroundColor Green
  exit 0
}
Write-Host "有失败：$($script:Failed)/$total" -ForegroundColor Red
exit 1
