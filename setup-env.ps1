param(
    [string]$MavenPath = "F:\env\apache-maven-3.9.11", # Change this default or pass as argument
    [string]$JavaHome = "C:\Program Files\Java\jdk-17.0.2" # Optional: Set if JAVA_HOME is not already set correctly
)

if (-not (Test-Path $MavenPath)) {
    Write-Host "Error: Maven path '$MavenPath' does not exist." -ForegroundColor Red
    Write-Host "Usage: .\setup-maven.ps1 -MavenPath 'C:\Tools\apache-maven...' -JavaHome 'C:\Program Files\Java\jdk...'"
    exit 1
}

if ($JavaHome -and (Test-Path $JavaHome)) {
    if ($env:JAVA_HOME -ne $JavaHome) {
        $env:JAVA_HOME = $JavaHome
        Write-Host "Set JAVA_HOME to $JavaHome" -ForegroundColor Green
    }
    
    $JavaBin = "$JavaHome\bin"
    if ($env:PATH -split ';' | Where-Object { $_ -eq $JavaBin }) {
        Write-Host "Java bin path already in PATH. Skipping update." -ForegroundColor Yellow
    }
    else {
        $env:PATH = "$JavaBin;$env:PATH"
        Write-Host "Added Java bin to PATH." -ForegroundColor Green
    }
}
elseif (-not $env:JAVA_HOME) {
    Write-Host "Warning: JAVA_HOME is not set. Maven might fail." -ForegroundColor Yellow
}
else {
    Write-Host "Using existing JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
}

$MavenBin = "$MavenPath\bin"

# Check if PATH already contains the Maven bin directory
if ($env:PATH -split ';' | Where-Object { $_ -eq $MavenBin }) {
    Write-Host "Maven bin path already in PATH. Skipping update." -ForegroundColor Yellow
}
else {
    $env:PATH = "$MavenBin;$env:PATH"
    Write-Host "Added Maven bin to PATH." -ForegroundColor Green
}

# Always update MAVEN_HOME just in case
if ($env:MAVEN_HOME -ne $MavenPath) {
    $env:MAVEN_HOME = $MavenPath
    Write-Host "Set MAVEN_HOME to $MavenPath" -ForegroundColor Green
}

Write-Host "Verifying mvn version..."
mvn -version
