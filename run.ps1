Write-Host "Setting up environment..."
. ..\setup-env.ps1

Write-Host "Running Media Center..."
cd media-center
mvn javafx:run
