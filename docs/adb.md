# Acceso ADB USB en Xiaomi Mi Smart Clock X04G

Esta ruta conserva el firmware, launcher, menús y carrusel originales. Cambia
BOOT para añadir Magisk 26.1 y un servicio que habilita ADB USB autenticado al
terminar el arranque. También desbloquea el arranque mediante SECCFG. El
restablecimiento de fábrica elimina las cuentas y configuración actuales; al
terminar hay que configurar de nuevo Google Home.

El destino comprobado es **X04G / mico_x04g, MediaTek 0x8167, ARMv7 de 32 bits,
Android 10/API 29, partición BOOT de 16 MiB y cabecera Android v2**. No trasladar
la receta a otro modelo ni escribir una imagen de otro reloj. Los scripts de
este repositorio construyen y verifican archivos en el PC; no flashean por sí
solos. El acceso físico se realiza con MTKClient y se verifica mediante lectura
posterior de las particiones.

## Herramientas

| Herramienta | Versión / fuente |
| --- | --- |
| Python | 3.12; Python 3 de Linux para construir BOOT |
| MTKClient | [bkerler/mtkclient](https://github.com/bkerler/mtkclient/tree/7cb40e727727dfe3faa065eefb29b16ed29bbdf1), commit `7cb40e727727dfe3faa065eefb29b16ed29bbdf1` |
| UsbDk, Windows x64 | [1.0.22](https://github.com/daynix/UsbDk/releases/tag/v1.0.22) |
| Android platform-tools | [Google](https://developer.android.com/tools/releases/platform-tools), `adb` |
| Magisk | [26.1, APK oficial](https://github.com/topjohnwu/Magisk/releases/download/v26.1/Magisk-v26.1.apk), SHA256 `ae1a02b1ab608a51d5bc9b323e0588d06d30d9987ac8da01f4710d76f705dccb` |
| Linux x86_64 | WSL2 Ubuntu para `magiskboot`; el builder lo extrae del APK anterior |

Instalar UsbDk con su instalador firmado. Para MTKClient se necesita un Python
normal de Windows y un entorno independiente:

```powershell
python scripts/fetch_boot_tools.py --mtkclient
$env:Path = "$PWD/.cache/toolchain/platform-tools-windows/platform-tools;$env:Path"
py -3.12 -m venv .venv-mtk
& ./.venv-mtk/Scripts/python.exe -m pip install -r .cache/toolchain/mtkclient/requirements.txt
```

El descargador verifica Magisk26.1, platform-tools37.0.1 y el commit de
MTKClient indicado; no instala controladores ni accede al USB. El enlace oficial
de platform-tools sirve una versión móvil: si Google lo actualiza y el hash ya
no coincide, el script se detiene. Revisa la versión y checksum de la nueva
distribución antes de actualizar ese pin; no omitas la comprobación.

La documentación de instalación de MTKClient explica los controladores Windows.
No se necesita instalar un controlador Fastboot para esta ruta BROM. Los datos
temporales, `.state`, `hwparam.json` y logs de MTKClient contienen identificadores
del propietario: mantenerlos fuera de Git junto a los respaldos.

## 1. Crear claves propias y el respaldo

En los ejemplos, `adb` y `python` deben apuntar a platform-tools y al Python de
MTKClient correspondientes. Ejecutar los comandos desde la raíz de este repo,
salvo el bloque que entra explícitamente al directorio de MTKClient.

Crear una clave nueva, independiente de las claves del equipo:

```powershell
New-Item -ItemType Directory -Force .local/adb-host, .local/backups | Out-Null
adb keygen .local/adb-host/adbkey
$env:ADB_VENDOR_KEYS = (Resolve-Path .local/adb-host/adbkey).Path
adb -P 5142 start-server
```

El puerto 5142 identifica el servidor **local del PC**, no ADB de red en el
reloj. La clave privada `adbkey` queda en el PC; sólo `adbkey.pub` entra en BOOT.
No publicar ninguna de las dos.

Abrir una terminal en `.cache/toolchain/mtkclient`, con el Python del entorno
creado. Preparar el lector **antes** de conectar el reloj. La entrada física es:

1. Desconectar su alimentación USB durante 10 segundos.
2. Mantener sólo **Volumen +** mientras se conecta al USB de datos del PC.
3. Mantenerlo unos 20 segundos y soltar. Dejar el cable conectado.
4. Comprobar que MTKClient identifica **0x8167**, eMMC y el GPT del X04G.

El estado de pantalla no prueba que BROM esté disponible: lo confirma la
identificación USB/DA de MTKClient. Si no lo captura, volver a preparar el lector
y repetir la conexión física. Un fallo de transporte a mitad de una operación
requiere una sesión nueva y lectura del estado actual, sin reintento automático.

Comandos de lectura de MTKClient, usando rutas absolutas a la carpeta privada:

```text
python mtk.py printgpt
python mtk.py r boot,seccfg boot_stock.img,seccfg_stock.bin
python mtk.py rf user_backup.bin
python mtk.py rf boot1_backup.bin --parttype boot1
python mtk.py rf boot2_backup.bin --parttype boot2
```

En cada comando sustituir los nombres de salida por rutas dentro de
`.local/backups`. Conservar la sesión DA y sus archivos locales mientras no se
reinicie ni desconecte el reloj. El GPT debe confirmar tamaños y particiones.
Guardar los hashes SHA256 (`Get-FileHash -Algorithm SHA256 <archivo>`) y una
segunda copia en otro disco; verificar ambos archivos. El respaldo USER más
BOOT1/BOOT2 no contiene una copia restaurable de todas las vinculaciones
RPMB/TEE. No demuestra recuperación de cuentas cifradas tras cambiar lockstate.

Para el laboratorio del PC, [extraer `super` y sus particiones OEM del respaldo
USER](extract-backup.md) con el lector incluido. Es una operación sobre archivos
que no requiere volver a conectar el reloj.

## 2. Construir BOOT con la pública nueva

Copiar el APK de Magisk a `.cache/toolchain/magisk/Magisk-v26.1.apk`, o usar la
ruta donde lo haya descargado `scripts/fetch_tools.py`. Verificar el SHA256 de
la tabla antes de usarlo. Obtener el hash del **boot_stock.img de esta unidad**.

Desde WSL2, en la raíz del repositorio (rutas Linux):

```bash
python3 tools/boot/build_boot.py \
  --boot .local/backups/boot_stock.img \
  --expect-stock-sha256 HASH_DEL_BOOT_STOCK_PROPIO \
  --magisk-apk .cache/toolchain/magisk/Magisk-v26.1.apk \
  --public-key .local/adb-host/adbkey.pub \
  --output .local/boot-build
```

Se crea `.local/boot-build/boot_adb_usb.img` y `manifest.json`. El directorio de
salida debe ser nuevo. El builder verifica BOOT completo de 16 MiB, el APK, la
herramienta, la estructura RSA, los archivos y metadatos del ramdisk stock
excepto `init`, kernel comprimido, DTB y cabecera. El hash final varía por la
clave propia y semilla de Magisk. Un hash de otro usuario no sirve para validar
tu imagen.

El servicio añadido se dispara en `sys.boot_completed=1`: provisiona la pública
en `/data/misc/adb/adb_keys` y solicita a init OEM la secuencia USB
`none` → `mtp,adb`. Conserva `ro.secure=1`, `ro.adb.secure=1`,
`ro.debuggable=0`, SELinux Enforcing y ADB TCP deshabilitado. No cambia
SYSTEM/VENDOR/PRODUCT/VBMETA. Magisk sí añade privilegios y su política; conservar
Enforcing no significa tener una política idéntica a stock.

## 3. Desbloquear, instalar y comprobar la lectura posterior

Volver a MTKClient con sesión válida. Si el reloj se desconectó o reinició,
preparar una sesión BROM nueva y releer BOOT/SECCFG antes de escribir.
Estos son comandos **de escritura**; los respaldos deben estar completos:

```text
python mtk.py da seccfg unlock
python mtk.py r seccfg seccfg_after_unlock.bin
python mtk.py w boot RUTA_ABSOLUTA_A_boot_adb_usb.img
python mtk.py r boot boot_after_write.img
```

Comprobar que la lectura de `boot_after_write.img` coincide byte por byte y en
SHA256 con `boot_adb_usb.img`. Para el SECCFG v4 de este destino se modifica el
primer sector de 512 bytes, con lockstate desbloqueado y autenticación propia;
comprobar que el resto de la partición coincide con el respaldo. No escribir el
SECCFG ni datos de seguridad de otra unidad. No ejecutar `da vbmeta`, borrados
de eMMC, escrituras a RPMB ni reemplazo de preloader.

El verificador de archivos comprueba BOOT completo, cabecera SECCFG v4 y cola
fuera del primer sector. No sustituye la autenticación SEJ que hace MTKClient:

```text
python tools/boot/verify_readback.py --boot .local/boot-build/boot_adb_usb.img --boot-readback .local/backups/boot_after_write.img --seccfg-stock .local/backups/seccfg_stock.bin --seccfg-readback .local/backups/seccfg_after_unlock.bin
```

Una lectura/ACK incierta no acredita una escritura correcta. Conservar los logs
privados, volver a capturar el estado y comparar antes de otra escritura.

Tras las verificaciones, desconectar 10 segundos y conectar **sin botones**.
Si aparece `Android Recovery / Can't load Android system`, seleccionar
**Factory data reset**, confirmar el borrado y esperar hasta que termine
`Wiping data`. Después elegir **Reboot system now** si vuelve al menú.
Completar otra vez la configuración oficial de Google Home y Wi-Fi.

## 4. Validar ADB y el sistema original

Volver a una terminal que tenga `ADB_VENDOR_KEYS` con la clave privada creada
en el paso 1. En Windows, el dispositivo compuesto MTP/ADB debe mostrar una
interfaz ADB con WinUSB y sin error de controlador. MTP solamente no es ADB.

### Controlador ADB en Windows

La configuración MTP+ADB del X04G usa **VID `0E8D`, PID `201D`, interfaz
`MI_01` para ADB**. El paquete MediaTek WinUSB firmado de Microsoft Update
Catalog incluye exactamente `USB\VID_0E8D&PID_201D&MI_01`. Su descarga y
verificación pueden hacerse antes del arranque modificado. Si el paquete ya
está en el almacén de controladores, Windows puede asignarlo al aparecer la
nueva interfaz; de lo contrario se instala con el helper siguiente. No hace
falta un INF personalizado ni Zadig.

Descargar el paquete público y verificarlo sin instalar:

```powershell
python scripts/fetch_boot_tools.py --windows-adb-driver
./tools/boot/install_adb_driver.ps1 -VerifyPackageOnly
```

Procedencia: [Microsoft Update Catalog, UpdateID
67446b50-e7f8-454a-9155-f48ddf362d01](https://www.catalog.update.microsoft.com/ScopedViewInline.aspx?updateid=67446b50-e7f8-454a-9155-f48ddf362d01).
El CAB tiene SHA256
`bcebf41bfc2c081544cc3ce4ebdc0b8d890f6411c1d2b925dc2f6b6a0ccd6c5b`.
El helper comprueba el CAB, los nueve archivos originales y la firma Microsoft
del catálogo x64. No se modifica ni publica un INF con identificadores de una
unidad particular.

Si ya aparece la interfaz ADB con servicio WinUSB y estado correcto, continuar
con `adb devices`. Si la interfaz `201D/MI_01` aparece sin controlador
(código 28), dejar el reloj arrancado normalmente con el BOOT preparado,
desconectar otros dispositivos USB MediaTek y ejecutar desde **PowerShell como
administrador**, en la raíz del repositorio:

```powershell
./tools/boot/install_adb_driver.ps1
```

El helper exige una única interfaz ADB `201D/MI_01`, instala el paquete firmado
mediante PnPUtil y vuelve a comprobar código de problema 0 y servicio WinUSB.
No reemplaza la función MTP, no abre ADB y no reinicia el reloj. `-WhatIf`
permite revisar la acción propuesta. Los registros locales quedan en
`.local/driver`, fuera de Git.

El PID `201C` corresponde al modo independiente encontrado en otros estados de
arranque; su descripción de controlador puede decir «Android ADB Interface»
incluso si la interfaz USB ofrece otro protocolo. Ese nombre por sí solo no
acredita ADB. Para esta ruta verificar `201D/MI_01` y después una sesión
autenticada con estado `device`.

```powershell
$env:ADB_VENDOR_KEYS = (Resolve-Path .local/adb-host/adbkey).Path
adb -P 5142 devices -l
```

Copiar el serial de la fila física del X04G a una variable de esta terminal:

```powershell
$clockSerial = 'SERIAL_DEL_X04G'
adb -P 5142 -s $clockSerial shell id
adb -P 5142 -s $clockSerial shell getprop ro.product.model
adb -P 5142 -s $clockSerial shell getprop ro.product.device
adb -P 5142 -s $clockSerial shell getprop ro.build.version.sdk
adb -P 5142 -s $clockSerial shell getprop sys.boot_completed
adb -P 5142 -s $clockSerial shell getprop ro.adb.secure
adb -P 5142 -s $clockSerial shell getenforce
adb -P 5142 -s $clockSerial shell getprop service.adb.tcp.port
adb -P 5142 -s $clockSerial shell getprop persist.adb.tcp.port
```

Esperado: estado `device`, `uid=2000(shell)`, modelo X04G, dispositivo mico_x04g,
API29, arranque1, RSA1, Enforcing, puertos TCP vacíos/0/−1. La presencia de un
reloj en `devices` sin estos valores no basta. Revisar en pantalla la hora,
menús originales, brillo automático, alarmas y Asistente antes de integrar la
app. Un shell UID0 no es el resultado esperado de adbd.

Si aparece `unauthorized`, comprobar que servidor, pública empaquetada y clave
privada pertenecen a la misma pareja; no desactivar RSA. Si sólo existe MTP,
revisar controlador y, desde una vía de mantenimiento ya disponible, el estado
protegido `/cache/x04g-adb-diag-v4/status`. El helper no abre un puerto de red
como alternativa.

## 5. Preparar Magisk para el módulo del carrusel

Instalar exactamente el mismo APK 26.1 y abrir su actividad:

```powershell
adb -P 5142 -s $clockSerial install -r .cache/toolchain/magisk/Magisk-v26.1.apk
adb -P 5142 -s $clockSerial shell am start -n com.topjohnwu.magisk/com.topjohnwu.magisk.ui.MainActivity
```

Si el manager ofrece **completar/reparar entorno** para instalar sus archivos de
ejecución, aceptar esa reparación y esperar el reinicio. No elegir
**instalación directa** ni actualizar Magisk: una instalación directa reescribe
BOOT y puede eliminar el servicio USB propio. Tras el regreso, verificar ADB y
pedir `shell su -c id`; permitir únicamente la petición reconocida de **Shell**
en el diálogo de Superusuario. Mantener desactivada la autorización automática
global.

Comprobar `su -c 'test -x /data/adb/magisk/busybox'` y `su -c 'magisk -v'`
(26.1/26100). Activar Zygisk desde los ajustes de Magisk. El módulo y el APK
propio se instalan siguiendo la documentación del carrusel de este repositorio.
El arranque utilizado admite un módulo Zygisk limitado sin reglas SELinux;
no se presenta como una instalación completa de todas las funciones de Magisk.

## Retirada y restauración

Deshabilitar primero el módulo propio y comprobar que regresan las páginas OEM.
Para retirar la autorización USB de este helper existe el marcador protegido
`/data/local/tmp/x04g-adb-usb.rollback`: crearlo como root y reiniciar conserva
el estado previo de claves y USB y deja el helper deshabilitado. La conexión
ADB puede perderse durante esa retirada; preparar antes la vía BROM.

Para volver a BOOT stock, escribir **el respaldo propio** con MTKClient y leerlo
de nuevo para verificar. No volver a bloquear el arranque con BOOT modificado.
Cambiar el lockstate otra vez puede requerir un nuevo restablecimiento; la
restauración de BOOT/SECCFG no promete recuperar cuentas o claves anteriores.

## Verificación local de las herramientas del repositorio

```text
python -m unittest discover -s tools/boot -p "test_*.py"
```

Las pruebas comprueban rechazo de claves inválidas, comentario local eliminado,
parser CPIO y el helper fijado. La construcción completa también se ha validado
en Linux/WSL con un BOOT original y una clave nueva, sin acceder al dispositivo.
La aceptación de cada instalación física sigue requiriendo las comprobaciones
del paso 4 y los ensayos de vídeo/audio del monitor.
