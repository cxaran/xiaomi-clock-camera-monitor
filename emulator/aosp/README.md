# Hardware virtual AOSP

La receta incluye las implementaciones virtuales necesarias para ejecutar el
userspace ARM32 del X04G con un kernel Goldfish ARM64. El firmware, las APK y
las bibliotecas OEM utilizadas para enlazar se obtienen del respaldo propio.

`sources.tar.gz` contiene 2.984 archivos de fuentes, headers y recetas CMake.
[`sources.lock.json`](sources.lock.json) fija cada archivo y el archivo
comprimido por SHA-256. Sus principales upstreams son:

| Familia | Fuente |
| --- | --- |
| GLES, EGL, codecs gráficos y gralloc | `device/generic/goldfish-opengl`, commit `dcccfbae1537fb21a281861672f510184a6f9e14` |
| Servicio Wi-Fi 1.3 | `platform/hardware/interfaces`, commit `2083ef1548be73bb8f002a98de8e1105fae85831` |
| Transporte Bluetooth y headers asociados | `platform/system/bt`, commit `3fd0365a822ca165ad6cd1cba57966c94c7c8a91` |
| Headers de plataforma y HIDL | Fuentes Android 10 y headers generados de interfaces congeladas; URL y hashes en [header-upstreams.json](header-upstreams.json) |

Las adaptaciones de gráficos corrigen la actualización en `gralloc_unlock` y la
transferencia YV12→RGBA; EGL conserva el lookup de bibliotecas del invitado.
El mapper2 mantiene su contrato SPHAL sin añadir un enlace directo innecesario
al allocator. El HAL Wi-Fi traduce el modo STA virtual a la enumeración que
espera el framework original. El transporte Bluetooth permite un canal virtio
al host; no implementa un sensor o una radio física del Xiaomi.

Los propietarios y las licencias originales permanecen en las fuentes. El
texto de Apache 2.0 está en [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt);
los headers con MIT, BSD, ICU o NSPR conservan sus avisos correspondientes.
El kernel tiene su licencia GPL-2.0 y sus fuentes/receta en
[`../recipe/kernel/`](../recipe/kernel/). Las licencias del motor y del SDK se
entregan en sus archivos oficiales `NOTICE.txt`, descargados junto con ellos.

## Reconstruir opcionalmente

El arranque normal usa los payloads fijados y no requiere recompilar estos HAL.
Para modificar el hardware virtual se necesita Linux/WSL, Python ≥3.12, CMake ≥3.18, Ninja y
NDK **r20b / 20.1.5948944**. La URL y el SHA-256 del NDK están en el lock.

Prepare las fuentes OEM y SDK como filesystem roots accesibles en la misma
sesión Linux. El JSON de entrada tiene el formato de
[`../inputs.example.json`](../inputs.example.json). Los 111 proveedores de enlace
se comprueban con los hashes de [`providers.json`](providers.json); el script
sólo copia las bibliotecas necesarias a su directorio local de compilación.

```bash
python3 emulator/aosp/rebuild.py \
  --inputs /ruta/inputs.json \
  --ndk /ruta/android-ndk-r20b \
  --cmake /ruta/cmake \
  --ninja /ruta/ninja \
  --output /var/tmp/x04g/native-build-01
```

El comando verifica y extrae el archivo de fuentes en un destino nuevo, configura
los componentes ARM32/ARM64 y guarda logs de cada configuración/compilación.
No instala sus resultados ni cambia los payloads de la receta. Las rutas de
compilación se mapean a `/build/x04g` en los datos de depuración. Los literales
de diagnóstico `__FILE__` se redactan después con sustituciones de igual longitud
en los ELF propios: Clang8 del NDK r20b no soporta `-ffile-prefix-map`.
`native-outputs.json` registra los hashes antes y después. No se modifican las
bibliotecas OEM/SDK utilizadas para enlazar. Use una ruta de salida Linux
canónica y sin espacios.

La [verificación del paquete exportado](rebuild-verification.json) registra
compilación real de los cinco componentes: diez etapas de CMake/Ninja y doce
ELF producidos, con hashes y comprobación de rutas de usuario ausentes. Es
compilación cruzada del HOST; no acredita ejecución de esos nuevos binarios
en Android ni sustituye las pruebas del emulador.

[`payload-provenance.json`](payload-provenance.json) relaciona los archivos
distribuidos con sus fuentes, deltas y hashes. Doce ELF AOSP tienen redactadas
las rutas de diagnóstico mediante sustituciones de igual longitud, sin cambiar
el tamaño o los offsets del archivo. La recompilación puede producir hashes
distintos por las rutas, los build IDs y la versión de herramientas; esos
resultados requieren la comprobación ABI, el arranque y la prueba visual antes
de sustituir un payload fijado.
