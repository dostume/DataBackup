$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$base = 'https://cdn.jsdelivr.net/gh/XayahSuSuSu/Android-DataBackup@master/'
$proxy = 'http://127.0.0.1:60825'
$root = Join-Path $PSScriptRoot 'repo'

$listPath = Join-Path $PSScriptRoot 'filelist.txt'
$list = Get-Content $listPath | Where-Object { $_ -like 'source/*' }
$total = $list.Count
$i = 0
$fail = @()
$ok = 0

foreach ($p in $list) {
    $i++
    $segs = $p -split '/'
    $encoded = ($segs | ForEach-Object { [System.Uri]::EscapeDataString($_) }) -join '/'
    $url = $base + $encoded
    $dest = Join-Path $root ($segs -join '\')
    $dir = Split-Path $dest -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    try {
        Invoke-WebRequest -Uri $url -Proxy $proxy -OutFile $dest -UseBasicParsing -TimeoutSec 60
        $ok++
    } catch {
        $fail += $p
    }
    if (($i % 50) -eq 0 -or $i -eq $total) {
        Write-Output ("[{0}/{1}] ok={2} fail={3}" -f $i, $total, $ok, $fail.Count)
    }
}

Write-Output '=== DONE ==='
Write-Output ("ok={0} fail={1}" -f $ok, $fail.Count)
if ($fail.Count -gt 0) {
    $fail | Out-File (Join-Path $PSScriptRoot 'download_failures.txt') -Encoding utf8
    Write-Output 'failures written to download_failures.txt'
}
