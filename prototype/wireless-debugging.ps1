$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot '..\.tools\android\platform-tools\adb.exe'
Write-Host 'Galaxy wireless debugging setup'
Write-Host 'On the phone: Developer options > Wireless debugging > Pair device with pairing code.'
Write-Host 'Enter the IP address and port from the PAIRING screen, e.g. 192.168.1.2:12345.'
$pairAddress = Read-Host 'Pairing IP:port'
if ($pairAddress -notmatch '^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$') { throw 'Invalid address.' }
& $adb pair $pairAddress
if ($LASTEXITCODE -ne 0) { Write-Host 'Pairing failed.'; Read-Host 'Press Enter to close'; exit 1 }
Write-Host 'Return to the main Wireless debugging screen.'
Write-Host 'Enter its IP address and port. This port is usually different from the pairing port.'
$connectAddress = Read-Host 'Connection IP:port'
if ($connectAddress -notmatch '^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$') { throw 'Invalid address.' }
& $adb connect $connectAddress
& $adb devices -l
Write-Host 'Tell Codex when this step is complete.'
Read-Host 'Press Enter to close'
