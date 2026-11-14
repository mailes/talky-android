$mode=Read-Host 'Build type (debug/release)'
& "$PSScriptRoot/build.ps1" -BuildType $mode
exit $LASTEXITCODE
