# Cámara RTSP sintética

Este servidor permite probar la app sin una cámara real. Sirve un patrón
`testsrc2` inventado de 640×360 a 15 fps y un tono de 440 Hz a volumen bajo.
No contiene imágenes del hogar, grabaciones de micrófono ni credenciales reales.
Los cuatro segundos de material se repiten manteniendo secuencias y timestamps
RTP crecientes.

Sólo necesita Python 3.12 o posterior. El material ya está incluido y verificado
por SHA256 en `media/manifest.json`; FFmpeg únicamente se necesita si se quiere
generar de nuevo el patrón. Este directorio no entra en el APK.

## Probar desde el emulador

Ejecutar en el **mismo host Linux/WSL donde corre el motor del emulador**, desde
la raíz del repositorio:

```bash
python3 emulator/fixture/fixture_server.py \
  --bind 127.0.0.1 --port 8554 --seconds 180 --auth digest
```

En la configuración de cámaras de la app, añadir una cámara de prueba:

| Campo | Valor público del fixture |
| --- | --- |
| Nombre | Patrón de prueba |
| IP/host | `10.0.2.2` |
| Puerto RTSP | `8554` |
| Usuario | `fixture` |
| Contraseña | `fixture-public` |
| Ruta | `/stream2` |

URL completa de prueba:

```text
rtsp://fixture:fixture-public@10.0.2.2:8554/stream2
```

`10.0.2.2` es el alias estándar del host para el NAT del emulador; no identifica
una cámara ni una dirección de la red doméstica. No sirve para acceder desde
el reloj físico. Si el motor y el servidor están en hosts distintos, hay que
elegir una dirección del servidor alcanzable desde esa red y un bind explícito.
El servidor admite loopback o una IPv4 privada concreta; no escucha en
`0.0.0.0`, ni crea rutas o reglas de firewall.

Primero comprobar que aparece el patrón. Después probar selector, zoom,
salida de la página y liberación del reproductor. El perfil del emulador arranca
con audio del host deshabilitado: esta fuente ofrece PCMA, pero escuchar el tono
requiere habilitar y validar por separado una salida de audio adecuada. La
presencia de paquetes no acredita imagen presentada ni sonido audible.

Una sola conexión puede estar activa. Detener otros reproductores antes de
abrir esta fuente en la app. El servidor termina a los 180 segundos o con
`Ctrl+C`; se puede elegir un plazo de hasta 1800 segundos. Cada nueva ejecución
es manual.

## Formato y autenticación

La fuente usa RTSP 1.0 con RTP interleaved sobre TCP, H.264 Baseline/yuv420p y
audio PCMA/G.711 A-law mono de 8 kHz. Incluye SPS/PPS, AUD e IDR cada segundo.
Admite OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, PAUSE y TEARDOWN.
`--auth basic` y `--auth none` permiten probar esas variantes con el mismo
material y las mismas credenciales públicas. No introducir cuentas reales en
este fixture.

No emite RTCP Sender Reports: sirve para comprobar transporte, decodificación y
ciclos de vida, pero no acredita sincronía fina entre audio y vídeo.

## Probar desconexión y fallos acotados

`--state /ruta/estado.json` permite simular una fuente apagada. El archivo debe
contener exactamente `{"enabled":true}` o `{"enabled":false}`, con máximo 256
bytes. Al pasar a `false` se cierra la conexión; después de volver a `true`,
reintentar manualmente desde la app. El archivo se actualiza mediante reemplazo
atómico para evitar lecturas parciales.

También están disponibles dos fallos deliberados, que comienzan después de
unos dos segundos de vídeo válido:

- `--fault-mode video-stall`: conserva audio y RTSP, pero detiene el vídeo.
- `--fault-mode interleaved-drip`: deja un mensaje de vídeo incompleto y entrega
  bytes lentamente para probar el límite total del parser.

El valor predeterminado es `--fault-mode normal`. La cola de salida está limitada
a 512 KiB; un cliente lento provoca un cierre, sin crecimiento indefinido.
`--stats ARCHIVO_NUEVO.json` registra contadores públicos y cierre de sesiones,
sin URL, cabeceras, direcciones de clientes ni claves.

## Pruebas del servidor

```bash
python3 -m unittest discover -s emulator/fixture -p test_fixture.py
```

Las pruebas usan sockets y reloj simulados en memoria: no abren un listener ni
usan Android. Cubren hashes del material, SDP, Digest/Basic, FU-A, marker,
secuencias/timestamps con wrap, PLAY/PAUSE/TEARDOWN, escrituras parciales y los
fallos deliberados.

## Generar otro patrón con FFmpeg

Instalar FFmpeg con `libx264` en el host y usar una carpeta nueva:

```bash
python3 emulator/fixture/generate_fixture.py --output .local/fixture-media
python3 emulator/fixture/fixture_server.py \
  --media .local/fixture-media --bind 127.0.0.1 --port 8554 --seconds 180
```

El generador sólo utiliza las fuentes `lavfi` `testsrc2` y `sine`; no acepta
cámaras, micrófonos ni medios externos. El nuevo manifiesto registra comandos
genéricos, perfil y hashes sin incluir rutas locales. Otra versión de FFmpeg
puede producir bytes diferentes conservando el perfil.
