# Extraer las particiones OEM del respaldo

`emulator/extract_partitions.py` lee archivos locales y escribe archivos nuevos.
No accede a USB, ADB, dispositivos de bloque ni montajes. Usa sólo Python 3 y su
biblioteca estándar; funciona en Windows y en Linux/WSL.

La entrada debe ser un respaldo **USER completo y cerrado**, con su tamaño y
SHA256 ya registrados durante la validación del respaldo. Para el emulador se
necesitan únicamente `system`, `product` y `vendor`, contenidas en `super`.
No se extrae ni reutiliza `userdata`.

## 1. Localizar y extraer `super` desde USER

Desde la raíz del repositorio, sustituir la ruta y el hash por los propios:

```bash
python3 emulator/extract_partitions.py gpt-list \
  .local/backups/user_backup.bin --sha256 HASH_USER_VALIDADO
```

El listado contiene nombres, rangos y tamaños del GPT real. Debe mostrar la
partición `super`. El lector valida el CRC de la cabecera GPT primaria y su
tabla, los límites y la ausencia de solapamientos. No repara un GPT dañado ni
valida su cabecera secundaria; esa validación corresponde al respaldo completo.

Extraer exclusivamente la partición que aparece en ese listado:

```bash
python3 emulator/extract_partitions.py gpt-extract \
  .local/backups/user_backup.bin --sha256 HASH_USER_VALIDADO \
  --partition super --out-dir .local/extracted-gpt \
  --report .local/extracted-gpt/report.json
```

La salida `super.img` conserva exactamente los bytes del rango GPT; `report.json`
incluye su tamaño y SHA256. Si el nombre real es distinto, usar ese nombre y la
ruta de salida correspondiente. La carpeta de salida debe ser nueva. Un error
deja un resultado parcial que no debe usarse como imagen válida.

Si ya se dispone de un `super.img` extraído de un respaldo validado, se puede
continuar directamente con su SHA256 registrado, sin volver a copiar USER.

## 2. Extraer las particiones lógicas

Leer el hash de `super.img` en el informe anterior y listar su contenido:

```bash
python3 emulator/extract_partitions.py super-list \
  .local/extracted-gpt/super.img --sha256 HASH_SUPER_DEL_INFORME
```

El lector comprueba los SHA256 de geometría, cabecera y tablas liblp. Admite un
solo dispositivo físico, metadatos versión 10.0–10.2 y extents lineales o a cero.
Si usa la copia de respaldo por un fallo de la primaria, lo informa explícitamente
en `metadata_copy` y `fallback_errors`; nunca acepta ambas copias inválidas.

Para el firmware soportado, extraer los tres nombres del listado:

```bash
python3 emulator/extract_partitions.py super-extract \
  .local/extracted-gpt/super.img --sha256 HASH_SUPER_DEL_INFORME \
  --partition system --partition product --partition vendor \
  --out-dir .local/oem-partitions \
  --report .local/oem-partitions/report.json
```

Se generan `system.img`, `product.img` y `vendor.img`, cada uno con tamaño exacto
y SHA256 en el informe. Se copian todos los extents en orden, incluso longitudes
que no sean múltiplo de 4096. Los extents a cero se materializan correctamente.
El lector también admite Android sparse v1, incluidos patrones FILL distintos
de cero; no crea una conversión junto a la entrada original. Su CRC global sparse
no sustituye la comprobación SHA256 del archivo.

`--slot N` selecciona una ranura de metadatos liblp; por defecto usa 0. Si los
nombres contienen sufijos, seleccionar los nombres completos del listado.
La receta del emulador exige además los hashes del firmware soportado y rechazará
otros contenidos; una extracción correcta no implica compatibilidad de otra
versión del firmware.

## 3. Usarlas en el emulador

En Linux/WSL, pasar estas rutas a `mount_inputs.py`:

```bash
sudo python3 emulator/mount_inputs.py mount \
  --state /var/tmp/x04g/input-mounts \
  --oem-system /ruta/al/repo/.local/oem-partitions/system.img \
  --oem-product /ruta/al/repo/.local/oem-partitions/product.img \
  --oem-vendor /ruta/al/repo/.local/oem-partitions/vendor.img \
  --sdk-sysdir /var/tmp/x04g/dependencies/sdk/arm64-v8a
```

Continuar con [el ensamblado y arranque del emulador](emulator.md). Mantener las
imágenes y los informes en `.local`, fuera de Git. Los informes son locales y
pueden contener rutas de archivos del propietario; no forman parte de la receta
publicable.

## Pruebas del lector

```text
python -m unittest discover -s emulator -p test_extract_partitions.py
```

Las pruebas usan estructuras GPT/liblp/sparse sintéticas y verifican rechazo de
CRC/hash incorrectos, copia de respaldo explícita, múltiples extents, extents a
cero, patrones FILL y protección contra sobrescritura de una salida existente.
