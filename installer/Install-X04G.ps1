<#
.SYNOPSIS
Verifica e instala una pareja X04G firmada en el sistema original ya preparado.
.DESCRIPTION
Sin opciones efectua preflight de solo lectura. -Install permite instalar APK y
modulo, fijar UID y reiniciar exclusivamente el reloj USB seleccionado.
-NoReboot prepara y deja el modulo deshabilitado. -Disable deshabilita solo el
modulo propio; no desinstala APK ni borra configuracion. No flashea ni desbloquea.
#>
[CmdletBinding()]
param(
    [string]$PackageRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$AdbPath,
    [string]$AdbPrivateKey,
    [string]$Serial,
    [switch]$Install,
    [switch]$CheckOnly,
    [switch]$Disable,
    [switch]$Upgrade,
    [switch]$NoReboot
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'X04G.Installer.psm1') -Force
$arguments = @{}
foreach ($name in $PSBoundParameters.Keys) { $arguments[$name] = $PSBoundParameters[$name] }
$arguments.PackageRoot = $PackageRoot
$result = Invoke-X04GInstaller @arguments
if ($result.success) {
    switch ($result.phase) {
        'PREFLIGHT_PASSED' { Write-Host 'Comprobación correcta: reloj y paquete compatibles. No se instaló ni reinició nada.' }
        'PREPARED_DISABLED' { Write-Host 'Aplicación y módulo preparados; el módulo sigue deshabilitado. Ejecuta de nuevo Instalar sin -NoReboot para activarlo.' }
        'DISABLED_REBOOT_REQUIRED' { Write-Host 'Módulo deshabilitado. Reinicia el reloj para retirar la integración que siga cargada en memoria.' }
        'INSTALLED_RUNTIME_VERIFIED' { Write-Host 'Instalación completada: el carrusel confirmó la integración después del reinicio. Configura tus cámaras desde su pantalla.' }
        default { Write-Host 'Operación completada. Consulta el informe local.' }
    }
} else {
    Write-Host 'No se completó la operación.'
    switch -Regex ($result.errorCode) {
        'SERIAL|USB|SELECT_ONE|ADB_NOT_AUTHENTICATED' { Write-Host 'Conecta únicamente el Xiaomi por USB de datos, déjalo arrancado y comprueba el controlador y la autorización ADB.'; break }
        'ADB_PRIVATE_KEY' { Write-Host 'Indica con -AdbPrivateKey la clave privada que autorizaste al preparar ADB.'; break }
        'ARTIFACT|MANIFEST|ZIP|PACKAGE|RELEASE_VERSION|ADB_BINARY|ADB_COMPANION' { Write-Host 'El paquete local no supera la verificación. Usa la distribución completa correspondiente a esta versión.'; break }
        'DEVICE_COMPATIBILITY|SECURITY_STATE|DISPLAY|OEM_|SHARED_UID' { Write-Host 'La versión o el estado del reloj no coincide con lo admitido. Se detuvo antes de la instalación.'; break }
        'MAGISK|ZYGISK|DENYLIST' { Write-Host 'Revisa Magisk, Zygisk, el permiso de Superusuario de Shell y la lista de exclusión antes de continuar.'; break }
        'EXISTING_|MANAGED_|EXPLICIT_UPGRADE|PENDING_UPDATE' { Write-Host 'Existe una instalación que requiere revisión. Sólo se actualizan versiones gestionadas, deshabilitadas y compatibles con -Upgrade.'; break }
        'RUNTIME|BOOT_|REBOOT_' { Write-Host 'El arranque o el carrusel no confirmó el resultado. Consulta el informe antes de volver a instalar.'; break }
        'MODE_CONFLICT' { Write-Host 'Elige una sola acción: comprobar, instalar o deshabilitar.'; break }
        default { Write-Host 'Revisa la conexión y el estado del reloj en el informe local. No repitas la instalación sin comprobar ese estado.' }
    }
    if ($result.failureDisableConfirmed) { Write-Host 'El módulo quedó deshabilitado para el siguiente arranque.' }
    elseif ($result.failureDisableAttempted) { Write-Host 'No se pudo confirmar la deshabilitación. Revisa el reloj y el informe antes de reiniciarlo.' }
    if ($result.rebootRequiredForDisable) { Write-Host 'Queda pendiente reiniciar para retirar de memoria la integración anterior.' }
    Write-Host ('Código: ' + $result.errorCode + ' · Etapa: ' + $result.phase)
}
if ($result.reportPath) { Write-Host ('Informe local: ' + $result.reportPath) }
if (-not $result.success) { exit 1 }
exit 0
