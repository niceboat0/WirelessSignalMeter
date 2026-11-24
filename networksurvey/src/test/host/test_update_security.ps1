param(
    [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$GradleCache = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1"
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$taskOutput = Join-Path $taskRoot '.temp/update-security-tests'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null

function Find-TaskJar([string]$module, [string]$version) {
    $taskDirectory = Join-Path $GradleCache "$module/$version"
    $taskJar = Get-ChildItem -LiteralPath $taskDirectory -Recurse -File -Filter '*.jar' |
        Where-Object Name -NotMatch 'sources|javadoc' |
        Select-Object -First 1 -ExpandProperty FullName
    if (!$taskJar) { throw "Missing cached JAR: $module $version" }
    return $taskJar
}

$taskStdlib = Find-TaskJar 'org.jetbrains.kotlin/kotlin-stdlib' '2.4.10'
$taskAnnotations = Find-TaskJar 'org.jetbrains/annotations' '26.0.2-1'
$taskCompiler = @(
    (Find-TaskJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable' '2.4.10'),
    $taskStdlib,
    $taskAnnotations,
    (Find-TaskJar 'org.jetbrains.kotlin/kotlin-daemon-embeddable' '2.4.10'),
    (Find-TaskJar 'org.jetbrains.kotlin/kotlin-reflect' '1.6.10'),
    (Find-TaskJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm' '1.10.2')
) -join [IO.Path]::PathSeparator
$taskClasspath = @(
    $taskOutput,
    $taskStdlib,
    $taskAnnotations,
    (Join-Path $JavaHome '../lib/junit4.jar')
) -join [IO.Path]::PathSeparator
$taskSource = Join-Path $taskRoot 'networksurvey/src/main/java/com/craxiom/networksurvey/update'
$taskTests = Join-Path $taskRoot 'networksurvey/src/test/java/com/craxiom/networksurvey/update'

& "$JavaHome/bin/java.exe" -cp $taskCompiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $taskClasspath -d $taskOutput `
    "$taskSource/UpdateSecurity.kt" "$taskSource/SecureUpdateTransport.kt" `
    "$taskTests/UpdateSecurityTest.kt" "$taskTests/SecureUpdateTransportTest.kt"
if ($LASTEXITCODE) { throw 'Update security Kotlin compilation failed' }

& "$JavaHome/bin/javac.exe" --release 17 -encoding UTF-8 -cp $taskClasspath -d $taskOutput `
    "$PSScriptRoot/RunHostTests.java"
if ($LASTEXITCODE) { throw 'Update security host runner compilation failed' }

& "$JavaHome/bin/java.exe" -cp $taskClasspath RunHostTests `
    com.craxiom.networksurvey.update.UpdateSecurityTest `
    com.craxiom.networksurvey.update.SecureUpdateTransportTest
if ($LASTEXITCODE) { throw 'Update security host tests failed' }
