param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/lte-ca-rank-native'
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
    (Block '/* little-endian bit field' '/* MSB-first bit reader'),
    (Block '/* Bandwidth RB count' '/* B0C1 is modem-decoded'),
    (Block '/* --- 0xB126 LTE PDSCH demapper config' '/* Rank / antenna window')
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'lte_ca_rank_under_test.inc'), $utilities)
$obj = Join-Path $target 'lte_ca_rank_test.obj'
$dll = Join-Path $target 'lte_ca_rank_test.dll'
& "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin `
    -c -I $target (Join-Path $PSScriptRoot 'lte_ca_rank_test.c') -o $obj
if ($LASTEXITCODE) { throw 'LTE CA/rank host compilation failed' }
& "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj
if ($LASTEXITCODE) { throw 'LTE CA/rank host linking failed' }
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class LteCaRankHostTests {
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_lte_ca_rank();
}
"@
$result = [LteCaRankHostTests]::test_lte_ca_rank()
if ($result) { throw "LTE CA/rank host test failed at line $result" }
Write-Output 'PASS LTE 0xB126 demapper and 0xB184 CA metrics decoders: per-version layouts, antenna codes, state normalization, slot fallback and truncation guards'