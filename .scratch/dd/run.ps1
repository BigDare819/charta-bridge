param([string]$Mode = "fast", [string]$Only = "", [int]$Deals = 0)
$root = $PSScriptRoot
$out = Join-Path $root "out"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$files = @(Get-ChildItem (Join-Path $root "src") -Filter *.java | ForEach-Object { $_.FullName })
$files += (Join-Path $root "..\..\src\main\java\dev\bridge\game\BridgeDoubleDummy.java")
& javac -encoding UTF-8 -d $out $files 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED"; exit 1 }
$args2 = @()
if ($Mode -eq "slow") { $args2 += "--slow" }
if ($Only) { $args2 += "--only=$Only" }
& java -cp $out Main @args2
exit $LASTEXITCODE
