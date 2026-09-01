# CallerView plugin build script.
# Uses JDK 21 javac (the only JDK 11+ available on this machine) with -source/-target 1.8
# so the emitted bytecode is 52.0 (Java 8) and runs on every IDE from 2020.3 onwards.
# No Maven required.

$ErrorActionPreference = "Stop"

$projectRoot = "D:\trae\callerView"
$m2 = "C:\Users\Administrator\.m2\repository"
$jdk = "C:\Users\Administrator\.trae-cn\extensions\redhat.java-1.55.0-win32-x64\jre\21.0.11-win32-x86_64"
$javac = "$jdk\bin\javac.exe"
$jar    = "$jdk\bin\jar.exe"

$src    = "$projectRoot\src\main\java"
$res    = "$projectRoot\src\main\resources"
$target = "$projectRoot\target\classes"
$staging = "$projectRoot\target\staging\CallerView\lib"

# --- 1. collect classpath: every 203.7148.57 jar under com/jetbrains + annotations ---
$jets = Get-ChildItem -Path "$m2\com\jetbrains" -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "203\.7148\.57" } |
        Select-Object -ExpandProperty FullName

$ann = @()
$annDir = "$m2\org\jetbrains\annotations"
if (Test-Path $annDir) {
    $ann = Get-ChildItem -Path $annDir -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
           Select-Object -ExpandProperty FullName
}

$cpList = @($jets) + $ann
$cp = $cpList -join ";"
Write-Host "Classpath has $($cpList.Count) jars"

# --- 2. clean + prepare dirs ---
if (Test-Path "$projectRoot\target") { Remove-Item -Recurse -Force "$projectRoot\target" }
New-Item -ItemType Directory -Force -Path $target | Out-Null
New-Item -ItemType Directory -Force -Path $staging | Out-Null

# --- 3. compile ---
$srcFiles = Get-ChildItem -Path $src -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
Write-Host "Compiling $($srcFiles.Count) java files with $javac ..."

# Write source list to a file to avoid cmdline length limits.
# Use .NET API to write WITHOUT a BOM (a BOM corrupts the first file name for javac @argfile).
$srcList = "$projectRoot\target\sources.txt"
[System.IO.File]::WriteAllLines($srcList, $srcFiles, (New-Object System.Text.UTF8Encoding($false)))

& $javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -cp $cp -d $target "@$srcList"
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "javac failed with exit code $code"
    exit $code
}
Write-Host "javac OK"

# --- 4. copy resources ---
Copy-Item -Path "$res\META-INF" -Destination $target -Recurse -Force
Copy-Item -Path "$res\icons"   -Destination $target -Recurse -Force

# --- 5. package jar ---
$jarPath = "$staging\CallerView.jar"
& $jar cf "$jarPath" -C $target "."
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "jar failed with exit code $code"
    exit $code
}
Write-Host "jar OK -> $jarPath"

# --- 6. package zip ---
# IMPORTANT: build the zip with JDK `jar` (not PowerShell Compress-Archive / .NET ZipFile),
# because the latter emit backslash entry names ("lib\CallerView.jar"). IntelliJ's zip
# reader requires the standard forward-slash form ("lib/CallerView.jar").
# The zip MUST contain a single top-level folder named after the plugin:
#     CallerView/
#       lib/
#         CallerView.jar
# IDEA 2023+ (PluginDescriptorLoader.loadDescriptorFromArtifact) extracts the zip, takes
# the FIRST top-level entry and loads the plugin descriptor from it. Without the wrapping
# folder it would pick "lib" and fail with "Fail to load plugin descriptor from file".
$stagingRoot = "$projectRoot\target\staging\CallerView"
$zipPath = "$projectRoot\target\CallerView-1.0.0.zip"
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
$zipRoot = "$projectRoot\target\staging\ziproot"
if (Test-Path $zipRoot) { Remove-Item -Recurse -Force $zipRoot }
New-Item -ItemType Directory -Force -Path $zipRoot | Out-Null
Copy-Item -Path "$stagingRoot" -Destination "$zipRoot\CallerView" -Recurse -Force
# c=create, M=no manifest (plain zip), f=output file, -C <dir> . = archive its contents
& $jar cMf "$zipPath" -C "$zipRoot" "."
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "zip (jar) failed with exit code $code"
    exit $code
}
Write-Host "zip OK -> $zipPath"

Write-Host "BUILD DONE"
