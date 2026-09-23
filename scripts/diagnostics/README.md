# CNIPA browser comparison

Current project version: Playwright 1.53.0, with its matching Firefox 139.0. For login pages that become blank, try the Firefox probe as a diagnostic alternative; see [recorded results](RESULTS-2026-09-22.md).

Run from the repository root in PowerShell. Uses a fresh context and a visible browser, follows the two application links from the CNIPA home page, and samples the final tab every five seconds for 135 seconds after clicking the application-system entry. Logs navigation HTTP responses, URL/body/input state, console warnings and errors, and screenshots at 10 and 135 seconds. Does not enter credentials or submit a login.

```powershell
mvn -pl playwright-server test-compile dependency:build-classpath '-Dmdep.outputFile=target/diagnosis-classpath.txt'
$probeClasspath = Get-Content -Raw playwright-server/target/diagnosis-classpath.txt
java -cp $probeClasspath com.microsoft.playwright.CLI install chromium firefox webkit
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
New-Item -ItemType Directory -Force playwright-server/target/cnipa-diagnosis | Out-Null
javac -proc:none -encoding UTF-8 -cp $probeClasspath -d playwright-server/target/cnipa-diagnosis scripts/diagnostics/CnipaBrowserProbe.java
foreach ($engine in @('chromium','chrome','edge','firefox','webkit')) {
  java '-Dfile.encoding=UTF-8' -cp "playwright-server/target/cnipa-diagnosis;$probeClasspath" CnipaBrowserProbe $engine
}
```

Chrome and Edge use locally installed stable channels. WebKit is Playwright's test browser, not a Safari installation. Keep the test windows open until the program closes them. Output is written beneath `playwright-server/target/cnipa-diagnosis/playwright-1.53-<engine>/`.
