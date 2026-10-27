# Emulador del sistema original X04G

Este entorno ejecuta el framework, las APK, los recursos, los menús y el carrusel
del Xiaomi X04G sobre una placa virtual Goldfish. No sustituye la interfaz por
Launcher3 ni por una recreación web. El código Java original es ARM de 32 bits;
el kernel y algunos servicios de la placa virtual son ARM64.

La composición está fijada al firmware X04G usado por esta versión. Los archivos
de otro firmware se rechazan por SHA-256. Las imágenes del fabricante se obtienen
del respaldo propio: el repositorio no incluye APK de Google/Xiaomi, cuentas,
datos de usuario, claves ADB ni configuraciones de cámaras.

## Componentes

| Componente | Procedencia y función |
| --- | --- |
| Framework, ART32, Bionic32, Launcher, Assistant Core, MediaShell y overlays | Particiones `system`, `product` y `vendor` del respaldo propio |
| init64, bibliotecas y servicios virtuales de placa | Imagen oficial ARM64/API29 del SDK, revisión 08 |
| Goldfish/GLES/gralloc32, mapper32, HAL Wi-Fi y transporte Bluetooth | Adaptaciones AOSP incluidas en la receta; no son controladores MediaTek |
| Kernel | Goldfish AOSP con dispositivo táctil virtual; fuente, configuración y compilación en [recipe/kernel](../emulator/recipe/kernel/README.md) |
| Nuevas páginas | El mismo APK compañero y las mismas clases de integración que se instalan en el reloj |
| Datos iniciales | `/data` nuevo; únicamente se precarga la clave pública ADB generada por quien ejecuta el laboratorio |

El perfil usa 800×480, 240 dpi, 1 GiB de RAM, `ro.config.low_ram=true`, heap de
crecimiento 128 MiB y máximo 256 MiB. La ejecución ARM mediante TCG en un PC x86
es lenta: permite comprobar recursos, estados, navegación y ciclos de vida;
no predice los FPS, el consumo del decodificador MediaTek ni el audio físico.

## 1. Preparar Linux o WSL2

Se necesita Linux x86_64 y Python 3.12 o posterior. En Windows, abra una terminal
de Ubuntu en WSL2. Guarde los árboles de trabajo y las imágenes generadas en el
filesystem Linux, por ejemplo `/var/tmp/x04g`; los metadatos SELinux y propietarios
numéricos no deben depender de una carpeta NTFS. Reserve al menos 20 GiB libres.

En Ubuntu puede preparar las herramientas con:

```bash
sudo apt update
sudo apt install python3 adb openjdk-21-jdk e2fsprogs util-linux \
  libpulse0 libnss3 libx11-6 libxcb1 libgl1 cmake ninja-build
command -v adb
java --version
javac --version
```

Use Python 3.12 o posterior y un JDK 17/21 con el módulo `jdk.jdi`. Si su
distribución proporciona otra versión, instale las versiones indicadas antes
de seguir. La ruta devuelta por `command -v adb` sustituye a `/ruta/platform-tools/adb`
en los ejemplos; no use `adb.exe` de Windows dentro del runner Linux.

Compruebe las bibliotecas del motor con `ldd <motor>/emulator-headless` y `ldd
<motor>/qemu/linux-x86_64/qemu-system-aarch64-headless` no deben mostrar
`not found`. No hace falta KVM para este perfil: usa `-accel off`.

Use una copia de Linux de Android Platform Tools. Las claves de Windows y Linux
son independientes a menos que usted las copie expresamente; aquí se genera una
clave nueva para el laboratorio:

```bash
mkdir -p /var/tmp/x04g/keys
chmod 700 /var/tmp/x04g/keys
umask 077
/ruta/platform-tools/adb keygen /var/tmp/x04g/keys/adbkey
```

La clave privada permanece fuera del repositorio. `build_images.py` sólo lee el
archivo `.pub` y elimina su etiqueta de usuario/equipo antes de precargarlo.

## 2. Descargar las referencias oficiales

Desde la raíz del repositorio:

```bash
python3 emulator/fetch_dependencies.py \
  --cache /var/tmp/x04g/downloads \
  --output /var/tmp/x04g/dependencies
```

Se descargan y verifican por tamaño y SHA-256:

- `arm64-v8a-29_r08.zip`, imagen oficial Android 10/API29.
- El motor Linux del commit `734fb74db98890a73098df214ec0296e4c9fd096` de
  `platform/prebuilts/android-emulator`.

Las URL y los hashes están en [sdk-pins.json](../emulator/recipe/sdk-pins.json) y
[engine-pins.json](../emulator/recipe/engine-pins.json). No cambie el motor por la
versión más reciente sin revisar el protocolo gráfico y los dispositivos de
entrada: este perfil depende de esa pareja kernel/motor.

## 3. Componer imágenes a partir del respaldo propio

Prepare las particiones lógicas **ext4 sin compresión** `system.img`,
`product.img` y `vendor.img` de su X04G. No use `userdata` del reloj ni una copia
de `/data` de otro emulador. Un respaldo de `boot` y `seccfg` sirve para restaurar
el arranque, pero no contiene el framework ni las APK requeridas aquí.
La guía [Extraer las particiones del respaldo](extract-backup.md) explica cómo
obtener esas particiones desde `USER` o `super` con el extractor incluido.

El comando siguiente monta las cinco fuentes como sólo lectura, compone los
árboles, crea imágenes nuevas y desmonta sus propios recursos al terminar:

```bash
sudo python3 emulator/mount_inputs.py mount \
  --state /var/tmp/x04g/source-mounts-01 \
  --oem-system /ruta/respaldo/system.img \
  --oem-product /ruta/respaldo/product.img \
  --oem-vendor /ruta/respaldo/vendor.img \
  --sdk-sysdir /var/tmp/x04g/dependencies/sdk/arm64-v8a \
  --adb-public-key /var/tmp/x04g/keys/adbkey.pub \
  --build-output /var/tmp/x04g/images-01
```

Use directorios nuevos para `--state` y `--build-output`. No se sobrescriben
resultados anteriores. En WSL conviene conservar **montaje y construcción en el
mismo comando**, como arriba: una nueva invocación de `wsl.exe` puede entrar en
otro espacio de montajes y ver directorios vacíos.

El compositor verifica los 3.778 nodos de la receta, conserva los 130 APK OEM y
reconstruye sólo 20 archivos mediante deltas verificados. Los 18 archivos
adicionales corresponden a hardware virtual AOSP. Doce ELF virtuales tienen
redactada su ruta de compilación mediante sustituciones de igual longitud; la
receta conserva el hash original y el de distribución. No se modifica un APK OEM.

Las imágenes nuevas usan ext4 y contenedores GPT/liblp compatibles con el SDK.
La auditoría vuelve a montar **las imágenes generadas** como sólo lectura y
compara contenido, enlaces, modo, UID/GID y xattrs. `images.json` registra sus
hashes. La verificación AVB se deshabilita únicamente para estas imágenes de PC;
**no son imágenes para flashear al Xiaomi**.

Las imágenes del PC también usan `ro.debuggable=1`, `ro.adb.secure=0` y el
backend ADB del invitado en el puerto 5555 para depuración del laboratorio.
El servidor ADB del host queda en loopback y el arranque usa la red NAT del
emulador. Estos ajustes pertenecen al PC; no describen la configuración ADB
segura del reloj instalado ni deben trasladarse como firmware al dispositivo.

Si un montaje se interrumpe, cierre exclusivamente los recursos registrados:

```bash
sudo python3 emulator/mount_inputs.py unmount \
  --state /var/tmp/x04g/source-mounts-01
```

El modo avanzado acepta filesystem roots ya montados mediante
[`inputs.example.json`](../emulator/inputs.example.json). `compose.py
--verify-only` valida las fuentes y deltas sin producir un árbol. Use
`build_images.py --help` para separar la composición de su política de montajes.

## 4. Arrancar el perfil aislado

```bash
python3 emulator/run_oem.py \
  --images /var/tmp/x04g/images-01/images.json \
  --engine /var/tmp/x04g/dependencies/engine \
  --adb /ruta/platform-tools/adb \
  --adb-private-key /var/tmp/x04g/keys/adbkey \
  --state /var/tmp/x04g/session-01 \
  --headless
```

Los puertos están separados del ADB físico: servidor **5102**, consola **5602**,
transporte **emulator-5602**. El operador rechaza puertos ocupados y valida todos
los hashes antes de arrancar. No reinicia ADB global ni busca un destino USB.
El proceso dispone de su propio AVD y `/data` escribible; las fuentes originales
permanecen intactas. Guarde el `session.json` devuelto y use esa ruta en controles.

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json status
```

Espere a `sys.boot_completed=1`. Los logs están en el directorio de sesión.
El primer arranque optimiza numerosas APK ARM y puede tardar varios minutos.
La existencia del proceso por sí sola no prueba que Android haya arrancado.

Antes de trabajar con las páginas, establezca luz ambiente de laboratorio:

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json light 500
```

El valor inicial puede ser 0 lux. En ese ambiente el launcher original entra en
LOW_LIGHT y oculta su pager; una página seleccionada por código puede quedar
inactiva. El sensor y la política de brillo siguen siendo los del sistema.
El control de consola debe devolver `OK`; un código de salida cero acompañado
de `KO` no acredita que se haya aplicado el valor.

Para conectar la Wi-Fi simulada del motor mediante el servicio OEM:

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json wifi
```

La red se llama `AndroidWifi` y pertenece al emulador, no al Wi-Fi de la vivienda.
La acción solicita conexión; compruebe el estado de Android antes de iniciar
RTSP. La asociación de red no completa el alta de Google Home.

## 5. Abrir y comprobar la interfaz OEM

Un `/data` limpio comienza en la configuración original. El launcher consulta
el estado de Cast/Assistant y puede conservar oculto su Home hasta completar el
alta. Abrir su Activity desde ADB no sustituye esa condición. Use el flujo normal
de Google Home para una prueba de aprovisionamiento completa, o el adaptador
explícito de laboratorio documentado con la integración para pruebas visuales
sin cuentas. Esa adaptación debe limitarse al emulador y declararse en el
resultado; no reproduce el estado de provisión del dispositivo físico.

La Activity original se puede solicitar con:

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json launcher
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json capture /var/tmp/x04g/oem-clock.png
```

Para una vista interactiva se puede conectar un cliente de pantalla ADB al
servidor `tcp:127.0.0.1:5102`, seleccionando expresamente `emulator-5602`.
No use el dispositivo predeterminado si tiene el reloj conectado al PC.
El motor fijado es headless; una ventana vacía del host no representa la
pantalla Android. Las capturas de `controls.py` provienen del Android invitado.

## 6. Sensores y gestos virtuales

```bash
# Luz virtual en lux. La curva de brillo y sus recursos siguen siendo OEM.
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json light 5
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json light 2000

# Desplazamiento horizontal a través de la pantalla.
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json swipe 740 240 60 240 450
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json swipe 60 240 740 240 450

# Pulsación larga en una posición: inicio y fin iguales.
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json swipe 400 240 400 240 1500
```

El sensor de luz virtual entrega valores a `SensorManager`; no emula el chip ni
calibra la luminancia física. La temperatura meteorológica requiere Internet y
no corresponde a un sensor de temperatura virtual. Los botones de volumen,
el interruptor físico de micrófono, Bluetooth y las alarmas deben verificarse
por separado. Este arranque mantiene el audio del host deshabilitado: escribir
audio en Android no demuestra que se escuche en el PC.

Los gestos introducidos por ADB son entradas virtuales, no una garantía de que
un menú se haya abierto. Verifique la captura siguiente y el estado visible.

## 7. Probar modificaciones

Compile la app con las instrucciones del repositorio. Instale el APK compañero
sólo en el serial del laboratorio y cargue la integración del carrusel siguiendo
su documentación. Instalar el APK por sí solo no crea una página OEM.

Use un stream RTSP sintético o una cámara de prueba. Compruebe selección por
pulsación larga, doble toque para zoom y retorno, desplazamiento de la imagen,
sonido/silencio y cierre del stream al volver al reloj. Las páginas precargadas
del pager no deben iniciar otro reproductor. Los nombres, direcciones y claves
de cámaras se configuran localmente, nunca dentro del código.

El [fixture RTSP incluido](../emulator/fixture/README.md) sirve un patrón H.264
y un tono PCMA completamente sintéticos. Su guía explica la dirección NAT
`10.0.2.2` del invitado, el puerto y las credenciales públicas de prueba. El
servidor sólo se inicia con el comando explícito de esa guía.

Para aceptar un cambio compruebe también el reloj OEM, los menús, las alarmas,
la respuesta de luz, reconstrucción de la Activity y retirada/reinstalación de
las páginas. Después repita las funciones relevantes en el hardware: el PC no
valida el decodificador, la estabilidad sostenida, los sensores ni la cadena
de arranque del Xiaomi.

El [adaptador de laboratorio del carrusel](../emulator/lab/README.md) permite
instalar temporalmente las páginas dentro del proceso OEM. Requiere un **JDK**
17/21 con `jdk.jdi`, no sólo un JRE. Por ejemplo:

```bash
python3 emulator/lab/integrate.py --session /var/tmp/x04g/session-01/session.json install
python3 emulator/lab/integrate.py --session /var/tmp/x04g/session-01/session.json --capture selectcamera
```

Para abrir el editor con entradas virtuales, ejecute consecutivamente:

```bash
python3 emulator/lab/integrate.py --session /var/tmp/x04g/session-01/session.json selectcamera
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json swipe 400 240 400 240 1500
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json tap 400 445
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json tap 118 120
```

Son la pulsación prolongada, **Administrar** y **Añadir** de la UI real a
800×480. También puede hacerlos con un cliente interactivo de pantalla ADB.
En páginas sin reproducción el retorno automático al reloj continúa siendo OEM.

Consulte su `--help` para las rutas del APK y Java y los requisitos de la
captura. La entrada técnica de laboratorio se distingue del alta normal de Home.

## Cerrar

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json stop
```

Se cierra sólo el emulador identificado. Después se espera a que desaparezca su
transporte y se cierra el servidor privado 5102 si no quedan otros transportes.
Si contiene otros, se conserva y se informa claramente. Una respuesta ambigua
de ADB nunca se interpreta como una lista vacía.

Si la salida del emulador tarda más de cinco segundos, puede completar después
el cierre del servidor usando la misma sesión:

```bash
python3 emulator/controls.py --session /var/tmp/x04g/session-01/session.json stop-server
```

`stop-server` sólo cierra 5102 cuando su lista de transportes está vacía; no
detiene un emulador ni otro destino conectado. Conserve los datos si quiere
inspeccionar esa sesión. Un nuevo `--state` crea una instancia separada; el
script no borra AVD existentes ni detiene otros emuladores.
