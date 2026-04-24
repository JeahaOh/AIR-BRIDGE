$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$GradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $RootDir ".gradle-home" }

Write-Host "[verify] Repo: $RootDir"
Write-Host "[verify] Java version"
java -version

Write-Host "[verify] Gradle test"
Push-Location $RootDir
try {
    $env:GRADLE_USER_HOME = $GradleUserHome
    if (Test-Path ".\gradlew.bat") {
        .\gradlew.bat test
    } elseif (Test-Path ".\gradlew") {
        .\gradlew test
    } else {
        gradle test
    }
} finally {
    Pop-Location
}

Write-Host "[verify] Done"
