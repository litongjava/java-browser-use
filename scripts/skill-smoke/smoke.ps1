<#
  java-browser-use 技能冒烟测试

  用 HTTP 接口把 SKILL.md 里承诺的行为跑一遍,每条断言对应文档里的一句话。
  文档与源码不一致时(接口名、参数名)由 SkillDocConsistencyTest 负责拦截,
  这个脚本负责"文档说的行为是不是真的"。

  前置:
    1. 服务已启动:在 playwright-server 目录执行 mvn spring-boot:run
    2. node 在 PATH 上(用于起本地测试页)
    3. 默认地址 http://localhost:10049,端口来自 playwright-server/src/main/resources/app.properties

  用法:
    powershell -ExecutionPolicy Bypass -File smoke.ps1
    powershell -ExecutionPolicy Bypass -File smoke.ps1 -Base http://localhost:10050/api/v1/playwright

  执行策略被拦时(不能改策略的机器):
    cd 到本目录,然后
    & ([scriptblock]::Create((Get-Content -Raw -Encoding UTF8 .\smoke.ps1)))

  退出码:0 全部通过,1 有断言失败,2 环境不可用(服务/测试页/node 有问题)
#>
param(
  [string]$Base = 'http://localhost:10049/api/v1/playwright',
  [int]$PagePort = 10054
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$outDir = Join-Path $root 'out'
$pageBase = "http://127.0.0.1:$PagePort"

$script:pass = 0
$script:fail = 0
$script:failures = New-Object System.Collections.ArrayList
$id = $null
$domText = ''

function Resp([string]$path, [hashtable]$params) {
  $qs = @()
  if ($params) {
    foreach ($kv in $params.GetEnumerator()) {
      $qs += "$($kv.Key)=" + [uri]::EscapeDataString([string]$kv.Value)
    }
  }
  $url = "$Base/$path"
  if ($qs.Count -gt 0) { $url += '?' + ($qs -join '&') }
  try {
    return Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 90
  } catch {
    Write-Host ("      HTTP 错误: " + $_.Exception.Message) -ForegroundColor DarkGray
    return $null
  }
}

function PostJson([string]$path, [string]$json, [string]$query = '') {
  $url = "$Base/$path"
  if ($query) { $url += '?' + $query }
  try {
    return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 90
  } catch {
    Write-Host ("      HTTP 错误: " + $_.Exception.Message) -ForegroundColor DarkGray
    return $null
  }
}

function IsOk($r) { return ($null -ne $r) -and ($r.code -eq 1) }
function Field($r, [string]$name) { if (($null -eq $r) -or ($null -eq $r.data)) { return $null } return $r.data.$name }
function Msg($r) { if ($null -eq $r) { return '<没有 JSON 响应>' } return [string]$r.msg }

function Check([string]$name, $ok, [string]$detail = '') {
  if ($ok) {
    $script:pass++
    Write-Host ("  PASS  " + $name) -ForegroundColor Green
  } else {
    $script:fail++
    [void]$script:failures.Add($name)
    if ($detail) { Write-Host ("  FAIL  " + $name + "   <- " + $detail) -ForegroundColor Red }
    else { Write-Host ("  FAIL  " + $name) -ForegroundColor Red }
  }
}

function Js([string]$expr) {
  $r = Resp 'execute_js' @{ id = $id; body = $expr }
  if (IsOk $r) { return (Field $r 'result') }
  Write-Host ("      execute_js 失败: " + (Msg $r)) -ForegroundColor DarkGray
  return $null
}

function Idx([string]$pattern) {
  foreach ($line in ($domText -split "`n")) {
    if ($line -match '\[(\d+)\]') {
      $n = [int]$Matches[1]
      if ($line -match $pattern) { return $n }
    }
  }
  Write-Host ("      快照里找不到元素: " + $pattern) -ForegroundColor DarkGray
  return -1
}

function Section([string]$title) { Write-Host ''; Write-Host ("== " + $title) -ForegroundColor Cyan }

# 控制台日志是异步回传的,reload 刚返回时页面脚本的后续日志可能还没到,所以轮询等待
function WaitLogs([string]$pattern, [int]$seconds = 5) {
  $logs = ''
  for ($i = 0; $i -lt ($seconds * 4); $i++) {
    $logs = (Field (Resp 'get_console_logs' @{ id = $id }) 'logs') -join '|'
    if ($logs -like $pattern) { return $logs }
    Start-Sleep -Milliseconds 250
  }
  return $logs
}

Write-Host 'java-browser-use 技能冒烟测试' -ForegroundColor Cyan
Write-Host ("  接口前缀: " + $Base)
Write-Host ("  测试页  : " + $pageBase + "/test.html")

$exitCode = 0
$static = $null
try {
  # ---------- 环境检查 ----------
  $reachable = $true
  try { Invoke-WebRequest -Uri "$Base/get_tabs?id=1" -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { $reachable = $false }
  if (-not $reachable) {
    throw "服务不可达:" + $Base + "`n请先在 playwright-server 目录执行 mvn spring-boot:run"
  }
  $nodeCmd = Get-Command node -ErrorAction SilentlyContinue
  if (-not $nodeCmd) { throw '找不到 node,无法启动本地测试页服务' }

  New-Item -ItemType Directory -Force $outDir | Out-Null
  $static = Start-Process -FilePath $nodeCmd.Source `
    -ArgumentList @((Join-Path $root 'server.js'), $root, $PagePort) -PassThru -WindowStyle Hidden
  $pageUp = $false
  for ($i = 0; $i -lt 40; $i++) {
    try { Invoke-WebRequest -Uri "$pageBase/test.html" -UseBasicParsing -TimeoutSec 2 | Out-Null; $pageUp = $true; break }
    catch { Start-Sleep -Milliseconds 250 }
  }
  if (-not $pageUp) { throw "本地测试页服务没起来(端口 " + $PagePort + ")" }

  # ---------- 启动与导航 ----------
  Section '启动与导航'
  $r = Resp 'start' @{ headless = 'true' }
  Check 'start 返回 id' ((IsOk $r) -and ((Field $r 'id') -ne $null)) (Msg $r)
  $id = Field $r 'id'
  if (-not $id) { throw '没有拿到实例 id,后续用例无法执行' }

  $r = Resp 'go_to_url' @{ id = $id; url = "$pageBase/test.html" }
  Check 'go_to_url 返回 status=200' ((IsOk $r) -and ((Field $r 'status') -eq 200)) (Msg $r)

  $r = Resp 'get_url' @{ id = $id }
  Check 'get_url 返回当前地址' ((IsOk $r) -and ((Field $r 'url') -like '*test.html')) (Msg $r)

  $r = Resp 'get_title' @{ id = $id }
  Check 'get_title 返回页面标题' ((IsOk $r) -and ((Field $r 'title') -eq 'Playwright 接口测试页')) (Msg $r)

  $r = Resp 'reload' @{ id = $id }
  Check 'reload 返回 status=200' ((IsOk $r) -and ((Field $r 'status') -eq 200)) (Msg $r)

  # ---------- get_dom_text ----------
  Section 'get_dom_text 与元素索引'
  $r = Resp 'get_dom_text' @{ id = $id }
  Check 'get_dom_text 成功' (IsOk $r) (Msg $r)
  Check 'get_dom_text 返回 text/url/title/tabs' `
    (((Field $r 'text') -ne $null) -and ((Field $r 'url') -ne $null) -and ((Field $r 'title') -ne $null) -and ((Field $r 'tabs') -ne $null))
  Check 'get_dom_text 返回滚动与视口信息' `
    (((Field $r 'viewport_height') -gt 0) -and ((Field $r 'page_height') -gt 0))
  $domText = [string](Field $r 'text')
  Check '文本行形如 [index]<tag ...>' ($domText -match '\[\d+\]<')
  Check '文本里包含可交互按钮' ($domText -like '*<button >点我/>*')
  Check '文本不含 id 属性(文档已说明)' (-not ($domText -like "*id='btn'*"))

  $iBtn = Idx '<button >点我/>'
  $iTxt = Idx "placeholder='请输入用户名'"
  $iChk = Idx "type='checkbox'"
  $iSel = Idx "aria-label='城市'"
  $iDbl = Idx '>双击我/>'
  $iHov = Idx '>悬停我/>'
  $iDrag = Idx '>拖我/>'
  $iDrop = Idx '>放到这里/>'
  $iAlert = Idx '>弹窗/>'
  $iToggle = Idx '>隐藏点我按钮/>'
  $iAttr = Idx '>属性测试/>'
  Check '索引能定位到页面元素' (($iBtn -ge 0) -and ($iTxt -ge 0) -and ($iChk -ge 0) -and ($iSel -ge 0) -and ($iAttr -ge 0))

  # ---------- 元素交互 ----------
  Section '元素交互'
  Check 'click_element_by_index 生效' (IsOk (Resp 'click_element_by_index' @{ id = $id; index = $iBtn }))
  Check '  点击计数为 1' ((Js 'window.__clicks') -eq 1)

  Check 'double_click_element_by_index 生效' (IsOk (Resp 'double_click_element_by_index' @{ id = $id; index = $iDbl }))
  Check '  双击计数为 1' ((Js 'window.__dbl') -eq 1)

  Check 'hover_element_by_index 生效' (IsOk (Resp 'hover_element_by_index' @{ id = $id; index = $iHov }))
  Check '  悬停计数 >= 1' ([int](Js 'window.__hover') -ge 1)

  Check 'focus_element_by_index 生效' (IsOk (Resp 'focus_element_by_index' @{ id = $id; index = $iTxt }))
  Check '  焦点落在输入框' ((Js 'document.activeElement.id') -eq 'txt')

  Check 'type_text 逐字输入' (IsOk (Resp 'type_text' @{ id = $id; index = $iTxt; text = 'abc' }))
  Check '  输入值为 abc' ((Field (Resp 'get_element_value' @{ id = $id; index = $iTxt }) 'value') -eq 'abc')
  Check 'input_text 覆盖填充' (IsOk (Resp 'input_text' @{ id = $id; index = $iTxt; text = 'Mac Mini M4' }))
  Check '  输入值为 Mac Mini M4' ((Field (Resp 'get_element_value' @{ id = $id; index = $iTxt }) 'value') -eq 'Mac Mini M4')

  $r = Resp 'input_text' @{ id = $id; index = $iTxt; text = '中文参数测试' }
  Check 'input_text 支持中文参数' ((IsOk $r) -and ((Field (Resp 'get_element_value' @{ id = $id; index = $iTxt }) 'value') -eq '中文参数测试')) (Msg $r)

  Check 'check_element_by_index 勾选' (IsOk (Resp 'check_element_by_index' @{ id = $id; index = $iChk }))
  Check '  is_checked = true' ((Field (Resp 'is_checked' @{ id = $id; index = $iChk }) 'checked') -eq $true)
  Check 'uncheck_element_by_index 取消勾选' (IsOk (Resp 'uncheck_element_by_index' @{ id = $id; index = $iChk }))
  Check '  is_checked = false' ((Field (Resp 'is_checked' @{ id = $id; index = $iChk }) 'checked') -eq $false)

  Check 'drag_element_by_index 生效' (IsOk (Resp 'drag_element_by_index' @{ id = $id; index = $iDrag; targetIndex = $iDrop }))
  Check '  放置计数为 1' ((Js 'window.__drop') -eq 1)

  Check 'key_down 生效' (IsOk (Resp 'key_down' @{ id = $id; keys = 'Shift' }))
  Check 'key_up 生效' (IsOk (Resp 'key_up' @{ id = $id; keys = 'Shift' }))

  # ---------- 读取信息与状态 ----------
  Section '读取元素信息与状态'
  Check 'get_element_text 返回文本' ((Field (Resp 'get_element_text' @{ id = $id; index = $iAttr }) 'text') -eq '属性测试')
  Check 'get_element_html 返回 HTML' ((Field (Resp 'get_element_html' @{ id = $id; index = $iAttr }) 'html') -eq '属性测试')
  Check 'get_element_attribute 返回属性值' ((Field (Resp 'get_element_attribute' @{ id = $id; index = $iAttr; name = 'data-role' }) 'value') -eq 'demo')
  Check 'get_element_count 统计按钮数' ((Field (Resp 'get_element_count' @{ id = $id; selector = 'button' }) 'count') -eq 9)
  Check 'get_element_box 返回坐标' (([double](Field (Resp 'get_element_box' @{ id = $id; index = $iBtn }) 'width')) -gt 0)
  Check 'is_visible = true' ((Field (Resp 'is_visible' @{ id = $id; index = $iBtn }) 'visible') -eq $true)
  Check 'is_enabled = true' ((Field (Resp 'is_enabled' @{ id = $id; index = $iBtn }) 'enabled') -eq $true)

  $r = Resp 'get_element_text' @{ id = $id; index = 99999 }
  Check '越界索引返回 code:0 与中文原因' ((-not (IsOk $r)) -and ((Msg $r) -like '*索引越界*')) (Msg $r)

  # ---------- 选择器 / 文本 / 语义定位 ----------
  Section '选择器 / 文本 / 语义定位'
  Check 'click_element_by_text 生效' (IsOk (Resp 'click_element_by_text' @{ id = $id; text = '点我' }))
  Check '  点击计数为 2' ((Js 'window.__clicks') -eq 2)
  Check 'click_element_by_selector 生效' (IsOk (Resp 'click_element_by_selector' @{ id = $id; selector = '#btn' }))
  Check '  点击计数为 3' ((Js 'window.__clicks') -eq 3)
  Check 'click_element_by_role 生效' (IsOk (Resp 'click_element_by_role' @{ id = $id; role = 'button'; name = '提交表单' }))
  Check 'input_text_by_selector 生效' (IsOk (Resp 'input_text_by_selector' @{ id = $id; selector = '#txt'; text = 'by-selector' }))
  Check '  输入值为 by-selector' ((Field (Resp 'get_element_value' @{ id = $id; index = $iTxt }) 'value') -eq 'by-selector')
  Check 'input_text_by_label 生效' (IsOk (Resp 'input_text_by_label' @{ id = $id; label = '用户名'; text = 'by-label' }))
  Check '  输入值为 by-label' ((Field (Resp 'get_element_value' @{ id = $id; index = $iTxt }) 'value') -eq 'by-label')

  # ---------- 标签页 ----------
  Section '标签页'
  Check 'get_tabs 返回 1 个标签页' (((Resp 'get_tabs' @{ id = $id })).data.tabs.Count -eq 1)
  $r = Resp 'new_tab' @{ id = $id; url = "$pageBase/other.html" }
  Check 'new_tab 返回 pageIndex=1' ((IsOk $r) -and ((Field $r 'pageIndex') -eq 1)) (Msg $r)
  Check '  当前页切到新标签页' ((Field (Resp 'get_title' @{ id = $id }) 'title') -eq '第二个页面')
  Check 'switch_tab 切回第 0 个' (IsOk (Resp 'switch_tab' @{ id = $id; pageIndex = 0 }))
  Check '  标题回到测试页' ((Field (Resp 'get_title' @{ id = $id }) 'title') -eq 'Playwright 接口测试页')
  Check 'close_tab 关闭第 1 个' (IsOk (Resp 'close_tab' @{ id = $id; pageIndex = 1 }))
  Check '  只剩 1 个标签页' (((Resp 'get_tabs' @{ id = $id })).data.tabs.Count -eq 1)

  # ---------- 等待 ----------
  Section '等待'
  Check 'wait_for_element 命中' (IsOk (Resp 'wait_for_element' @{ id = $id; selector = '#btn'; timeoutSeconds = 5 }))
  Check 'wait_for_text 命中' (IsOk (Resp 'wait_for_text' @{ id = $id; text = '双击我'; timeoutSeconds = 5 }))
  Check 'wait_for_url 命中' (IsOk (Resp 'wait_for_url' @{ id = $id; url = '**/test.html'; timeoutSeconds = 5 }))
  Check 'wait_for_load 命中' (IsOk (Resp 'wait_for_load' @{ id = $id; state = 'networkidle'; timeoutSeconds = 10 }))
  Check 'wait_for_function 命中' (IsOk (Resp 'wait_for_function' @{ id = $id; expression = '() => document.readyState === "complete"'; timeoutSeconds = 5 }))
  $r = Resp 'wait_for_element' @{ id = $id; selector = '#nothing-here'; timeoutSeconds = 2 }
  Check '等待超时给出中文原因' ((-not (IsOk $r)) -and ((Msg $r) -like '*超时*')) (Msg $r)

  # ---------- 鼠标 ----------
  Section '鼠标'
  Check 'mouse_move 生效' (IsOk (Resp 'mouse_move' @{ id = $id; x = 100; y = 120 }))
  Check 'mouse_wheel 生效' (IsOk (Resp 'mouse_wheel' @{ id = $id; deltaY = 200 }))
  Check 'mouse_down 生效' (IsOk (Resp 'mouse_down' @{ id = $id; button = 'left' }))
  Check 'mouse_up 生效' (IsOk (Resp 'mouse_up' @{ id = $id; button = 'left' }))

  # ---------- 截图与 PDF ----------
  Section '截图与 PDF'
  $shot = Join-Path $outDir 'shot.png'
  $pdf = Join-Path $outDir 'page.pdf'
  Remove-Item $shot, $pdf -Force -ErrorAction SilentlyContinue
  Check 'screenshot 落盘' ((IsOk (Resp 'screenshot' @{ id = $id; path = $shot; fullPage = 'true' })) -and (Test-Path $shot))
  Check 'pdf 落盘' ((IsOk (Resp 'pdf' @{ id = $id; path = $pdf })) -and (Test-Path $pdf))
  Check 'screenshot 不传 path 返回 base64' (((Field (Resp 'screenshot' @{ id = $id }) 'base64')).Length -gt 100)

  # ---------- Cookie 与本地存储 ----------
  Section 'Cookie 与本地存储'
  Check 'set_cookie 生效' (IsOk (Resp 'set_cookie' @{ id = $id; name = 'smoke'; value = '1'; url = $pageBase }))
  $r = Resp 'get_cookies' @{ id = $id; url = $pageBase }
  Check 'get_cookies 返回结构化字段' ((IsOk $r) -and ((Field $r 'count') -eq 1) -and ((($r.data.cookies)[0]).name -eq 'smoke') -and ((($r.data.cookies)[0]).domain -ne $null)) (Msg $r)
  Check 'clear_cookies 生效' ((IsOk (Resp 'clear_cookies' @{ id = $id })) -and ((Field (Resp 'get_cookies' @{ id = $id; url = $pageBase }) 'count') -eq 0))
  Check 'set_local_storage 生效' (IsOk (Resp 'set_local_storage' @{ id = $id; key = 'k1'; value = 'v1' }))
  Check '  读回 k1 = v1' ((Field (Resp 'get_local_storage' @{ id = $id; key = 'k1' }) 'value') -eq 'v1')
  Check '  不传 key 返回整个 localStorage' ((Field (Resp 'get_local_storage' @{ id = $id }) 'value') -like '*k1*')
  Check 'clear_local_storage 生效' ((IsOk (Resp 'clear_local_storage' @{ id = $id })) -and ((Field (Resp 'get_local_storage' @{ id = $id; key = 'k1' }) 'value') -eq $null))

  # ---------- 浏览器设置 ----------
  Section '浏览器设置'
  Check 'set_viewport 生效' ((Field (Resp 'set_viewport' @{ id = $id; width = 1024; height = 768 }) 'height') -eq 768)
  Check 'set_media dark 生效' ((IsOk (Resp 'set_media' @{ id = $id; colorScheme = 'dark' })) -and ((Js 'matchMedia("(prefers-color-scheme: dark)").matches') -eq $true))
  Check 'set_media light 生效' ((IsOk (Resp 'set_media' @{ id = $id; colorScheme = 'light' })) -and ((Js 'matchMedia("(prefers-color-scheme: dark)").matches') -eq $false))
  $r = Resp 'set_headers' @{ id = $id; headersJson = '{"X-Smoke":"1"}' }
  Check 'set_headers 解析 JSON' ((IsOk $r) -and ((($r.data.headers).'X-Smoke') -eq '1')) (Msg $r)
  Check 'set_offline true 生效' (IsOk (Resp 'set_offline' @{ id = $id; offline = 'true' }))
  Check 'set_offline false 生效' (IsOk (Resp 'set_offline' @{ id = $id; offline = 'false' }))
  Check 'set_geolocation 生效' (IsOk (Resp 'set_geolocation' @{ id = $id; latitude = 39.9; longitude = 116.4 }))

  # ---------- 弹窗与控制台 ----------
  Section '弹窗与控制台'
  Check 'get_dialog 初始为 null' ((Field (Resp 'get_dialog' @{ id = $id }) 'dialog') -eq $null)
  Check '点击触发弹窗的按钮' (IsOk (Resp 'click_element_by_index' @{ id = $id; index = $iAlert }))
  $r = Resp 'get_dialog' @{ id = $id }
  Check '  弹窗被自动确认并记录信息' ((IsOk $r) -and (((Field $r 'dialog')).message -eq '这是一个弹窗')) (Msg $r)
  Check 'set_dialog_behavior 生效' ((Field (Resp 'set_dialog_behavior' @{ id = $id; dismiss = 'true' }) 'dismiss') -eq $true)
  Check 'clear_console_logs 生效' (IsOk (Resp 'clear_console_logs' @{ id = $id }))
  Check 'reload 后控制台有日志' ((IsOk (Resp 'reload' @{ id = $id })) -and ((WaitLogs '*测试页已加载*' 5) -like '*测试页已加载*'))

  # ---------- 网络 ----------
  # 注意:页面自己发起的 fetch 在被 route mock 时,渲染进程的回调可能一直挂着不执行
  # (没有真实网络 IO 去唤醒它),所以这里用 execute_js 主动发起请求来做确定性断言。
  Section '网络'
  Check 'route mock 生效' (IsOk (Resp 'route' @{ id = $id; urlPattern = '**/api/ping'; action = 'mock'; body = '{"pong":false}'; status = 200 }))
  Resp 'clear_console_logs' @{ id = $id } | Out-Null
  Resp 'execute_js' @{ id = $id; body = "fetch('/api/ping').then(r=>r.json()).then(d=>console.log('MOCK:'+JSON.stringify(d))).catch(e=>console.log('MOCKERR:'+e))" } | Out-Null
  Check '  页面拿到 mock 数据' ((WaitLogs '*MOCK:{"pong":false}*' 5) -like '*MOCK:{"pong":false}*')
  Check 'route abort 生效' (IsOk (Resp 'route' @{ id = $id; urlPattern = '**/api/ping'; action = 'abort' }))
  Resp 'clear_console_logs' @{ id = $id } | Out-Null
  Resp 'execute_js' @{ id = $id; body = "fetch('/api/ping').then(r=>r.text()).then(t=>console.log('ABORTED:'+t)).catch(e=>console.log('ABORTEDERR:'+e))" } | Out-Null
  $logs = WaitLogs '*ABORTED*' 5
  Check '  被拦截的请求拿不到响应' (($logs -like '*ABORTEDERR*') -and ($logs -notlike '*ABORTED:{*'))
  Check 'get_requests 支持 filter' ((((Resp 'get_requests' @{ id = $id; filter = '/api/ping' })).data.requests).Count -ge 1)
  Check 'unroute 清空路由' (IsOk (Resp 'unroute' @{ id = $id }))

  # ---------- 批量指令(PTC) ----------
  # 一次请求里跑完「导航 + 取快照 + 动作 + 读取」,验证每步结果都能读到
  # 只支持 POST + JSON 请求体,GET/表单/bodyJson 一律不支持
  Section '批量指令 PTC'
  $batch = '{"id":' + $id + ',"commands":[{"go_to_url":{"url":"' + $pageBase + '/test.html"}},{"get_dom_text":{}},' +
    '{"check_element_by_index":{"index":' + $iChk + '}},{"is_checked":{"index":' + $iChk + '}},' +
    '{"get_element_text":{"index":' + $iBtn + '}}]}'
  $r = PostJson 'commands' $batch
  Check '一个批次跑完 5 条命令' ((IsOk $r) -and ((Field $r 'count') -eq 5) -and ((Field $r 'failed') -eq 0)) (Msg $r)
  Check '  批次里能读到 get_dom_text 的文本' (((($r.data.results)[1]).data.text) -like '*<button >点我/>*')
  Check '  批次里能读到 is_checked 的结果' ((($r.data.results)[3]).data.checked -eq $true)
  Check '  批次里能读到 get_element_text 的结果' ((($r.data.results)[4]).data.text -eq '点我')
  Check '  批次里每步都带 index/command/ok' `
    (((($r.data.results)[0]).command -eq 'go_to_url') -and ((($r.data.results)[4]).index -eq 4))

  # GET 已经不支持了:必须给出中文提示而不是让人以为是参数没传对
  $r = Resp 'commands' @{ id = $id; bodyJson = '[{"get_title":{}}]' }
  Check 'GET 被拒绝并给出中文提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*只支持 POST*')) (Msg $r)

  # 对象载荷 + stopOnError=false:一条失败不影响后面的命令
  $payload = '{"id":' + $id + ',"stopOnError":false,"commands":[{"get_title":{}},{"click_element_by_index":{"index":99999}},{"get_url":{}}]}'
  $r = PostJson 'commands' $payload
  Check 'stopOnError=false 继续执行后面的命令' ((-not (IsOk $r)) -and ((Field $r 'count') -eq 3) -and ((Field $r 'failed') -eq 1)) (Msg $r)
  Check '  失败步骤带中文原因' ((($r.data.results)[1]).msg -like '*索引越界*')
  Check '  失败之后的步骤仍然执行' ((($r.data.results)[2]).ok -eq $true)
  Check '  整体 msg 指出第几条失败' ((Msg $r) -like '*第 1 条命令*')

  # 对象载荷里带 id,查询串不用再传
  $r = PostJson 'commands' ('{"id":"' + $id + '","commands":[{"get_title":{}}]}')
  Check '对象载荷里可以带 id' ((IsOk $r) -and ((Field $r 'count') -eq 1)) (Msg $r)
  $r = PostJson 'commands' ('{"id":' + $id + ',"get_title":{}}')
  Check '只写一条命令的对象也接受' ((IsOk $r) -and ((Field $r 'count') -eq 1)) (Msg $r)

  # 默认 stopOnError=true:遇到失败就停,并标记 stopped
  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"click_element_by_index":{"index":99999}},{"get_title":{}}]}')
  Check '默认遇到失败即停止' ((-not (IsOk $r)) -and ((Field $r 'count') -eq 1) -and ((Field $r 'stopped') -eq $true)) (Msg $r)

  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"click_element_by_index":{}}]}')
  Check '批次里缺参数给中文原因而不是 500' ((-not (IsOk $r)) -and ((Msg $r) -like '*缺少参数 index*')) (Msg $r)
  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"no_such_command":{}}]}')
  Check '未知命令给出提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*不支持的命令*')) (Msg $r)
  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"commands":{}}]}')
  Check '不允许嵌套 commands' ((-not (IsOk $r)) -and ((Msg $r) -like '*不支持嵌套*')) (Msg $r)
  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"get_title":{},"get_url":{}}]}')
  Check '每项只能有一个键' ((-not (IsOk $r)) -and ((Msg $r) -like '*只能包含一个键*')) (Msg $r)

  # 其它载荷写法
  $r = PostJson 'commands' '[{"get_title":{}}]' ("id=" + $id)
  Check '数组体 + 查询串 id' ((IsOk $r) -and ((Field $r 'count') -eq 1)) (Msg $r)
  $r = PostJson 'commands' '[{"get_title":{}}]'
  Check '数组体缺 id 给中文提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*缺少参数 id*')) (Msg $r)
  $r = PostJson 'commands' 'hello'
  Check '非 JSON 体给中文提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*需要 JSON*')) (Msg $r)
  $r = PostJson 'commands' '{"id":'
  Check '坏 JSON 给中文提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*需要 JSON*')) (Msg $r)
  $r = PostJson 'commands' ''
  Check '空请求体给中文提示' ((-not (IsOk $r)) -and ((Msg $r) -like '*不能为空*')) (Msg $r)

  # 长计划:URL 写法会很长,POST 体不受影响
  $many = '{"id":' + $id + ',"commands":[' + ((1..20 | ForEach-Object { '{"get_title":{}}' }) -join ',') + ']}'
  $r = PostJson 'commands' $many
  Check 'POST 长计划 20 条命令' ((IsOk $r) -and ((Field $r 'count') -eq 20) -and ((Field $r 'failed') -eq 0)) (Msg $r)

  # 批量覆盖度:抽几个只有新版本才支持的命令
  $r = PostJson 'commands' ('{"id":' + $id + ',"commands":[{"get_console_logs":{}},{"get_element_count":{"selector":"button"}},{"is_visible":{"index":' + $iBtn + '}},{"get_cookies":{}},{"get_tabs":{}}]}')
  Check '新接口也能批量调用' ((IsOk $r) -and ((Field $r 'count') -eq 5) -and ((Field $r 'failed') -eq 0)) (Msg $r)
  Check '  批次里能读到 get_element_count' ((($r.data.results)[1]).data.count -eq 9)

  # ---------- 快照过期 ----------
  Section '快照过期与元素状态'
  $domText = [string](Field (Resp 'get_dom_text' @{ id = $id }) 'text')
  Check '重新取快照后仍能定位按钮' ((Idx '<button >点我/>') -ge 0)
  Check '点击隐藏按钮' (IsOk (Resp 'click_element_by_index' @{ id = $id; index = $iToggle }))
  Check '  按钮已隐藏' ((Js 'document.getElementById("btn").style.display') -eq 'none')
  Check '  旧索引的 is_visible = false' ((Field (Resp 'is_visible' @{ id = $id; index = $iBtn }) 'visible') -eq $false)
  Check '  旧索引仍可读文本(快照按 xpath 解析)' ((Field (Resp 'get_element_text' @{ id = $id; index = $iBtn }) 'text') -eq '点我')

  # ---------- 其它接口 ----------
  Section '其它接口'
  $r = Resp 'extract_structured_data' @{ id = $id; query = '测试'; extractLinks = 'true' }
  Check 'extract_structured_data 返回正文' ((IsOk $r) -and (((Field $r 'text')).Length -gt 10)) (Msg $r)
  Check '  正文里没有高亮序号' (-not (((Field $r 'text')) -match '(?m)^\d+$'))
  Check '  返回链接列表' (((($r.data.links)).Count) -ge 1)
  Check 'scroll 翻页' (IsOk (Resp 'scroll' @{ id = $id; down = 'true'; numPages = 1 }))
  $r = Resp 'get_dropdown_options' @{ id = $id; index = $iSel }
  Check 'get_dropdown_options 返回选项' ((IsOk $r) -and (((Field $r 'options')).Count -eq 3)) (Msg $r)
  Check 'select_dropdown_option 按文本选择' (IsOk (Resp 'select_dropdown_option' @{ id = $id; index = $iSel; text = '上海' }))
  Check '  选中值为 sh' ((Field (Resp 'get_element_value' @{ id = $id; index = $iSel }) 'value') -eq 'sh')
  Check 'go_back 生效' (IsOk (Resp 'go_back' @{ id = $id }))
  Check 'go_forward 生效' (IsOk (Resp 'go_forward' @{ id = $id }))

  # ---------- 错误处理 ----------
  Section '错误处理'
  $r = Resp 'get_tabs' @{ id = '999999999' }
  Check '实例不存在返回中文原因' ((-not (IsOk $r)) -and ((Msg $r) -like '*没有找到对应的浏览器实例*')) (Msg $r)
  $r = Resp 'go_to_url' @{ id = $id; url = 'http://127.0.0.1:19999/nope' }
  Check '地址打不通返回中文原因' ((-not (IsOk $r)) -and ((Msg $r) -like '*失败*')) (Msg $r)
  $r = Resp 'send_keys' @{ id = $id; keys = 'NotAKey' }
  Check '非法按键返回中文原因' ((-not (IsOk $r)) -and ((Msg $r) -like '*失败*')) (Msg $r)
  # 选择器类接口失败时不能说「重新取快照索引」,那是按索引的提示(实测隐藏元素 #kw 就是这条路)
  $r = Resp 'click_element_by_selector' @{ id = $id; selector = '#no-such-thing' }
  Check '选择器找不到元素给出定位提示(不提索引)' `
    ((-not (IsOk $r)) -and ((Msg $r) -like '*没匹配到可操作的元素*') -and ((Msg $r) -notlike '*get_dom_text*')) (Msg $r)

  # ---------- 关闭 ----------
  Section '关闭'
  Check 'close 生效' (IsOk (Resp 'close' @{ id = $id }))
  Check '  关闭后实例不存在' ((Msg (Resp 'get_tabs' @{ id = $id })) -like '*没有找到对应的浏览器实例*')
  $id = $null

  if ($script:fail -gt 0) { $exitCode = 1 }
} catch {
  Write-Host ''
  Write-Host ("致命错误: " + $_.Exception.Message) -ForegroundColor Red
  $exitCode = 2
} finally {
  if ($id) { Resp 'close' @{ id = $id } | Out-Null }
  if ($static -and -not $static.HasExited) {
    Stop-Process -Id $static.Id -Force -ErrorAction SilentlyContinue
  }
}

Write-Host ''
Write-Host ("通过 " + $script:pass + " 项,失败 " + $script:fail + " 项") -ForegroundColor $(if ($script:fail -gt 0) { 'Red' } else { 'Green' })
if ($script:fail -gt 0) {
  Write-Host '失败清单:' -ForegroundColor Red
  foreach ($name in $script:failures) { Write-Host ("  - " + $name) -ForegroundColor Red }
}
if ($exitCode -eq 0) { Write-Host '截图与 PDF 留在 out/ 目录,可直接人工核对。' -ForegroundColor DarkGray }

# 用 -File 运行时正常设置退出码;用 scriptblock 方式运行时不能用 exit(会把调用方的 shell 一起退掉)
if ($PSScriptRoot) { exit $exitCode }
$global:LASTEXITCODE = $exitCode
