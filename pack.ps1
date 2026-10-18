# IconMix build script
#
# Usage:
#   .\pack.ps1              Build portable app-image (dist\IconMix GUI + dist\IconMixCLI console)
#   .\pack.ps1 -Installer   Also build .exe installer (requires WiX Toolset v3)
#   .\pack.ps1 -Clean       Remove build artifacts only
#
# Uses only JDK built-in tools (javac / jar / jpackage). No network, no Maven/Gradle.
# NOTE: kept ASCII-only on purpose -- PS 5.1 reads BOM-less UTF-8 files as ANSI and
#       would garble non-ASCII text (see REQUIREMENTS T-1).

param(
    [switch]$Installer,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

$JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-26' }
$javac = Join-Path $JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $JAVA_HOME 'bin\jar.exe'
$jpackage = Join-Path $JAVA_HOME 'bin\jpackage.exe'
$java = Join-Path $JAVA_HOME 'bin\java.exe'

$buildDir = Join-Path $root 'build'
$distDir = Join-Path $root 'dist'
$assetsDir = Join-Path $root 'assets'
$appName = 'IconMix'
$appVersion = '1.0.0'
$appDesc = 'Folder icon composer'
$cliName = $appName + 'CLI'

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "    $msg" -ForegroundColor Green }
function Note($msg) { Write-Host "    $msg" -ForegroundColor Yellow }

function Remove-Dir($path) {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}

Write-Host "IconMix build script" -ForegroundColor White
Write-Host "JDK: $JAVA_HOME"

if ($Clean) {
    Step 'Cleaning'
    Remove-Dir $buildDir
    Remove-Dir $distDir
    Ok 'Removed build\ and dist\'
    exit 0
}

# ---------------------------------------------------------------- toolchain
Step 'Checking toolchain'
foreach ($tool in @($javac, $jar, $jpackage, $java)) {
    if (-not (Test-Path $tool)) {
        throw "Missing $tool -- set JAVA_HOME to a JDK 14+ (currently $JAVA_HOME)"
    }
}
Ok 'javac / jar / jpackage available'

# ---------------------------------------------------------------- compile
Step 'Compiling sources'
Remove-Dir $buildDir
$classesDir = Join-Path $buildDir 'classes'
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$sources = (Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root 'src\main\java')).FullName
& $javac -encoding UTF-8 -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$classCount = (Get-ChildItem -Recurse -Filter *.class -Path $classesDir).Count
Ok "Compiled $classCount classes"

# ---------------------------------------------------------------- icon
Step 'Preparing application icon'
$iconIco = Join-Path $assetsDir 'icon.ico'
if (-not (Test-Path $iconIco)) {
    Note 'assets\icon.ico not found, generating it'
    $toolsDir = Join-Path $buildDir 'tools'
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
    $iconSrc = Join-Path $root 'tools\MakeAppIcon.java'
    & $javac -encoding UTF-8 -cp $classesDir -d $toolsDir $iconSrc
    if ($LASTEXITCODE -ne 0) { throw 'Icon generator compilation failed' }
    $cp = $classesDir + ';' + $toolsDir
    & $java '-Djava.awt.headless=true' -cp $cp MakeAppIcon $assetsDir
    if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed' }
}
Ok "Icon: $iconIco"

# ---------------------------------------------------------------- jar
Step 'Packaging jar'
$jarDir = Join-Path $buildDir 'jar'
New-Item -ItemType Directory -Force -Path $jarDir | Out-Null
$manifestPath = Join-Path $buildDir 'manifest.txt'
$manifestLines = @(
    'Manifest-Version: 1.0',
    'Main-Class: iconmix.Main',
    "Implementation-Title: $appName",
    "Implementation-Version: $appVersion"
)
Set-Content -Path $manifestPath -Value $manifestLines -Encoding ASCII
$jarPath = Join-Path $jarDir ($appName + '.jar')
& $jar --create --file $jarPath --manifest $manifestPath -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar packaging failed' }
$jarKb = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Ok "$appName.jar ($jarKb KB)"

# ---------------------------------------------------------------- jpackage
# Restrict the runtime image. By default jpackage drags in jdk.jshell, jdk.jconsole,
# java.sql and friends (about 123 MB here). This app only needs Swing/AWT plus base
# libraries, so java.base + java.desktop gives roughly 74 MB; jlink adds the transitive
# dependencies automatically.
# NOTE: if new capabilities are introduced later (java.logging, java.net.http, ...),
#       add the corresponding module here.
$runtimeModules = 'java.base,java.desktop'

Step "Building portable GUI app-image ($appName)"
Remove-Dir (Join-Path $distDir $appName)
& $jpackage --type app-image `
    --name $appName `
    --app-version $appVersion `
    --vendor $appName `
    --description $appDesc `
    --icon $iconIco `
    --input $jarDir `
    --main-jar ($appName + '.jar') `
    --main-class iconmix.Main `
    --dest $distDir `
    --add-modules $runtimeModules `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'jpackage (GUI) failed' }

Step "Building console app-image ($cliName)"
Remove-Dir (Join-Path $distDir $cliName)
& $jpackage --type app-image `
    --name $cliName `
    --app-version $appVersion `
    --vendor $appName `
    --description ($appDesc + ' (console)') `
    --icon $iconIco `
    --input $jarDir `
    --main-jar ($appName + '.jar') `
    --main-class iconmix.Main `
    --dest $distDir `
    --win-console `
    --add-modules $runtimeModules `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'jpackage (console) failed' }

# ---------------------------------------------------------------- installer
if ($Installer) {
    Step 'Building .exe installer (needs WiX Toolset)'
    $wix = Get-Command light.exe -ErrorAction SilentlyContinue
    if (-not $wix) {
        Note 'WiX Toolset (light.exe) not found, skipping installer'
        Note 'Install WiX v3 then retry: https://github.com/wixtoolset/wix3/releases'
    } else {
        & $jpackage --type exe `
            --name $appName `
            --app-version $appVersion `
            --vendor $appName `
            --description $appDesc `
            --icon $iconIco `
            --input $jarDir `
            --main-jar ($appName + '.jar') `
            --main-class iconmix.Main `
            --dest $distDir `
            --win-dir-chooser `
            --win-menu `
            --win-shortcut `
            --add-modules $runtimeModules `
            --java-options '-Dfile.encoding=UTF-8'
        if ($LASTEXITCODE -ne 0) {
            Note 'Installer build failed (common cause: WiX version mismatch, jpackage needs WiX 3.x)'
        } else {
            Ok 'Installer built'
        }
    }
}

# ---------------------------------------------------------------- summary
Step 'Summary'
foreach ($dir in @($appName, $cliName)) {
    $p = Join-Path $distDir $dir
    if (Test-Path $p) {
        $sizeMb = [math]::Round((Get-ChildItem $p -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
        $exePath = Join-Path $p ($dir + '.exe')
        # PS 5.1 cannot use if as an expression inside -f arguments (see REQUIREMENTS T-6)
        if (Test-Path $exePath) {
            $exeState = 'exe present'
        } else {
            $exeState = 'exe MISSING'
        }
        Ok ("{0,-12} {1,6} MB   {2}" -f $dir, $sizeMb, $exeState)
    }
}
$installerPath = Join-Path $distDir ($appName + '-' + $appVersion + '.exe')
if (Test-Path $installerPath) {
    $instMb = [math]::Round((Get-Item $installerPath).Length / 1MB, 1)
    Ok ("installer    {0,6} MB" -f $instMb)
}

Write-Host "`nDone. Artifacts in $distDir" -ForegroundColor Green
Write-Host "Hint: dist\$appName is self-contained -- zip it and the target PC needs no JDK." -ForegroundColor Gray
