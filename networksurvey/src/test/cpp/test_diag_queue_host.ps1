param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/diag-queue-native'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$bin = Join-Path $Sdk 'ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin'
$source = Get-Content -LiteralPath (Join-Path $repo 'networksurvey/src/main/cpp/root_diag.c') -Raw
function Block([string]$Start, [string]$End) {
    $first = $source.IndexOf($Start, [StringComparison]::Ordinal)
    if ($first -lt 0) { throw "Missing production block: $Start" }
    $last = $source.IndexOf($End, $first, [StringComparison]::Ordinal)
    if ($last -lt 0) { throw "Missing end of production block: $Start" }
    $source.Substring($first, $last - $first)
}
$utilities = @(
    (Block '#define NA_I ' "`n"),
    (Block '#define NA_F ' "`n"),
    (Block 'static inline uint16_t rd16' '/* MSB-first bit reader'),
    (Block 'struct jb {' '#define QMDL_LIMIT ')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'diag_epoch_util_under_test.inc'), $utilities)
[IO.File]::WriteAllText((Join-Path $target 'diag_epoch_pdsch_under_test.inc'),
    (Block 'struct pdsch_acc {' '/* LTE scheduling layouts'))
[IO.File]::WriteAllText((Join-Path $target 'diag_epoch_worker_under_test.inc'),
    (Block 'static void nr_statistics_reset(' 'static void voice_update('))
[IO.File]::WriteAllText((Join-Path $target 'string.h'),
    "#include <stddef.h>`nvoid *memset(void *, int, size_t);`nvoid *memcpy(void *, const void *, size_t);`n")
$def = Join-Path $target 'msvcrt.def'
[IO.File]::WriteAllText($def, "LIBRARY msvcrt.dll`nEXPORTS`n_snprintf`n_vsnprintf`nmemset`nmemcpy`n")
$lib = Join-Path $target 'msvcrt.lib'
& "$bin/llvm-dlltool.exe" -m i386:x86-64 -d $def -l $lib
if ($LASTEXITCODE) { throw 'CRT import generation failed' }
$stackDef = Join-Path $target 'kernelbase.def'
$stackLib = Join-Path $target 'kernelbase.lib'
# The production worker has a 64 KiB local buffer; keep Windows stack probing enabled.
[IO.File]::WriteAllText($stackDef, "LIBRARY kernelbase.dll`nEXPORTS`n__chkstk`n")
& "$bin/llvm-dlltool.exe" -m i386:x86-64 -d $stackDef -l $stackLib
if ($LASTEXITCODE) { throw 'Stack-probe import generation failed' }
foreach ($suite in 'diag_queue', 'diag_epoch') {
    $obj = Join-Path $target "${suite}_test.obj"
    $dll = Join-Path $target "${suite}_test.dll"
    & "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin `
        -DDIAG_QUEUE_HOST_DLL -Dsnprintf=_snprintf -c -I $target `
        (Join-Path $PSScriptRoot "${suite}_test.c") -o $obj
    if ($LASTEXITCODE) { throw "$suite host compilation failed" }
    & "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj $lib $stackLib
    if ($LASTEXITCODE) { throw "$suite host linking failed" }
}
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class DiagQueueHostTests {
    [DllImport(@"$(Join-Path $target 'diag_queue_test.dll')", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_diag_queue();
    [DllImport(@"$(Join-Path $target 'diag_epoch_test.dll')", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_diag_epoch_worker();
}
"@
$result = [DiagQueueHostTests]::test_diag_queue()
if ($result) { throw "DIAG queue test failed at line $result" }
Write-Output 'PASS DIAG queue: FIFO, overflow, wraparound, priority isolation, epoch timestamps and drop accounting'
$result = [DiagQueueHostTests]::test_diag_epoch_worker()
if ($result) { throw "DIAG epoch worker test failed at line $result" }
Write-Output 'PASS production DIAG worker: cross-cell filtering, same-cell retention, untimed/equal packets, wrapped backlog, worker reset and A-B-A cumulative baseline reset'
