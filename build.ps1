<#
.SYNOPSIS
    Builds the ESC Configurator Android app (web app + APK).

.DESCRIPTION
    Uses the portable toolchain in .toolchain (Node 18, JDK 17) when it is
    there, otherwise whatever is installed on the machine.

    The build writes tens of thousands of small files (node_modules, Gradle
    caches). On an exFAT/FAT drive that takes hours, so all of that can be
    moved to a work directory on an NTFS drive: pass -WorkDir or put the path
    into .toolchain\workdir.txt. The web sources are mirrored there, built,
    and the result is copied back to web\build.

    The finished APK is copied to dist\.

.PARAMETER SkipWeb
    Reuse the existing web\build instead of rebuilding the web app.

.PARAMETER Firmware
    Refresh the bundled firmware pack (firmware-pack\) before building.

.PARAMETER Debug
    Build the debuggable variant (WebView inspectable via chrome://inspect).

.PARAMETER WorkDir
    Directory for caches and build output, see above.

.EXAMPLE
    .\build.ps1
    .\build.ps1 -SkipWeb -Debug
#>
param(
    [switch]$SkipWeb,
    [switch]$Firmware,
    [switch]$Debug,
    [string]$WorkDir
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$toolchain = Join-Path $root '.toolchain'
$webDir = Join-Path $root 'web'

if (-not $WorkDir) {
    $workDirFile = Join-Path $toolchain 'workdir.txt'
    if (Test-Path $workDirFile) {
        $WorkDir = (Get-Content $workDirFile -TotalCount 1).Trim()
    }
}

if (Test-Path (Join-Path $toolchain 'jdk')) {
    $env:JAVA_HOME = Join-Path $toolchain 'jdk'
    $env:PATH = "$(Join-Path $toolchain 'node');$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
}

$gradleArgs = @()
if ($WorkDir) {
    New-Item -ItemType Directory -Force $WorkDir | Out-Null
    $env:GRADLE_USER_HOME = Join-Path $WorkDir 'gradle-home'
    $env:YARN_CACHE_FOLDER = Join-Path $WorkDir 'yarn-cache'
    $gradleArgs += "-PescBuildRoot=$(Join-Path $WorkDir 'android-build')"
    $gradleArgs += '--project-cache-dir', (Join-Path $WorkDir 'android-gradle-cache')
}

function Invoke-Step([string]$Name, [scriptblock]$Step) {
    Write-Host "== $Name" -ForegroundColor Cyan
    & $Step
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed (exit code $LASTEXITCODE)"
    }
}

# robocopy reports success with exit codes below 8
function Copy-Tree([string]$From, [string]$To, [string[]]$Options) {
    robocopy $From $To /MIR /NFL /NDL /NJH /NJS /NP @Options | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "Copying $From to $To failed (robocopy exit code $LASTEXITCODE)"
    }
    $global:LASTEXITCODE = 0
}

if ($Firmware) {
    Invoke-Step 'Firmware pack' { python (Join-Path $root 'tools\fetch_firmware.py') }
}

if (-not $SkipWeb) {
    $webBuildDir = $webDir
    if ($WorkDir) {
        $webBuildDir = Join-Path $WorkDir 'web'
        Copy-Tree $webDir $webBuildDir @('/XD', 'node_modules', 'build', '.git')
    }

    Push-Location $webBuildDir
    try {
        # --ignore-scripts: skips a native module the web app does not use
        Invoke-Step 'Web dependencies' { yarn install --frozen-lockfile --ignore-scripts }

        $env:GENERATE_SOURCEMAP = 'false'
        $env:DISABLE_ESLINT_PLUGIN = 'true'
        Invoke-Step 'Web build' { yarn build }
    } finally {
        Pop-Location
    }

    if ($WorkDir) {
        Copy-Tree (Join-Path $webBuildDir 'build') (Join-Path $webDir 'build') @()
    }
}

$variant = if ($Debug) { 'debug' } else { 'release' }
Push-Location (Join-Path $root 'android')
try {
    Invoke-Step "APK ($variant)" { .\gradlew.bat "assemble$variant" @gradleArgs }
} finally {
    Pop-Location
}

$outputs = if ($WorkDir) { Join-Path $WorkDir 'android-build\app\outputs\apk' } else { Join-Path $root 'android\app\build\outputs\apk' }
$apk = Get-ChildItem (Join-Path $outputs $variant) -Filter *.apk | Select-Object -First 1
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$target = Join-Path $dist "esc-configurator-$variant.apk"
Copy-Item $apk.FullName $target -Force

Write-Host ("{0}  ({1:N1} MB)" -f $target, ((Get-Item $target).Length / 1MB)) -ForegroundColor Green
