# Instalar una release en otro Xiaomi X04G

Guía de **v0.1.0-rc.2**, versión preliminar. El APK de esta release usa
`versionName=0.1.0-rc.2` y `versionCode=3`. La compatibilidad está limitada al
launcher indicado en esta guía; no es una instalación universal para cualquier
firmware X04G.

En Windows puedes usar el [instalador portable](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/installer/README.md), que verifica la compatibilidad, instala la pareja y configura el UID automáticamente. Los pasos siguientes son la alternativa manual.

La instalación utiliza **dos archivos de la misma release**:
`x04g-home.apk` y `x04g-camera-carousel-disabled.zip`. El módulo comprueba el
hash y la firma del APK: no mezcles versiones ni sustituyas el APK por uno
recompilado con otra clave.

El APK es un paquete compañero, **no una aplicación con icono para abrir**.
Instalarlo solo no añade Cámaras al carrusel. El ZIP se entrega deliberadamente
**deshabilitado y sin UID configurado**, para que no se active en otro proceso.

## 1. Preparar el reloj y comprobar compatibilidad

Necesitas ADB USB autenticado, acceso `su`, Magisk **26+ con Zygisk habilitado**
y el sistema original arrancado y configurado con Google Home. La ruta de
arranque documentada usa Magisk 26.1. Si Magisk ofrece completar/reparar su
entorno, sigue [la preparación de Magisk](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/adb.md#5-preparar-magisk-para-el-módulo-del-carrusel).
Instalar únicamente el APK del manager no proporciona root ni Zygisk.

Si tu reloj sigue de fábrica, completa antes la [guía de respaldo, desbloqueo
y ADB](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/adb.md). Construye BOOT con el respaldo y la clave pública de **tu propia
unidad**. No hay un BOOT universal para descargar. Esa ruta requiere un
restablecimiento de fábrica y volver a configurar las cuentas, Wi-Fi y Google
Home. La instalación del APK y el módulo, una vez preparado ADB/Magisk, no
requiere otro restablecimiento.

En el PC instala Android Platform Tools y conserva tu clave privada ADB. En los
ejemplos se usa PowerShell y el servidor local **5142**; no es un puerto de red
abierto en el reloj. Sustituye las rutas y el serial por los tuyos:

```powershell
$env:ADB_VENDOR_KEYS = (Resolve-Path 'RUTA_A_TU_CLAVE/adbkey').Path
adb -P 5142 start-server
adb -P 5142 devices -l
$clockSerial = 'SERIAL_DE_TU_X04G'
adb -P 5142 -s $clockSerial shell getprop ro.product.model
adb -P 5142 -s $clockSerial shell getprop ro.product.device
adb -P 5142 -s $clockSerial shell getprop ro.build.version.sdk
adb -P 5142 -s $clockSerial shell getprop ro.product.cpu.abi
adb -P 5142 -s $clockSerial shell pm path com.google.assistant.launcher
adb -P 5142 -s $clockSerial shell sha256sum /product/app/com.google.assistant.launcher/app.apk
adb -P 5142 -s $clockSerial shell dumpsys package com.google.assistant.launcher
adb -P 5142 -s $clockSerial shell su -c id
```

La fila de ADB debe corresponder al reloj físico y tener estado `device`.
`su -c id` debe devolver `uid=0`; autoriza en Magisk únicamente la solicitud
reconocida de **Shell**. Comprueba estos valores antes de instalar:

| Elemento | Valor compatible |
| --- | --- |
| Modelo / dispositivo | X04G / `mico_x04g` |
| Android / ABI | API 29 / `armeabi-v7a` |
| APK activo del launcher | `/product/app/com.google.assistant.launcher/app.apk` |
| `versionName` / `versionCode` en `dumpsys` | `11.51.6.416171605` / `111706` |
| SHA-256 del launcher | `1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21` |

Un launcher actualizado en `/data/app`, otro hash o una versión distinta
necesitan adaptación de la integración. No cambies un pin para saltarte la
comprobación. El reloj, sus menús y sus funciones OEM deben funcionar antes de
añadir la extensión. Consulta [la arquitectura y los requisitos](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/carousel.md).

## 2. Descargar y verificar la pareja

Descarga `x04g-home.apk`, `x04g-camera-carousel-disabled.zip`, `SHA256SUMS.txt`
y `release-manifest.json` desde esa release del repositorio. Guárdalos en una
carpeta del PC y abre PowerShell en ella. Comprueba los dos binarios contra
`SHA256SUMS.txt`:

```powershell
foreach ($name in @('x04g-home.apk', 'x04g-camera-carousel-disabled.zip')) {
    $pattern = '^[0-9a-fA-F]{64}\s+\*?' + [regex]::Escape($name) + '$'
    $lines = @(Get-Content -LiteralPath '.\SHA256SUMS.txt' | Where-Object { $_.Trim() -match $pattern })
    if ($lines.Count -ne 1) { throw "Falta un hash único para $name" }
    $expected = ($lines[0].Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $name).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Hash incorrecto: $name" }
    Write-Output "Verificado: $name"
}
```

El manifiesto de la release contiene los hashes y pins públicos. No contiene
configuración de una unidad particular. No continúes si la comprobación falla.

El ZIP incluye `provenance.json` con el hash del APK para el que se construyó.
Esta comprobación adicional verifica que descargaste una pareja coherente;
no sustituye la comparación anterior con los hashes de la release:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path '.\x04g-camera-carousel-disabled.zip').Path)
try {
    $entry = $zip.GetEntry('provenance.json')
    if ($null -eq $entry) { throw 'El módulo no contiene provenance.json' }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { $pins = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$apkHash = (Get-FileHash -Algorithm SHA256 '.\x04g-home.apk').Hash.ToLowerInvariant()
if ($apkHash -ne $pins.apk_sha256) { throw 'El APK y el módulo no son de la misma pareja' }
```

Si estás actualizando, conserva la pareja anterior y deshabilita primero su
módulo siguiendo el apartado de retirada de esta guía. Un error
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` indica una firma diferente: no continúes
mezclando APK y módulo ni borres datos OEM para resolverlo.

## 3. Instalar sin activar todavía

Desde esa carpeta, con `$clockSerial` definido en la misma terminal:

```powershell
adb -P 5142 -s $clockSerial install -r .\x04g-home.apk
adb -P 5142 -s $clockSerial push .\x04g-camera-carousel-disabled.zip /data/local/tmp/x04g-camera-carousel-disabled.zip
adb -P 5142 -s $clockSerial shell
```

Ahora estás dentro de Android. Los siguientes comandos se ejecutan en esa
shell, no en PowerShell:

```sh
su
id
magisk -v
test -x /data/adb/magisk/busybox && echo 'Magisk runtime disponible'
magisk --install-module /data/local/tmp/x04g-camera-carousel-disabled.zip
```

`id` debe mostrar root y la instalación debe terminar correctamente e indicar
que el módulo queda deshabilitado. **No reinicies aún.** En los ajustes del
manager Magisk, Zygisk debe estar habilitado; el proceso
`com.google.android.apps.mediashell` no debe quedar excluido por DenyList.
La activación se completará con el reinicio del paso 5.

## 4. Obtener el UID de esta unidad y preparar el pin

En la misma shell root, consulta ambos paquetes:

```sh
cmd package list packages -U com.google.assistant.launcher
cmd package list packages -U com.google.android.apps.mediashell
```

Sus UID deben coincidir y estar entre **10000 y 19999**. No uses un UID copiado
de otra unidad. Este bloque obtiene el del launcher y sólo escribe el pin si
ambos paquetes coinciden y existe la instalación preparada y deshabilitada:

```sh
UID_OEM=$(cmd package list packages -U com.google.assistant.launcher | sed -n 's/^package:com\.google\.assistant\.launcher uid:\([0-9][0-9]*\)$/\1/p')
UID_CAST=$(cmd package list packages -U com.google.android.apps.mediashell | sed -n 's/^package:com\.google\.android\.apps\.mediashell uid:\([0-9][0-9]*\)$/\1/p')
MOD=/data/adb/modules_update/x04g_home_bootstrap_direct01
case "$UID_OEM" in
  1[0-9][0-9][0-9][0-9])
    if [ "$UID_OEM" = "$UID_CAST" ] && [ -d "$MOD" ] && [ -f "$MOD/disable" ]; then
      printf '%s\n' "$UID_OEM" > "$MOD/uid.pin" &&
      chown 0:0 "$MOD/uid.pin" &&
      chmod 0600 "$MOD/uid.pin" &&
      cat "$MOD/uid.pin" &&
      ls -l "$MOD/uid.pin"
    else
      echo 'No activar: UID distintos o instalación preparada ausente/no deshabilitada'
    fi
    ;;
  *) echo 'No activar: no se obtuvo un UID válido de esta unidad' ;;
esac
```

Comprueba el número y que `ls -l` muestre propietario `root root` y permisos
`-rw-------`. El archivo debe ser regular, tener un único enlace y contener
cinco dígitos, con un salto de línea opcional. Puedes comprobar tamaño y enlaces:

```sh
stat -c 'modo=%a uid=%u gid=%g bytes=%s enlaces=%h' "$MOD/uid.pin"
```

Se espera `modo=600 uid=0 gid=0 bytes=6 enlaces=1` para el bloque anterior.
`modules_update` es la carpeta que prepara la instalación CLI de Magisk en
Android; durante el reinicio pasa a `modules`. No escribas el pin en una carpeta
activa antigua. Si ya reiniciaste o no existe la carpeta preparada, vuelve a
instalar el ZIP deshabilitado y repite este paso.

## 5. Activar y verificar

Sólo después de verificar el pin, elimina el marcador de la carpeta preparada
y reinicia desde la misma shell root:

```sh
rm /data/adb/modules_update/x04g_home_bootstrap_direct01/disable
sync
reboot
```

Espera a que vuelva la hora original y a que ADB regrese. Desde PowerShell:

```powershell
adb -P 5142 -s $clockSerial shell getprop sys.boot_completed
adb -P 5142 -s $clockSerial shell getprop ro.adb.secure
adb -P 5142 -s $clockSerial shell getenforce
adb -P 5142 -s $clockSerial logcat -d -s X04GBootstrap X04GRuntime
```

Se espera arranque `1`, ADB autenticado `1` y `Enforcing`. Busca
`stage=app_bootstrap result=dispatched` y después el estado real
`state=INSTALLED` de `X04GRuntime`: el dispatch solo no confirma la inserción.
Desliza el carrusel y verifica que aparecen las páginas nuevas, que puedes
volver al reloj y que siguen disponibles sus menús, alarmas y brillo automático.
El APK OEM debe conservar el mismo hash.

En **Cámaras**, mantén pulsado y abre **Administrar**. Añade tus cámaras con
sus direcciones y cuentas RTSP locales, prueba y guarda. No hay cámaras,
contraseñas ni configuración de otro usuario precargadas. El Portón opcional
tampoco incluye dispositivo, clave LAN ni señal RF: sin configuración no puede
accionar nada. Consulta [uso y configuración](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/app.md).

Configura las cámaras desde la página integrada: `PreviewActivity` ejecutada
mediante ADB tiene un UID/almacén distinto y no configura el carrusel. Comprueba
imagen visible, zoom, selección, sonido y cierre del vídeo al volver al reloj
con [la lista de verificación](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/verification.md). Una conexión o un log de éxito
no acreditan por sí solos imagen, sonido ni estabilidad prolongada.

## Retirar o volver a la pareja anterior

Para deshabilitar las páginas, abre otra vez `adb -P 5142 -s $clockSerial shell`,
entra con `su` y ejecuta:

```sh
touch /data/adb/modules/x04g_home_bootstrap_direct01/disable
sync
reboot
```

Después del reinicio comprueba el carrusel OEM. No borres datos del launcher ni
de Google Home. El APK compañero puede quedarse instalado sin activar páginas;
puedes desinstalar `org.x04g.home` una vez deshabilitado el módulo si deseas
retirarlo por completo. Para volver a una versión anterior se necesita **su
pareja completa**, con firma compatible, y preparar de nuevo el UID propio.
Si Android rechaza la firma o el descenso de versión, conserva la extensión
deshabilitada y revisa la migración antes de desinstalar o borrar datos. La
desactivación del módulo ya devuelve el carrusel OEM sin hacer ese cambio.

Si Android no arranca o no tienes ADB, usa la [vía de recuperación y el respaldo
propio](https://github.com/cxaran/xiaomi-clock-camera-monitor/blob/v0.1.0-rc.2/docs/adb.md#retirada-y-restauración). La reversión de las páginas no requiere
restaurar BOOT; restaurar el arranque es una operación distinta que exige sus
lecturas de comprobación. No vuelvas a bloquear el arranque con BOOT modificado.
