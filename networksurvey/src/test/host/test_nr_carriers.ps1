param(
    [string]$Studio = 'C:/Program Files/Android/Android Studio',
    [string]$GradleCache = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$target = Join-Path $repo '.temp/nr-carriers-host'
New-Item -ItemType Directory -Path $target -Force | Out-Null
function Find-Jar([string]$Module, [string]$Version) {
    $file = Get-ChildItem -LiteralPath (Join-Path $GradleCache "$Module/$Version") -Recurse -File -Filter '*.jar' |
        Where-Object Name -NotMatch 'sources|javadoc' | Select-Object -First 1 -ExpandProperty FullName
    if (!$file) { throw "Missing cached JAR: $Module $Version" }
    $file
}
$stdlib = Find-Jar 'org.jetbrains.kotlin/kotlin-stdlib' '2.4.10'
$annotations = Find-Jar 'org.jetbrains/annotations' '26.0.2-1'
$compiler = @(
    (Find-Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable' '2.4.10'), $stdlib, $annotations,
    (Find-Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable' '2.4.10'),
    (Find-Jar 'org.jetbrains.kotlin/kotlin-reflect' '1.6.10'),
    (Find-Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm' '1.10.2')
) -join [IO.Path]::PathSeparator
$classpath = @($target, $stdlib, $annotations, "$Studio/lib/junit4.jar",
    "$Studio/plugins/design-tools/lib/layoutlib.jar") -join [IO.Path]::PathSeparator
$source = Join-Path $repo 'networksurvey/src/main/java/com/craxiom/networksurvey'
& "$Studio/jbr/bin/javac.exe" -cp $classpath -d $target "$source/model/NrCarrier.java" "$source/model/AndroidNrCarriers.java" "$PSScriptRoot/RunHostTests.java"
if ($LASTEXITCODE) { throw 'NR Java compilation failed' }
& "$Studio/jbr/bin/java.exe" -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $classpath -d $target "$source/model/SurveyEvent.kt" "$source/model/SurveyLocationMode.kt" "$source/ui/rootdiag/RootNrCarriers.kt" "$source/ui/rootdiag/TddPattern.kt" "$source/ui/rootdiag/DiagRecordingPolicy.kt" "$source/ui/rootdiag/RecordingLocationSidecar.kt" "$PSScriptRoot/NrCarrierTest.kt" "$PSScriptRoot/TddPatternTest.kt" "$PSScriptRoot/QmdlRecordingFilesTest.kt" "$repo/networksurvey/src/test/java/com/craxiom/networksurvey/ui/rootdiag/DiagRecordingPolicyTest.kt" "$repo/networksurvey/src/test/java/com/craxiom/networksurvey/ui/rootdiag/RecordingLocationSidecarTest.kt"
if ($LASTEXITCODE) { throw 'NR Kotlin compilation failed' }
& "$Studio/jbr/bin/java.exe" -cp $classpath RunHostTests com.craxiom.networksurvey.ui.rootdiag.NrCarrierTest com.craxiom.networksurvey.ui.rootdiag.TddPatternTest com.craxiom.networksurvey.ui.rootdiag.QmdlRecordingFilesTest com.craxiom.networksurvey.ui.rootdiag.DiagRecordingPolicyTest com.craxiom.networksurvey.ui.rootdiag.DiagRecordingSessionTest com.craxiom.networksurvey.ui.rootdiag.RecordingLocationSidecarTest
if ($LASTEXITCODE) { throw 'NR carrier tests failed' }
