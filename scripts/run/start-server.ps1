<#
.SYNOPSIS
  以后台方式启动 deepseek-browser-use 服务(脱离当前进程树,宿主回收子进程时不会被带走)

.DESCRIPTION
  为什么需要这个脚本:浏览器登录态是**会话级**的 —— 服务进程被杀,它启动的浏览器会跟着退出,
  而 12306 这类站点的登录 cookie 是 session cookie,于是人工得重新登录一次。实测踩过:agent 的
  后台任务被回收时带走了整棵进程树,mvn / java / Chrome 一起消失,人工白登录一次。

  所以这里优先用 WMI(Win32_Process.Create)创建进程:该进程由 WmiPrvSE 创建,不在当前进程的
  Job 对象里,宿主 taskkill /T 自己的进程树时不会带走它;拿不到 WMI 时退回 Start-Process(至少
  能脱离当前控制台,但可能仍在宿主的进程树里 —— 这时脚本会明确提示)。

  启动之后会等 /playwright/health 通过,并把实际生效的引擎与 profile 目录打出来。

  关于「启动后出现一个黑窗口」:`java` / `mvn.cmd` / `cmd.exe` 都是**控制台子系统**程序,Windows
  启动控制台程序时必然给它分配一个控制台窗口,而且**窗口寿命 = 进程寿命** —— 服务跑多久,那个窗口
  就停多久。本脚本把输出重定向进日志文件,所以那个窗口里什么都不显示,看起来像卡住的残留窗口。
  这里用 WMI 的 `Win32_ProcessStartup.ShowWindow = SW_HIDE` 让它一开始就不显示(窗口仍存在,只是
  不可见)。想看实时输出请用 `Get-Content -Wait logs\server\server-<端口>.out.log`,或加 `-ShowConsole`。
  注意:**别直接叉掉那个窗口** —— 关掉它等于杀掉服务,连带浏览器与 session cookie 型的登录态一起没。

.PARAMETER Port
  监听端口,默认 10049(同时决定托管 profile 的默认目录 shared-<端口>)。

.PARAMETER Engine
  引擎:chromium(默认)/ chrome / edge / firefox。传了就覆盖服务端配置。

.PARAMETER ProfileDir
  托管 profile 目录。不传就用默认(按端口派生)。**换目录等于换一套登录态**。

.PARAMETER Jar
  用发行版 jar 启动(而不是开发态的 mvn spring-boot:run)。

.PARAMETER TimeoutSeconds
  等健康检查的最长秒数,默认 90(首次启动要拉起浏览器,慢一点正常)。

.PARAMETER Force
  端口上已经有服务时,不再直接返回,而是照常再起一个(通常不需要)。

.EXAMPLE
  pwsh -File scripts/run/start-server.ps1

.EXAMPLE
  # 指定引擎与 profile(复用某份已登录的会话)
  pwsh -File scripts/run/start-server.ps1 -Engine chromium -ProfileDir C:\Users\me\.config\browseruse\profiles\shared
#>
param(
  [int]$Port = 10049,
  [ValidateSet('chromium', 'chrome', 'edge', 'firefox')][string]$Engine,
  [string]$ProfileDir,
  [string]$Jar,
  [int]$TimeoutSeconds = 90,
  [switch]$Force,
  # 调试用:让后台那层 cmd 窗口显示出来(默认隐藏,见脚本开头关于「黑窗口」的说明)
  [switch]$ShowConsole
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runDir = Join-Path $repoRoot 'logs\server'
$serverDir = Join-Path $repoRoot 'playwright-server'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$healthUrl = "http://127.0.0.1:$Port/playwright/health"
$pidFile = Join-Path $runDir "server-$Port.pid"
$outLog = Join-Path $runDir "server-$Port.out.log"
$errLog = Join-Path $runDir "server-$Port.err.log"
$launcher = Join-Path $runDir "run-$Port.cmd"

function Test-Health {
  param([int]$TimeoutSec = 3)
  try {
    $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec $TimeoutSec
    return ($response.StatusCode -eq 200 -and $response.Content -match '"ok"\s*:\s*true')
  } catch {
    return $false
  }
}

# 健康检查失败时从日志里读出「真正的原因」,而不是笼统地让人「再等一会儿」
# (与 macOS/Linux 的 start-server.sh 对齐:运行的 JDK 比编译用的版本低 -> UnsupportedClassVersionError;
#  java 本身起不来 -> 架构不匹配等)
function Write-HealthFailureDiagnosis {
  param([string]$OutLog, [string]$ErrLog)
  $text = ''
  if (Test-Path $OutLog) { $text = $text + (Get-Content -Path $OutLog -Raw -ErrorAction SilentlyContinue) }
  if (Test-Path $ErrLog) { $text = $text + (Get-Content -Path $ErrLog -Raw -ErrorAction SilentlyContinue) }
  if ($text -match 'UnsupportedClassVersionError') {
    Write-Host '  [真正的原因] 运行这个服务的 JDK 版本太低:它是按 Java 21 编译的(class file version 65.0)。'
    Write-Host '               把 JAVA_HOME 指向 JDK 21+ 再启动。'
    return
  }
  if (($text -match 'rosetta error') -or ($text -match 'Abort trap')) {
    Write-Host '  [真正的原因] 这个 java 在本机根本跑不起来 —— 常见于 JDK 与本机架构不匹配'
    Write-Host '               (x64 / arm64、32 位 / 64 位不一致)。换一个与本机架构一致的 JDK 21+。'
    return
  }
  if ($text -match 'Address already in use') {
    Write-Host '  [真正的原因] 端口被占用:换一个 -Port,或先停掉占用它的进程。'
    return
  }
  if ($text -match 'BUILD FAILURE') {
    Write-Host '  [真正的原因] Maven 构建/启动失败,真正的错就在上面日志里的 [ERROR] 行。'
    return
  }
  if ($text -match 'Downloading') {
    Write-Host '  [正在下载浏览器] 日志里有 Downloading … —— 这是 Playwright 在下载它管理的浏览器'
    Write-Host '               (首次使用或 Playwright 升级后,数百 MB、可能十几分钟),不是失败:下完再跑一次。'
    return
  }
  Write-Host "提示:端口可能没被 -Dserver.port 覆盖(看日志里的 'Server port:'),也可能这次启动确实慢 —— 再等一会儿看看。"
}

if ((Test-Health) -and -not $Force) {
  Write-Host "服务已经在 $Port 上跑着(健康检查通过),不重复启动。"
  Write-Host "要重启:先 pwsh -File scripts/run/stop-server.ps1 -Port $Port"
  exit 0
}

# ---- 前置检查:要用的那个 java 到底能不能跑 ----------------------------------
# 与 macOS/Linux 的 start-server.sh 对齐:JAVA_HOME 指向与本机架构不匹配的 JDK 时 java 自己起不来,
# 以前脚本会照常启动、等满超时,最后给一句「端口可能没被覆盖」,把人引到完全无关的方向。
# 探的必须是「真正会跑起来的那个 java」:发行版那条路的启动器写的是 `java`(由 PATH 决定),
# 开发态的 mvn.cmd 优先用 JAVA_HOME。
$javaExe = $null
if ($Jar) {
  $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($javaCmd) { $javaExe = $javaCmd.Source }
}
if (-not $javaExe -and $env:JAVA_HOME) {
  $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
  if (Test-Path $candidate) { $javaExe = $candidate }
}
if (-not $javaExe) {
  $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($javaCmd) { $javaExe = $javaCmd.Source }
}
if (-not $javaExe) {
  Write-Host '✗ 找不到 java:请把 JDK 21+ 加进 PATH,或设置 JAVA_HOME。'
  exit 1
}
# 注意:`java -version` 写的是 stderr,而本脚本开头把 $ErrorActionPreference 设成了 Stop ——
# 在 PS 5.1 下原生命令往 stderr 写会被当成终止性错误,所以这里临时放宽一下。
$savedEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionLine = (& $javaExe -version 2>&1 | Select-Object -First 1)
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = $savedEap
if ($javaExitCode -ne 0 -or -not $javaVersionLine) {
  Write-Host "✗ 这个 java 跑不起来:$javaExe"
  Write-Host '  先确认 JAVA_HOME 指向的 JDK 与本机架构一致(x64 / arm64、32 位 / 64 位不一致就是这种表现)。'
  Write-Host '  改法:换一个 JDK 21+。'
  exit 1
}
$javaMajor = 0
if ("$javaVersionLine" -match 'version "1\.(\d+)') {
  $javaMajor = [int]$Matches[1]
} elseif ("$javaVersionLine" -match 'version "(\d+)') {
  $javaMajor = [int]$Matches[1]
}
if ($javaMajor -gt 0 -and $javaMajor -lt 21) {
  if ($Jar) {
    Write-Warning "当前 java 是 $javaVersionLine,低于本仓库要求的 Java 21+;老 jar 可能仍能跑,跑不起来请看下面的日志归因。"
  } else {
    Write-Host "✗ java 版本太低:$javaVersionLine($javaExe)"
    Write-Host '  开发态要编译本仓库(pom.xml 里 java.version=21,产物是 class file version 65.0),'
    Write-Host '  低于 21 的 JDK 会在启动时报 UnsupportedClassVersionError。'
    Write-Host '  改法:换一个 JDK 21+ 再启动。'
    exit 1
  }
}

# ---- 拼启动命令 ----------------------------------------------------------
$jvmArgs = New-Object System.Collections.Generic.List[string]
$jvmArgs.Add("-Dserver.port=$Port") | Out-Null
if ($Engine) { $jvmArgs.Add("-Dbrowser.engine=$Engine") | Out-Null }
if ($ProfileDir) { $jvmArgs.Add("-Dbrowser.profileDir=$ProfileDir") | Out-Null }
$jvmArgLine = ($jvmArgs -join ' ')

if ($Jar) {
  $jarPath = (Resolve-Path $Jar).Path
  $inner = "java $jvmArgLine -jar `"$jarPath`""
  $workDir = $repoRoot
} else {
  $mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue)
  if (-not $mvn) { $mvn = (Get-Command mvn -ErrorAction SilentlyContinue) }
  if (-not $mvn) { throw '找不到 mvn,要么把它加进 PATH,要么用 -Jar 指定发行版 jar' }
  # 工作目录放在仓库根:data/ 与 logs/ 都落在仓库里,方便事后翻截图与追踪日志
  $inner = "`"$($mvn.Source)`" spring-boot:run `"-Dspring-boot.run.jvmArguments=$jvmArgLine`" " +
           "`"-Dspring-boot.run.workingDirectory=$repoRoot`""
  $workDir = $serverDir
}

$launcherBody = @"
@echo off
rem 由 scripts/run/start-server.ps1 生成:端口 $Port,引擎 $(if ($Engine) { $Engine } else { '默认' })
cd /d "$workDir"
$inner 1>"$outLog" 2>"$errLog"
"@
Set-Content -Path $launcher -Value $launcherBody -Encoding ASCII

# ---- 创建进程:优先 WMI(脱离 Job + 隐藏窗口),退回 Start-Process ---------
#
# 为什么要显式隐藏窗口:`java` / `mvn.cmd` / `cmd.exe` 都是**控制台子系统**程序,Windows 启动
# 控制台程序时会给它分配一个控制台窗口 —— 就是那个黑窗口。它的寿命 = 进程的寿命,所以服务跑多久
# 它就停多久。我们的输出已经重定向到日志文件了,于是那个窗口是**全黑的**,看着像卡死的残留窗口。
# 由父进程给的 STARTUPINFO 里 wShowWindow=SW_HIDE 就能让它一开始就不显示(窗口仍存在,
# 只是不可见;要看实时日志请用 Get-Content -Wait logs\server\server-<端口>.out.log,或者加 -ShowConsole)。
$mode = 'wmi'
$processId = $null
$showWindow = if ($ShowConsole) { [uint16]1 } else { [uint16]0 }   # 1=SW_SHOWNORMAL, 0=SW_HIDE
try {
  $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = $showWindow }
  $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
    -Arguments @{ CommandLine = "cmd.exe /c `"$launcher`""; CurrentDirectory = $workDir
                  ProcessStartupInformation = $startup } -ErrorAction Stop
  if ($created.ReturnValue -ne 0) { throw "WMI 返回 $($created.ReturnValue)" }
  $processId = [int]$created.ProcessId
} catch {
  Write-Warning "WMI(带隐藏窗口)创建进程失败:$($_.Exception.Message) —— 退回 Start-Process -WindowStyle Hidden"
  try {
    $mode = 'start-process'
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$launcher`"" -WorkingDirectory $workDir `
      -WindowStyle Hidden -PassThru
    $processId = $p.Id
  } catch {
    $mode = 'wmi-visible'
    $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
      -Arguments @{ CommandLine = "cmd.exe /c `"$launcher`""; CurrentDirectory = $workDir }
    $processId = [int]$created.ProcessId
  }
}

@{
  port      = $Port
  pid       = $processId
  mode      = $mode
  engine    = $Engine
  profileDir = $ProfileDir
  startedAt = (Get-Date).ToString('s')
  launcher  = $launcher
} | ConvertTo-Json | Set-Content -Path $pidFile -Encoding UTF8

Write-Host "已启动(pid=$processId,方式=$mode),等健康检查…"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  if (Test-Health) { $healthy = $true; break }
  Start-Sleep -Milliseconds 800
}

if (-not $healthy) {
  Write-Warning "等了 $TimeoutSeconds 秒,http://127.0.0.1:$Port 还没起来。最后 20 行日志:"
  if (Test-Path $outLog) { Get-Content $outLog -Tail 20 }
  if (Test-Path $errLog) { Get-Content $errLog -Tail 20 }
  Write-HealthFailureDiagnosis -OutLog $outLog -ErrLog $errLog
  exit 1
}

$config = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/playwright/config" -UseBasicParsing -TimeoutSec 10).Content
Write-Host "服务已就绪:http://127.0.0.1:$Port"
try {
  $parsed = $config | ConvertFrom-Json
  Write-Host ("  引擎={0}  托管 profile={1}" -f $parsed.data.engine, $parsed.data.profileDir.resolved)
} catch {
  Write-Host "  (配置读不出来,原样返回:$($config.Substring(0, [Math]::Min(200, $config.Length))))"
}
Write-Host "  pid 文件:$pidFile"
Write-Host "  停服务:pwsh -File scripts/run/stop-server.ps1 -Port $Port"
exit 0
