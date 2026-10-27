# Kernel del emulador

`Image.gz` es Linux **4.14.175 Goldfish AOSP para ARM64**, con compatibilidad
ARM32. No es el kernel del Xiaomi y nunca se escribe en el reloj. Su función es
proporcionar al sistema virtual los dispositivos Goldfish/Ranchu del emulador:
entrada, pantalla, audio, almacenamiento y transporte de servicios virtuales.

La modificación añade `goldfish_events` y su opción de configuración. La entrada
multitáctil importa y valida hasta diez slots del dispositivo virtual. Esto
permite transportar los gestos; el launcher y la aplicación deciden qué hacer
con ellos. Los sensores virtuales se controlan mediante la interfaz del emulador,
no simulan por sí solos el hardware o la calibración del reloj físico.

## Archivos

| Archivo | Uso |
| --- | --- |
| `Image.gz` | Kernel listo para el ensamblador de imágenes del emulador |
| `config` | Configuración completa que pasa `olddefconfig` sin cambios |
| `complete.patch` | Todas las diferencias respecto del árbol fuente AOSP fijado |
| `sources.lock.json` | URLs, commits, hashes de archivos y árboles Git, variables de compilación |
| `COPYING` | Licencia GNU GPL versión 2 del kernel y su excepción de llamadas al sistema |
| `build_kernel.py` | Descarga/verificación, preparación y compilación en una carpeta nueva |

El SHA256 del binario entregado figura en `sources.lock.json`. No contiene
imágenes OEM, cuentas, claves ADB, configuración de cámaras ni datos del usuario.

## Recompilar en Linux/WSL2 x86_64

Usar un directorio de trabajo en el filesystem de Linux para evitar el coste de
compilar miles de archivos sobre `/mnt/c`. Reservar al menos 8 GiB libres.
Los tres archivos públicos descargados suman unos 501 MB; fuente y toolchains
extraídos ocupan aproximadamente 2 GB, además de los objetos de compilación.

Instalar las herramientas del host mediante el gestor de paquetes de la
distribución. En Ubuntu:

```bash
sudo apt-get install python3 git make gcc libc6-dev bc bison flex libssl-dev zlib1g-dev
```

El compilador que produce el kernel ARM64 y sus binutils están fijados por
checksum en el lock: Android Clang `r365631c` (9.0.8) y binutils del prebuilt
`aarch64-linux-android-4.9`. `HOSTCC` es el GCC nativo de Linux, usado únicamente
para las herramientas auxiliares del kernel; no sustituye ese Clang por el
Clang del equipo. `clang.real` evita depender del wrapper Python antiguo del
prebuilt. Se requieren Python 3.12 o superior y GNU Make 4.3 o superior.

Desde cualquier ubicación, con la ruta real al repositorio:

```bash
python3 /ruta/al/repo/emulator/recipe/kernel/build_kernel.py \
  --work-dir "$HOME/x04g-kernel-build" --jobs 2
```

El directorio debe ser nuevo. El script verifica los tres archivos descargados,
reconstruye la identidad Git del árbol AOSP, aplica el parche completo y verifica
la identidad del árbol resultante. Después ejecuta `olddefconfig`, exige que la
configuración fijada permanezca idéntica y compila `Image.gz` y módulos.
El binario queda en `out/arch/arm64/boot/Image.gz` y el resultado en `result.json`.
El script no instala módulos, no inicia Android y no accede al Xiaomi.

Para preparar las fuentes completas sin compilar:

```bash
python3 /ruta/al/repo/emulator/recipe/kernel/build_kernel.py \
  --work-dir "$HOME/x04g-kernel-source" --prepare-only
```

`--offline --cache /ruta/a/archivos` permite usar los tres tar.gz ya verificados,
con los nombres `kernel.tar.gz`, `clang_r365631c.tar.gz` y
`binutils_arm64.tar.gz`. `--hostcc /ruta/a/gcc` y `--make /ruta/a/make` permiten
elegir herramientas nativas explícitas. El host de referencia usa GCC15 y
GNU Make4.4.1. Otro GCC nativo puede cambiar los auxiliares o necesitar sus
propias dependencias; el compilador ARM64 permanece fijado.

El usuario y host de compilación están fijados a `builder` y `localhost`, con
fecha fija. El SHA256 de una recompilación debe comprobarse y registrarse antes
de reemplazar el kernel de la receta. El lock identifica el binario distribuido;
no convierte una compilación realizada con otras herramientas del host en una
reproducción binaria idéntica.

## Fuentes y licencia

Las fuentes correspondientes están definidas por el archivo AOSP `kernel`
fijado en `sources.lock.json`, más `complete.patch` y `config`, incluidos aquí.
La raíz fuente obtenida con `--prepare-only` contiene el código completo del
kernel, incluidos sus scripts de construcción, con el parche ya aplicado.
Al redistribuir el binario, acompañarlo de las fuentes completas correspondientes
con sus avisos y `COPYING`; los prebuilts de compilación tienen sus propios
avisos de licencia en los archivos oficiales.
