param(
  [Parameter(Mandatory = $true)][string]$Path,
  [Parameter(Mandatory = $true)][string]$Out,
  [string]$Language = "zh-Hans-CN"
)

# Windows 自带 OCR(Windows.Media.Ocr)读图上的文字
#
# 为什么用 PowerShell 而不是纯 Java:Windows.Media.Ocr 是 WinRT API,Java 侧要调它得走 JNI/COM,
# 而系统自带的 Windows PowerShell 5.1 能直接以 ContentType=WindowsRuntime 的方式加载它,零依赖。
# 识别中文需要系统装了对应语言包(默认 zh-Hans-CN;装了英文包时 zh 的识别结果会退化成拉丁字母片段)。
#
# 结果写进 -Out 指定的文件(UTF-8),而不是往 stdout 打 —— 中文在管道里的编码在不同代码页下不可靠。
# 退出码:0 成功 / 2 这台机器上没有可用的 OCR 引擎 / 1 其它错误。

$ErrorActionPreference = "Stop"

try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

  $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
  $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
  $null = [Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

  # WinRT 的 IAsyncOperation<T> 在 PowerShell 里不能直接 await,套一层 AsTask().Wait()
  $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
      $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
      $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

  function Await($operation, $resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait(-1) | Out-Null
    return $task.Result
  }

  $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
  $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
  $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

  $engine = $null
  if ($Language) {
    try {
      $lang = New-Object Windows.Globalization.Language $Language
      $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
    } catch {
      $engine = $null
    }
  }
  if (-not $engine) {
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
  }
  if (-not $engine) {
    $available = ([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag }) -join ','
    [System.IO.File]::WriteAllText($Out, "NO_ENGINE:$available", [System.Text.Encoding]::UTF8)
    exit 2
  }

  $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
  [System.IO.File]::WriteAllText($Out, $result.Text, [System.Text.Encoding]::UTF8)
  exit 0
} catch {
  try {
    [System.IO.File]::WriteAllText($Out, "ERROR:" + $_.Exception.Message, [System.Text.Encoding]::UTF8)
  } catch {
    # 连错误都写不出去就只能靠退出码了
  }
  exit 1
}
