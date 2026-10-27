<#
.SYNOPSIS
Verifica el paquete MediaTek WinUSB firmado e instala para X04G MTP+ADB.
.DESCRIPTION
Sin firmware, ADB, Fastboot, reinicios ni sustitucion de MTP. Para instalar exige
Windows AMD64, administrador y una unica interfaz USB 0E8D:201D&MI_01 presente.
-VerifyPackageOnly verifica archivos locales y firma sin consultar dispositivos.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$VerifyPackageOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskCache = Join-Path $taskRoot '.cache\toolchain\mediatek-adb'
$taskCab = Join-Path $taskCache 'mediatek-winusb.cab'
$taskPackage = Join-Path $taskCache 'package'
$taskCabSha = 'BCEBF41BFC2C081544CC3CE4EBDC0B8D890F6411C1D2B925DC2F6B6A0CCD6C5B'
$taskHashes = @{
    'android_winusb.inf' = '48684E581C3DCDA467482955C0072F051DA4037CF66C1FEEB8C2752DC38A7B50'
    'androidwinusb86.cat' = 'DFF9D8A8C0C65BCFE6CC76ACEBA6F541692D3E835C26019FB73470084C0FBA31'
    'androidwinusba64.cat' = 'F9ADF64DCADA6D46F6947661C456C112ABBC4CF84D4D06826E866B13863675F6'
    'amd64\WdfCoInstaller01009.dll' = 'A02C27EAD1C72CAF1472914E7918D25CD872947A3E75B52A70AB43625E252B59'
    'amd64\winusbcoinstaller2.dll' = 'A3E0882B27B1EB44C6C824705F98129DB7DE5D6130EB07F215797C0BA7B1D8BC'
    'amd64\WUDFUpdate_01009.dll' = '03DE81CFD9F03722D2115654C3A3FE6B0563BE5E3019808B910B2A88909307DC'
    'i386\WdfCoInstaller01009.dll' = '534A0467B878FAB375D092B4FE878513BB64B7A1A1F91E5769086D60ACF66FCC'
    'i386\winusbcoinstaller2.dll' = '09D22CCFF83F9CF70FC20125748529D667E7DBCC96368AFFC12EE580B0117C72'
    'i386\WUDFUpdate_01009.dll' = '1A0B6EF1E615CBC26194AC14ADF6CDAE4D18AF357EA8A550787154851AA624A7'
}
if ($env:OS -ne 'Windows_NT' -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'Este paquete corresponde a Windows AMD64.'
}
if (-not (Test-Path -LiteralPath $taskCab -PathType Leaf) -or
    (Get-FileHash -LiteralPath $taskCab -Algorithm SHA256).Hash -ne $taskCabSha) {
    throw 'Falta el CAB verificado. Ejecuta python scripts/fetch_boot_tools.py --windows-adb-driver.'
}
if (-not (Test-Path -LiteralPath $taskPackage)) {
    New-Item -ItemType Directory -Path $taskPackage | Out-Null
    & "$env:SystemRoot\System32\expand.exe" '-F:*' $taskCab $taskPackage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo extraer el CAB; conserva la carpeta para revisar.' }
}
foreach ($taskEntry in $taskHashes.GetEnumerator()) {
    $taskFile = Join-Path $taskPackage $taskEntry.Key
    if (-not (Test-Path -LiteralPath $taskFile -PathType Leaf) -or
        (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash -ne $taskEntry.Value) {
        throw ('Archivo de controlador distinto: ' + $taskEntry.Key)
    }
}
$taskCatalog = Get-AuthenticodeSignature -LiteralPath (Join-Path $taskPackage 'androidwinusba64.cat')
if ($taskCatalog.Status -ne 'Valid' -or
    $taskCatalog.SignerCertificate.Subject -notmatch 'Microsoft Windows Hardware Compatibility Publisher') {
    throw 'El catalogo x64 no tiene la firma Microsoft valida requerida.'
}
if ($VerifyPackageOnly) {
    [pscustomobject]@{ PackageVerified = $true; Files = $taskHashes.Count; DeviceQueried = $false; DriverInstalled = $false }
    return
}
$taskAdmin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $taskAdmin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'La instalacion requiere una terminal PowerShell como administrador.'
}
$taskPresent = @(Get-PnpDevice -PresentOnly)
$taskTargets = @($taskPresent | Where-Object { $_.InstanceId -match '^USB\\VID_0E8D&PID_201D&MI_01\\' })
if ($taskTargets.Count -ne 1) {
    throw 'Debe estar presente una unica interfaz ADB USB 0E8D:201D/MI01; el reloj debe arrancar con BOOT USB preparado.'
}
$taskOthers = @($taskPresent | Where-Object {
    $_.InstanceId -match '^USB\\VID_0E8D&PID_' -and
    $_.InstanceId -notmatch '^USB\\VID_0E8D&PID_201D(?:&MI_0[01])?\\'
})
if ($taskOthers.Count) { throw 'Desconecta otros dispositivos USB MediaTek antes de instalar este paquete.' }
$taskInstance = $taskTargets[0].InstanceId
$taskProblem = (Get-PnpDeviceProperty -InstanceId $taskInstance -KeyName DEVPKEY_Device_ProblemCode).Data
$taskService = (Get-PnpDeviceProperty -InstanceId $taskInstance -KeyName DEVPKEY_Device_Service -ErrorAction SilentlyContinue).Data
if ($taskProblem -eq 0 -and $taskService -eq 'WinUSB') {
    [pscustomobject]@{ PackageVerified = $true; Interface = '0E8D:201D/MI01'; Service = 'WinUSB'; DriverInstalled = $false; AlreadyReady = $true }
    return
}
if ($taskProblem -ne 28) { throw 'La interfaz tiene un estado distinto de controlador ausente; revisa el Administrador de dispositivos.' }
if (-not $PSCmdlet.ShouldProcess('Interfaz ADB 0E8D:201D/MI01', 'Instalar paquete WinUSB MediaTek firmado')) { return }
$taskLocal = Join-Path $taskRoot '.local\driver'
New-Item -ItemType Directory -Force -Path $taskLocal | Out-Null
$taskLog = Join-Path $taskLocal 'pnputil.log'
$taskPnpOutput = & "$env:SystemRoot\System32\pnputil.exe" /add-driver (Join-Path $taskPackage 'android_winusb.inf') /install 2>&1
$taskExit = $LASTEXITCODE
$taskPnpOutput | Out-File -LiteralPath $taskLog -Encoding utf8
if ($taskExit -notin @(0, 3010)) { throw 'PnPUtil fallo; revisa .local/driver/pnputil.log.' }
Start-Sleep -Seconds 2
$taskProblem = (Get-PnpDeviceProperty -InstanceId $taskInstance -KeyName DEVPKEY_Device_ProblemCode).Data
$taskService = (Get-PnpDeviceProperty -InstanceId $taskInstance -KeyName DEVPKEY_Device_Service).Data
if ($taskProblem -ne 0 -or $taskService -ne 'WinUSB') {
    throw 'El paquete se preparo pero la interfaz aun no esta lista; revisa el controlador sin repetir cambios a ciegas.'
}
[pscustomobject]@{ PackageVerified = $true; Interface = '0E8D:201D/MI01'; Service = 'WinUSB'; DriverInstalled = $true; FirmwareChanged = $false }
