param([ValidateSet('debug','release')][string]$BuildType='debug')
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$task=if ($BuildType -eq 'release') { ':TMessagesProj_AppStandalone:assembleAfatStandalone' } else { ':TMessagesProj_AppStandalone:assembleAfatDebug' }
& .\gradlew.bat $task
exit $LASTEXITCODE
