param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/qmi-lock-native'
New-Item -ItemType Directory -Path $target -Force | Out-Null
$bin = Join-Path $Sdk 'ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin'
$obj = Join-Path $target 'qmi_lock_test.obj'
$dll = Join-Path $target 'qmi_lock_test.dll'
$include = Join-Path $repo 'networksurvey/src/main/cpp'
& "$bin/clang.exe" --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin `
    -c -I $include (Join-Path $PSScriptRoot 'qmi_lock_test.c') -o $obj
if ($LASTEXITCODE) { throw 'QMI lock host compilation failed' }
& "$bin/ld.lld.exe" -flavor link /dll /noentry /nodefaultlib "/out:$dll" $obj
if ($LASTEXITCODE) { throw 'QMI lock host linking failed' }
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class QmiLockHostTests {
    [DllImport(@"$dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_qmi_lock();
}
"@
$result = [QmiLockHostTests]::test_qmi_lock()
if ($result) { throw "QMI lock host test failed at line $result" }
Write-Output 'PASS QMI NAS lock frames: LTE cell list/apply, NR PCI/ARFCN/clear, system selection preference TLVs, GET headers and TLV walk'