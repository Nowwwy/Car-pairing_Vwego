param(
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$StudioPath = "C:/Program Files/Android/Android Studio",
    [Parameter(Mandatory = $true)][string]$NaviClasspath
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskBuild = Join-Path $taskRoot 'offline-build'
New-Item -ItemType Directory -Path $taskBuild -Force | Out-Null
$taskTools = (Get-ChildItem "$SdkPath/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$taskPlatform = (Get-ChildItem "$SdkPath/platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$taskJava = "$StudioPath/jbr/bin"
$taskKotlin = "$StudioPath/plugins/Kotlin/kotlinc"
$taskAndroidJar = "$taskPlatform/android.jar"
$env:JAVA_HOME = "$StudioPath/jbr"
$env:PATH = "$taskJava;$env:PATH"
function Check-Result { if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)" } }
& "$taskTools/aapt2.exe" compile --dir "$taskRoot/app/src/main/res" -o "$taskBuild/resources.zip"
Check-Result
$taskManifest = (Get-Content "$taskRoot/app/src/main/AndroidManifest.xml" -Raw).Replace('<manifest ', '<manifest package="ai.carpairing.app" ')
Set-Content "$taskBuild/AndroidManifest.xml" -Value $taskManifest -Encoding utf8
$taskGenerated = Join-Path $taskBuild 'generated'
& "$taskTools/aapt2.exe" link -o "$taskBuild/base.apk" -I $taskAndroidJar --manifest "$taskBuild/AndroidManifest.xml" --min-sdk-version 28 --target-sdk-version 34 --version-code 5 --version-name '0.5.0-pleos-group' --java $taskGenerated -A "$taskRoot/app/src/main/assets" --auto-add-overlay "$taskBuild/resources.zip"
Check-Result
$taskSources = @(Get-ChildItem "$taskRoot/app/src/main/java" -Recurse -Filter '*.kt' | Select-Object -ExpandProperty FullName)
$taskLibraries = @(Get-ChildItem -LiteralPath $NaviClasspath -Filter '*.jar' -File | Select-Object -ExpandProperty FullName)
if ($taskLibraries.Count -eq 0) { throw 'NaviHelper classpath has no jars.' }
$taskResourceClasses = Join-Path $taskBuild 'resource-classes'
& "$taskJava/javac.exe" --release 17 -d $taskResourceClasses "$taskGenerated/ai/carpairing/app/R.java"
Check-Result
$taskClasspath = ((@($taskAndroidJar, $taskResourceClasses) + $taskLibraries) -join ';').Replace('\', '/')
$taskArgs = Join-Path $taskBuild 'kotlinc.args'
Set-Content -LiteralPath $taskArgs -Value @('-classpath', ('"' + $taskClasspath + '"')) -Encoding utf8
& "$taskKotlin/bin/kotlinc.bat" "@$taskArgs" @taskSources -jvm-target 17 -d "$taskBuild/classes.jar"
Check-Result
& "$taskJava/jar.exe" uf "$taskBuild/classes.jar" -C $taskResourceClasses .
Check-Result
$taskDex = Join-Path $taskBuild 'dex'
New-Item -ItemType Directory -Path $taskDex -Force | Out-Null
Get-ChildItem -LiteralPath $taskDex -Filter 'classes*.dex' -File | Remove-Item
& "$taskJava/java.exe" -cp "$StudioPath/plugins/android/lib/r8.jar" com.android.tools.r8.D8 --lib $taskAndroidJar --min-api 28 --output $taskDex "$taskBuild/classes.jar" "$taskKotlin/lib/kotlin-stdlib.jar" @taskLibraries
Check-Result
Copy-Item -LiteralPath "$taskBuild/base.apk" -Destination "$taskBuild/unsigned.apk" -Force
$taskRuntime = Join-Path $taskBuild 'runtime-resources'
New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
$taskReflect = $taskLibraries | Where-Object { [IO.Path]::GetFileName($_) -like 'kotlin-reflect*.jar' } | Select-Object -First 1
Push-Location $taskRuntime
try {
    $taskDexArgs = foreach ($taskFile in Get-ChildItem -LiteralPath $taskDex -Filter 'classes*.dex' -File) {
        '-C', $taskDex, $taskFile.Name
    }
    $taskRuntimeArgs = @()
    if ($taskReflect) {
        & "$taskJava/jar.exe" xf $taskReflect META-INF/services
        Check-Result
        & "$taskJava/jar.exe" xf "$taskKotlin/lib/kotlin-stdlib.jar" kotlin
        Check-Result
        $taskRuntimeArgs += 'META-INF/services'
        $taskRuntimeArgs += @(Get-ChildItem -Path kotlin -Recurse -Filter '*.kotlin_builtins' -File |
            ForEach-Object { [IO.Path]::GetRelativePath($taskRuntime, $_.FullName) })
    }
    & "$taskJava/jar.exe" uf "$taskBuild/unsigned.apk" @taskDexArgs @taskRuntimeArgs
    Check-Result
} finally { Pop-Location }
& "$taskTools/zipalign.exe" -f 4 "$taskBuild/unsigned.apk" "$taskBuild/aligned.apk"
Check-Result
$taskKey = "$taskBuild/layout-debug.jks"
if (-not (Test-Path -LiteralPath $taskKey)) {
    & "$taskJava/keytool.exe" -genkeypair -keystore $taskKey -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Car Pairing,C=KR' -keyalg RSA -keysize 2048 -validity 10000
    Check-Result
}
& "$taskTools/apksigner.bat" sign --ks $taskKey --ks-pass pass:android --out "$taskRoot/CarPairing-layout-debug.apk" "$taskBuild/aligned.apk"
Check-Result
& "$taskTools/apksigner.bat" verify --verbose "$taskRoot/CarPairing-layout-debug.apk"
Check-Result
Write-Output "Built: $taskRoot/CarPairing-layout-debug.apk"
