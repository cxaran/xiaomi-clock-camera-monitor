# Instalador para Windows

Instala la pareja APK/módulo de esta release en un **Xiaomi Mi Smart Clock X04G
ya preparado con ADB USB autenticado, root y Magisk con Zygisk**. Requiere
Windows **x64** con PowerShell **5.1 o posterior**.

El instalador no desbloquea el reloj, no restablece datos y no escribe firmware.
Si tu unidad sigue de fábrica, completa primero [el respaldo y la preparación
de ADB/Magisk](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/adb.md). Esa preparación es independiente y puede requerir
restablecimiento de fábrica; el instalador no la automatiza.

## Pasos rápidos

1. Descarga el ZIP del instalador de la release oficial y **extrae todo su
   contenido** en una carpeta del PC. No ejecutes los archivos dentro del ZIP ni
   mezcles APK, módulo o manifiesto de releases diferentes.
2. Deja el reloj arrancado normalmente, mostrando la hora y conectado al PC con
   un cable USB de datos. ADB debe estar autorizado y el reloj ya debe tener
   Magisk **26+**, su entorno de ejecución disponible y **Zygisk habilitado**.
   El proceso `com.google.android.apps.mediashell` no debe estar en DenyList.
3. Haz doble clic en **`Check-X04G.cmd`** para comprobar el paquete y el reloj.
   Si usaste una clave ADB propia al preparar BOOT, consulta el apartado de
   claves más abajo antes de continuar.
4. Si la comprobación es correcta, haz doble clic en **`Install-X04G.cmd`** y
   sigue las indicaciones. Si aparece una petición de Superusuario en el reloj,
   autoriza únicamente la solicitud reconocida de **Shell**. Es el permiso root
   de Android necesario para preparar el módulo, no una petición de administrador
   de Windows. No actives una autorización root automática para todas las apps.
5. Deja el cable conectado mientras instala y reinicia. Al volver la hora,
   comprueba las páginas nuevas del carrusel y añade tus cámaras desde
   **Cámaras → pulsación prolongada → Administrar**.

La aplicación es un paquete compañero: **no aparece como una app lanzable con
icono**, y el APK por sí solo no modifica el carrusel.

## Qué comprueba y hace

El instalador verifica los hashes del paquete y utiliza exclusivamente su
servidor ADB local **5142**. Selecciona un transporte **USB autenticado** del
X04G; no instala en un emulador ni usa ADB por la red. Si no puede identificar
el destino sin ambigüedad, se detiene.

La compatibilidad está fijada a esta plataforma:

| Elemento | Requisito |
| --- | --- |
| Modelo / dispositivo | X04G / `mico_x04g` |
| Android / ABI | API 29 / `armeabi-v7a` |
| Launcher activo | `/product/app/com.google.assistant.launcher/app.apk` |
| Versión / versionCode OEM | `11.51.6.416171605` / `111706` |
| SHA-256 OEM | `1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21` |
| Acceso root | `su` disponible para Shell y Magisk 26+ con Zygisk |

Otro hash, ruta o versión del launcher necesita una adaptación de la integración;
no omitas esa comprobación. Instalar sólo el manager de Magisk no proporciona
por sí mismo root ni Zygisk.

Tras las comprobaciones, instala el APK y el módulo inicialmente deshabilitado.
Lee los UID reales del launcher y MediaShell en **tu unidad**, comprueba que
coincidan y prepara `uid.pin` con propietario/permisos protegidos en el módulo
preparado. No utiliza el UID de otro reloj. Mantiene la extensión deshabilitada
hasta terminar las comprobaciones y verifica la integración real después del
reinicio. El launcher original no se sustituye ni se vuelve a firmar.

La instalación usa únicamente el ZIP propio verificado y prepara su carpeta de
forma atómica con BusyBox de Magisk. No ejecuta los hooks de un instalador de
módulos genérico. La actualización se prepara por separado y conserva la
versión anterior deshabilitada hasta el reinicio.

Si ocurre un fallo durante la instalación, intenta dejar deshabilitada la
extensión y registra el resultado. Una desconexión puede impedir confirmar esa
limpieza: revisa el recibo antes de repetir operaciones. No hay borrado, flasheo
ni desbloqueo automático como alternativa.

## Comprobar sin instalar: dry-run

**`Check-X04G.cmd`** ejecuta la comprobación sin instalar APK, preparar el módulo
ni reiniciar. Puede iniciar su servidor ADB local y solicitar autorización de
Shell para leer el estado de Magisk.

También puedes abrir PowerShell en la carpeta extraída y ejecutar:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\installer\Install-X04G.ps1" -PackageRoot "." -CheckOnly
```

Sin `-Install`, `-CheckOnly` ni `-Disable`, el script se comporta como
`-CheckOnly`. `ExecutionPolicy Bypass` se limita al proceso de este comando;
no necesitas cambiar la política global de PowerShell.

## Usar tu clave ADB o seleccionar el USB

Por defecto utiliza la clave local `%USERPROFILE%\.android\adbkey`. Si el BOOT
del reloj autoriza otra pareja, indica **esa misma clave privada**; permanece
en tu PC. Por ejemplo:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\installer\Install-X04G.ps1" -PackageRoot "." -AdbPrivateKey "C:\X04G-private\adbkey" -CheckOnly
```

Una vez comprobado, cambia `-CheckOnly` por `-Install` para instalar. La clave
debe corresponder a la pública que ya está autorizada en el reloj; el instalador
no elude `unauthorized` ni desactiva la autenticación RSA.

`-Serial "SERIAL_USB_DE_TU_RELOJ"` permite seleccionar expresamente un USB,
manteniendo las mismas comprobaciones. `-AdbPath "RUTA\adb.exe"` permite indicar
otra ubicación de ADB, pero el ejecutable debe coincidir con la versión/hash
fijados por el manifiesto del paquete. Normalmente se utiliza
`platform-tools\adb.exe` incluido en el ZIP.

## Preparar sin reiniciar

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\installer\Install-X04G.ps1" -PackageRoot "." -Install -NoReboot
```

Este modo deja el módulo **deshabilitado**. Preparar archivos no equivale a
activar el carrusel; no esperes páginas nuevas por reiniciar manualmente.
Para activar después, vuelve a usar **el mismo paquete** con `Install-X04G.cmd`
o `-Install` sin `-NoReboot`. Sólo reanuda una preparación gestionada por este
instalador, deshabilitada y verificada como la misma pareja: relee el UID antes
de activar y reiniciar. No reinstala los archivos ya preparados.

## Actualizar una instalación gestionada

Deshabilita primero la extensión con `Disable-X04G.cmd`. Una pareja diferente
requiere además la opción explícita `-Upgrade` del script; no se trata como una
reanudación de `-NoReboot`. La configuración de cámaras se conserva.

El instalador rechaza módulos activos o desconocidos. Una instalación manual
anterior puede requerir [la guía manual](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/release-install.md); no borres
sus archivos ni eludas el rechazo para convertirla en una instalación gestionada.

## Retirar la extensión

Haz doble clic en **`Disable-X04G.cmd`**, con el mismo reloj preparado y conectado.
Deshabilita el módulo para devolver el carrusel OEM; no desinstala el APK ni
borra la configuración de cámaras. El reinicio indicado es necesario para
descargar las páginas que ya estén en el proceso.

El modo equivalente es:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\installer\Install-X04G.ps1" -PackageRoot "." -Disable
```

No borres datos del launcher ni de Google Home para retirar las páginas. Si el
reloj no arranca o no hay acceso ADB, utiliza [tu respaldo y la vía de
recuperación](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/adb.md#retirada-y-restauración).

## Resultado y configuración propia

El recibo local se guarda en
`%LOCALAPPDATA%\X04GCameraMonitor\Installer\Runs\<UTC>-<GUID>\report.json`.
La consola indica su ubicación y si quedaron tareas pendientes. El recibo no
es una garantía de imagen, sonido o estabilidad: verifica esas funciones en
el reloj con [la lista de comprobación](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/verification.md).

No se incluyen cámaras, cuentas, contraseñas ni una configuración de domótica.
Añade las direcciones y credenciales RTSP de tus cámaras en el editor integrado.
El Portón opcional tampoco lleva un dispositivo, clave LAN o señal RF
preconfigurados; no necesitas configurarlo para ver cámaras. Consulta
[el uso de la aplicación](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/app.md).

## Alcance de las pruebas

El controlador se comprobó con 53 casos simulados en Windows PowerShell 5.1 y
PowerShell 7, incluidos fallos de instalación, UID, reinicio y diagnóstico. Las
pruebas no utilizaron dispositivos ni inventario USB reales. También se probó
el manejo real de argumentos, Unicode y tiempos de espera con procesos locales
inertes. El puente Java del módulo aceptó los pins RC2 en el emulador; la
inserción persistente mediante Zygisk y una instalación completa por este
instalador siguen pendientes de comprobar en otro X04G físico.

El instalador sólo declara éxito si el reloj confirma `INSTALLED` después del
reinicio. Copiar archivos o volver a mostrar la hora no basta; si no recibe esa
confirmación, informa del fallo e intenta deshabilitar la extensión.
