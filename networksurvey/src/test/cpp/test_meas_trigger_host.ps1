param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/meas-trigger-native'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$bin = Join-Path $Sdk 'ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin'
$diag = Get-Content -LiteralPath (Join-Path $repo 'networksurvey/src/main/cpp/root_diag.c') -Raw
$dec = Get-Content -LiteralPath (Join-Path $repo 'networksurvey/src/main/cpp/sigdec.c') -Raw
function Block([string]$Text, [string]$Start, [string]$End, [string]$Where) {
    $first = $Text.IndexOf($Start, [StringComparison]::Ordinal)
    if ($first -lt 0) { throw "Missing production block in ${Where}: $Start" }
    $last = $Text.IndexOf($End, $first, [StringComparison]::Ordinal)
    if ($last -lt 0) { throw "Missing end of production block in ${Where}: $Start" }
    $Text.Substring($first, $last - $first)
}
$utilities = @(
    (Block $diag '#define NA_F ' "`n" 'root_diag.c'),
    (Block $dec 'enum sd_meas_quantity' 'struct sd_meas_object' 'sigdec.c'),
    (Block $dec 'static int sd_enum_num' 'static void sd_meas_integer' 'sigdec.c'),
    (Block $diag '/* Measurement trigger thresholds' '/* The measId linking' 'root_diag.c')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'meas_trigger_under_test.inc'), $utilities)
$obj = Join-Path $target 'meas_trigger_test.obj'
$dll = Join-Path $target 'meas_trigger_test.dll'
& "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin `
    -c -I $target (Join-Path $PSScriptRoot 'meas_trigger_test.c') -o $obj
if ($LASTEXITCODE) { throw 'Measurement trigger host compilation failed' }
& "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj
if ($LASTEXITCODE) { throw 'Measurement trigger host linking failed' }
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class MeasTriggerHostTests {
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_meas_trigger();
}
"@
$result = [MeasTriggerHostTests]::test_meas_trigger()
if ($result) { throw "Measurement trigger host test failed at line $result" }
Write-Output 'PASS measurement trigger conversions: LTE/NR RSRP/RSRQ/SINR ranges to dB and ENUMERATED names (hysteresis/timeToTrigger/a3-Offset)'