param([string]$ProductionSource)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$taskBuild = Join-Path $taskRoot '.temp/band-lock-host-tests'
New-Item -ItemType Directory -Path $taskBuild -Force | Out-Null
$taskClang = Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$taskLinker = Join-Path (Split-Path -Parent $taskClang) 'ld.lld.exe'
if (!$ProductionSource) { $ProductionSource = Join-Path $taskRoot 'networksurvey/src/main/cpp/band_lock.c' }
$taskSource = Get-Content -LiteralPath $ProductionSource -Raw
function Select-TaskFunctionBlock([string]$Start, [string]$End) {
    $taskFirst = $taskSource.IndexOf($Start, [StringComparison]::Ordinal)
    if ($taskFirst -lt 0) { throw "Missing production function marker: $Start" }
    $taskLast = $taskSource.IndexOf($End, $taskFirst, [StringComparison]::Ordinal)
    if ($taskLast -lt 0) { throw "Missing production function end marker: $End" }
    $taskSource.Substring($taskFirst, $taskLast - $taskFirst)
}
$taskFunctions = @(
    (Select-TaskFunctionBlock 'static int backup_mask(' 'static int put_file('),
    (Select-TaskFunctionBlock 'static int lte_band_command(' 'static int backup_may_exist('),
    (Select-TaskFunctionBlock 'static int backup_may_exist(' 'static int cell_list(')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $taskBuild 'band_lock_backup_under_test.inc'), $taskFunctions)
foreach ($taskSuite in @('protocol', 'backup')) {
    $taskDll = Join-Path $taskBuild "band_lock_${taskSuite}_test.dll"
    $taskObject = Join-Path $taskBuild "band_lock_${taskSuite}_test.obj"
    & $taskClang --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin -c -I $taskBuild (Join-Path $PSScriptRoot "band_lock_${taskSuite}_test.c") -o $taskObject
    if ($LASTEXITCODE) { throw "Native $taskSuite host test compilation failed." }
    & $taskLinker -flavor link /dll /noentry /nodefaultlib "/out:$taskDll" $taskObject
    if ($LASTEXITCODE) { throw "Native $taskSuite host test linking failed." }
}
$taskBackupDll = Join-Path $taskBuild 'band_lock_backup_test.dll'
$taskProtocolDll = Join-Path $taskBuild 'band_lock_protocol_test.dll'
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class BandLockHostTests {
    [DllImport(@"$taskBackupDll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_band_lock_backup();
    [DllImport(@"$taskProtocolDll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_band_lock_protocol();
}
"@
$taskProtocolResult = [BandLockHostTests]::test_band_lock_protocol()
if ($taskProtocolResult) { throw "Protocol host test failed at line $taskProtocolResult." }
Write-Output 'PASS band lock protocol host tests'
$taskBackupResult = [BandLockHostTests]::test_band_lock_backup()
if ($taskBackupResult) { throw "Backup host test failed at line $taskBackupResult." }
Write-Output 'PASS band lock backup, persistence failure, and restore host tests'
