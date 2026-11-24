param([string]$ProductionHeader)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$taskBuild = Join-Path $taskRoot '.temp/qmdl2-host-tests'
New-Item -ItemType Directory -Path $taskBuild -Force | Out-Null
$taskClang = Join-Path $env:LOCALAPPDATA 'Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$taskLinker = Join-Path (Split-Path -Parent $taskClang) 'ld.lld.exe'
$taskSource = Join-Path $PSScriptRoot 'qmdl2_test.c'
$taskObject = Join-Path $taskBuild 'qmdl2_test.obj'
$taskDll = Join-Path $taskBuild 'qmdl2_test.dll'
& $taskClang --target=x86_64-pc-windows-msvc -ffreestanding -fno-stack-protector -fno-builtin -Wall -Wextra -Werror -c -I $taskBuild $taskSource -o $taskObject
if ($LASTEXITCODE) { throw 'QMDL2 host test compilation failed.' }
& $taskLinker -flavor link /dll /noentry /nodefaultlib "/out:$taskDll" $taskObject
if ($LASTEXITCODE) { throw 'QMDL2 host test linking failed.' }
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class Qmdl2HostTests {
    [DllImport(@"$taskDll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int test_qmdl2();
}
"@
$result = [Qmdl2HostTests]::test_qmdl2()
if ($result) { throw "QMDL2 host test failed at line $result." }
Write-Output 'PASS QMDL2 container host tests'