# Xiaomi Clock Camera Monitor

Monitor de cámaras RTSP para **Xiaomi Mi Smart Clock X04G** con Android Things 10,
pantalla de 800 × 480 y 1 GB de RAM. Añade una página de cámaras al carrusel del
launcher original y conserva el reloj, alarmas, menús, voz y regulación de brillo OEM.

Una cámara a la vez, sonido, selección mediante pulsación prolongada, zoom por
doble toque y editor con teclado en pantalla. La conexión de vídeo funciona por
la red local. Las cámaras necesitan una cuenta RTSP propia; la cuenta de la app
del fabricante puede ser distinta. El paquete también conserva el control LAN
opcional de portón, sin ningún dispositivo ni señal preconfigurados.

## Instalación en el reloj

1. Comprueba el modelo, versión y compatibilidad en [ADB y arranque](docs/adb.md).
2. Obtén un respaldo de tu propia unidad y prepara el arranque con tu clave pública
   ADB. El desbloqueo requiere restablecimiento de fábrica: se borran cuentas y
   configuración. Conserva los originales para recuperación.
3. En Windows x64, instala Python 3.11 o posterior y Git. Desde la raíz del repositorio:

   ```powershell
   python scripts/fetch_tools.py
   python scripts/build_app.py
   ```

   El APK firmado queda en `build/app/x04g-home.apk`. Las herramientas se descargan
   con versiones y SHA-256 fijados. La clave de firma nueva se guarda sólo en
   `.local/signing/`; consérvala para poder actualizar tu instalación.
4. Extrae las fuentes del launcher de tu respaldo para usar su tipografía e iconos.
   Vuelve a compilar el APK después de extraerlas. Consulta
   [integración y construcción del módulo](docs/carousel.md).
5. Construye el puente, instala la pareja APK/módulo y fija el UID real del proceso
   OEM siguiendo esa guía. El módulo se genera deshabilitado y vinculado al hash
   y firma del APK construido. **Instalar sólo el APK no inserta páginas en el carrusel.**
6. Abre Cámaras, mantén pulsado y entra en Administrar. Añade una cámara, prueba la
   conexión y guarda. Consulta [uso y configuración](docs/app.md).

## Emulación y desarrollo

[La guía de emulación](docs/emulator.md) explica la composición del sistema OEM
sobre hardware virtual, su arranque, los sensores virtuales y las pruebas de UI.
Necesita imágenes extraídas del respaldo propio y las dependencias indicadas;
el repositorio no distribuye firmware ni datos de un reloj.

Incluye un [servidor RTSP de prueba](emulator/fixture/README.md) con patrón de
vídeo y tono sintéticos para ensayar cámaras sin compartir una fuente privada.

El entorno permite trabajar con el launcher y sus menús reales. No certifica por
sí solo los HAL físicos, la decodificación, la sincronía de audio ni el rendimiento
del reloj. Consulta los límites y las comprobaciones de la guía antes de usar una
prueba virtual como aceptación del dispositivo.

## Verificación

```powershell
python scripts/audit_export.py
```

Pruebas Java completas en WSL/Linux con OpenJDK 17/21:

```bash
python3 app/tests/run_host.py
```

Estas pruebas comprueban código y artefactos sin instalar ni accionar dispositivos.
La guía de cada componente identifica además las pruebas que requieren Android o
hardware. [Capturas de la integración](docs/screenshots/README.md).

## Estructura

| Ruta | Contenido |
| --- | --- |
| `app/` | Aplicación nativa, reproductor RTSP, configuración cifrada y pruebas |
| `integration/` | Puente Zygisk/DEX para el carrusel original y construcción del módulo |
| `tools/boot/` | Preparación del arranque y helper USB ADB |
| `emulator/` | Composición, arranque y controles del sistema virtual |
| `scripts/` | Herramientas fijadas, compilación y auditoría del paquete |
| `docs/` | Instrucciones de instalación, uso y desarrollo |

No se incluyen contraseñas, claves ADB, cuentas, SSID, códigos RF ni imágenes de
cámaras particulares. No hay claves compartidas entre instalaciones. Los datos
locales de trabajo están excluidos por `.gitignore`.

