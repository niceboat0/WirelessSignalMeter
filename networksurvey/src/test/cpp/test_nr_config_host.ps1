param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/nr-config-native'
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
$blocks = @(
    (Block '#define NA_I ' "`n"),
    (Block '#define NA_F ' "`n"),
    (Block '#define NR_CA_STATE_CODE ' "`n"),
    (Block 'static inline uint16_t rd16' '/* MSB-first bit reader'),
    (Block 'struct jb {' 'static void jb_emit('),
    (Block 'static void jb_emit(' "`n"),
    (Block 'static int pos_fits(' "`n"),
    (Block 'struct b825_l {' '/* --- 0xB887')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'nr_config_under_test.inc'), $blocks)
$snr = @(
    (Block 'static inline int32_t rds32' 'static void nr_set_sinr'),
    (Block 'static void parse_nr_b8d8(' '/* --- 0xB8DD')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'nr_snr_under_test.inc'), $snr)
$def = Join-Path $target 'msvcrt.def'
[IO.File]::WriteAllText($def, "LIBRARY msvcrt.dll`nEXPORTS`n_snprintf`n_vsnprintf`nstrcmp`nlog10`nmemset`n")
$lib = Join-Path $target 'msvcrt.lib'
& "$bin/llvm-dlltool.exe" -m i386:x86-64 -d $def -l $lib
if ($LASTEXITCODE) { throw 'CRT import generation failed' }
$obj = Join-Path $target 'nr_config_test.obj'
$dll = Join-Path $target 'nr_config_test.dll'
& "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin -mno-stack-arg-probe -Dsnprintf=_snprintf -c -I $target (Join-Path $PSScriptRoot 'nr_config_test.c') -o $obj
if ($LASTEXITCODE) { throw 'NR config host compilation failed' }
& "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj $lib
if ($LASTEXITCODE) { throw 'NR config host linking failed' }
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class NrConfigTest {
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr nr_config_feed(int count, int tailDelta, int truncate, long now);
    public static string Feed(int count, int tailDelta, int truncate, long now) {
        return Marshal.PtrToStringAnsi(nr_config_feed(count, tailDelta, truncate, now));
    }
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr nr_ca_feed(int version, int count, int lengthDelta, int fault);
    public static string Ca(int version, int count, int lengthDelta, int fault) {
        return Marshal.PtrToStringAnsi(nr_ca_feed(version, count, lengthDelta, fault));
    }
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr nr_snr_feed(int cc, int pci, int version, int trs, int length, int sample, long now);
    public static string Snr(int cc, int pci, int version, int trs, int length, int sample, long now) {
        return Marshal.PtrToStringAnsi(nr_snr_feed(cc, pci, version, trs, length, sample, now));
    }
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int nr_snr_primary_calls();
}
"@
foreach ($count in 4,16) {
    $config = [NrConfigTest]::Feed($count, 0, 0, 1000) | ConvertFrom-Json
    if ($config.ncells -ne $count -or $config.cells.Count -ne $count -or
        $config.cells[-1].cc -ne ($count - 1) -or $config.cells[-1].ssb_arfcn -ne (634990 + 1000 * ($count - 1))) {
        throw "Truncated or damaged $count-CC configuration"
    }
}
if ([NrConfigTest]::Feed(16, 0, 0, 1100)) { throw 'Identical configuration was not suppressed' }
$change = [NrConfigTest]::Feed(16, 1, 0, 1200) | ConvertFrom-Json
if ($change.cells[-1].pci -ne 116) { throw 'A tail-only change was not emitted' }
if ([NrConfigTest]::Feed(16, 1, 1, 1300)) { throw 'Truncated input was accepted' }
if ([NrConfigTest]::Feed(17, 0, 0, 1400)) { throw 'Invalid count was accepted' }
$retry = [NrConfigTest]::Feed(16, 1, 0, 11200) | ConvertFrom-Json
if ($retry.cells.Count -ne 16) { throw 'Identical configuration did not refresh after dedup window' }
Write-Output 'PASS B825: 4/16 CC, full-record dedup, tail changes, refresh, truncated and oversized inputs'

foreach ($version in 0x20003,0x20004,0x30000,0x30002) {
    $ca = [NrConfigTest]::Ca($version, 3, 0, 0) | ConvertFrom-Json
    if ($ca.ver -ne ('{0}.{1}' -f ($version -shr 16), ($version -band 0xffff)) -or
        $ca.src -ne 'B9A7' -or $ca.cc.Count -ne 3 -or $ca.cc[0].dl -ne 3 -or
        $ca.cc[1].ssb_arfcn -ne 636000 -or $ca.cc[1].pci -ne 101 -or
        $ca.cc[1].band -ne 78 -or $ca.cc[1].dl -ne 2 -or $ca.cc[1].ul -ne 1 -or
        $ca.cc[2].band -ne 80 -or $ca.cc[2].ul -ne 2) {
        throw "Invalid B9A7 decoding for $version"
    }
}
if (([NrConfigTest]::Ca(0x30002, 0, 0, 0) | ConvertFrom-Json).cc.Count -ne 0 -or
    ([NrConfigTest]::Ca(0x30002, 16, 0, 0) | ConvertFrom-Json).cc.Count -ne 16) {
    throw 'B9A7 boundary count failed'
}
foreach ($lengthDelta in -95,-49,-1,1) {
    if ([NrConfigTest]::Ca(0x30002, 3, $lengthDelta, 0)) { throw 'Bad B9A7 length accepted' }
}
if ([NrConfigTest]::Ca(0x30001, 3, 0, 0) -or [NrConfigTest]::Ca(0x30002, 17, 0, 0)) {
    throw 'Unsupported B9A7 version or count accepted'
}
foreach ($fault in 1..5) {
    if ([NrConfigTest]::Ca(0x30002, 3, 0, $fault)) { throw "Malformed B9A7 carrier accepted: $fault" }
}
Write-Output 'PASS B9A7: versions 2.3/2.4/3.0/3.2, DL/UL fields, SUL placeholder, malformed record rejection'

$snr = [NrConfigTest]::Snr(1, 293, 0x30002, 0, 28, 10240, 12000) | ConvertFrom-Json
if ($snr.carrier -ne 1 -or $snr.pci -ne 293 -or $snr.sinr -ne 10 -or $snr.rs -ne 'SSB' -or
    $snr.PSObject.Properties.Name -contains 'arfcn') { throw 'Invalid SCC SINR record' }
if ([NrConfigTest]::Snr(1, 293, 0x30002, 0, 28, 10240, 12100)) { throw 'SCC SINR not throttled' }
if ([NrConfigTest]::Snr(1, 293, 0x30002, 1, 28, 10240, 13000)) { throw 'TRS appeared as SSB' }
if ([NrConfigTest]::Snr(1, 293, 0x30001, 0, 28, 10240, 13000)) { throw 'Unverified version accepted' }
if ([NrConfigTest]::Snr(1, 293, 0x30002, 0, 27, 10240, 13000)) { throw 'Short packet accepted' }
if ([NrConfigTest]::Snr(1, 1008, 0x30002, 0, 28, 10240, 13000)) { throw 'Invalid PCI accepted' }
if ([NrConfigTest]::Snr(1, 293, 0x30002, 0, 28, 0, 13000)) { throw 'Unavailable SINR accepted' }
if ([NrConfigTest]::nr_snr_primary_calls()) { throw 'SCC data reached the primary SINR path' }
$null = [NrConfigTest]::Snr(0, 293, 0x30002, 0, 28, 10240, 13000)
if ([NrConfigTest]::nr_snr_primary_calls() -ne 1) { throw 'PCC SINR path was not retained' }
Write-Output 'PASS B8D8: SCC SSB SINR, no fabricated ARFCN, malformed or unsupported records rejected, PCC path unchanged'

$qmdl = @(
    (Block '#define QMDL_LIMIT ' 'static void qmdl_status_locked('),
    (Block 'static void qmdl_escape(' '/* Log a representative failure'),
    (Block 'static void qmdl_start(' 'int main(')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'qmdl_stop_under_test.inc'), $qmdl)
$live = Block 'static void on_log(unsigned char *ptr, int len) {' 'static void on_event('
[IO.File]::WriteAllText((Join-Path $target 'diag_live_under_test.inc'), $live)
$liveMask = @(
    (Block 'struct code_info {' '#define MAX_CODES '),
    (Block 'static uint16_t k_qmdl_codes[]' '/* Optional live subscription'),
    (Block 'struct dci_mask_result {' "`n"),
    (Block 'static int dci_enable_logs(' 'static int dci_start('),
    (Block 'enum live_mask_action {' 'static void dci_enable_live_details(')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'diag_live_mask_under_test.inc'), $liveMask)
$obj = Join-Path $target 'qmdl_stop_test.obj'
$dll = Join-Path $target 'qmdl_stop_test.dll'
& "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin -c -I $target (Join-Path $PSScriptRoot 'qmdl_stop_test.c') -o $obj
if ($LASTEXITCODE) { throw 'QMDL stop host compilation failed' }
& "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj
if ($LASTEXITCODE) { throw 'QMDL stop host linking failed' }
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class QmdlStopTest {
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_diag_live_without_recording();
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_diag_live_masks();
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_qmdl_stop();
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_qmdl2_records();
}
"@
$result = [QmdlStopTest]::test_diag_live_without_recording()
if ($result) { throw "Live DIAG without QMDL test failed at line $result" }
Write-Output 'PASS live DIAG: RRC, NAS and LTE measurement records are queued without recording or file writes'
$result = [QmdlStopTest]::test_diag_live_masks()
if ($result) { throw "Live DIAG mask test failed at line $result" }
Write-Output 'PASS DIAG startup: validated 15-code base mask, separate live/QMDL codes, stepwise expansion and per-code fallback'
$result = [QmdlStopTest]::test_qmdl_stop()
if ($result) { throw "QMDL stop ordering test failed at line $result" }
Write-Output 'PASS QMDL: valid escaped HDLC frame, flush/close before ACK, asynchronous mask request, repeated stop, recovery on shutdown, short writes, full disk and open errors'
$result = [QmdlStopTest]::test_qmdl2_records()
if ($result) { throw "QMDL2 record test failed at line $result" }
Write-Output 'PASS QMDL2: container magic on open, unescaped record payload, wall-clock seconds/microseconds, clean close'
