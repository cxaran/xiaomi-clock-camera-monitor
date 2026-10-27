# Xiaomi Clock Camera Monitor

Monitor de cámaras RTSP para **Xiaomi Mi Smart Clock X04G** con Android Things 10,
pantalla de 800 × 480 y 1 GB de RAM. Añade una página de cámaras al carrusel del
launcher original y conserva el reloj, alarmas, menús, voz y regulación de brillo OEM.

Una cámara a la vez, sonido, selección mediante pulsación prolongada, zoom por
doble toque y editor con teclado en pantalla. La conexión de vídeo funciona por
la red local. Las cámaras necesitan una cuenta RTSP propia; la cuenta de la app
del fabricante puede ser distinta. El paquete también conserva el control LAN
opcional de portón, sin ningún dispositivo ni señal preconfigurados.

## Así se ve

| Reloj original · X04G físico | Página de cámaras · emulador OEM |
| --- | --- |
| ![Reloj original y los indicadores del carrusel](docs/screenshots/oem-clock.png) | ![Página de cámaras lista para añadir una cámara](docs/screenshots/camera-page.png) |
| **Selector por pulsación prolongada · emulador OEM** | **Editor y teclado táctil · emulador OEM** |
| ![Selector de cámaras con acceso a Administrar](docs/screenshots/camera-selector.png) | ![Editor de cámaras con teclado en pantalla](docs/screenshots/camera-keyboard.png) |

Capturas reales de 800 × 480. Las vistas de cámaras están vacías, sin vídeo
privado ni datos de acceso. [Más pantallas y origen de cada captura](docs/screenshots/README.md).

## Instalador para Windows y descargas

Descarga el [instalador portable de v0.1.0-rc.2](https://github.com/cxaran/xiaomi-clock-camera-monitor/releases/download/v0.1.0-rc.2/x04g-windows-installer-0.1.0-rc.2.zip).
Incluye ADB para Windows, el APK y su módulo. Extrae todo el ZIP y, con el X04G
conectado por USB y arrancado normalmente, abre **Check-X04G.cmd** y después
**Install-X04G.cmd**. Comprueba la compatibilidad, obtiene el UID del reloj,
instala la pareja y verifica el reinicio y la integración. **Disable-X04G.cmd**
permite desactivar la extensión. [Instrucciones del instalador](installer/README.md).

Necesita un X04G con ADB USB autenticado, root y Magisk/Zygisk ya preparados;
para una unidad de fábrica completa antes [la preparación y el respaldo](docs/adb.md).
El instalador no automatiza el desbloqueo ni el borrado de datos. La compatibilidad
se limita al launcher exacto documentado y la versión sigue siendo preliminar.

La [release](https://github.com/cxaran/xiaomi-clock-camera-monitor/releases/tag/v0.1.0-rc.2)
también ofrece los binarios individuales, hashes y
[la instalación manual](docs/release-install.md). RC2 corrige la comprobación de
versión del APK que impedía activar el puente de RC1. No incluye BOOT, fuentes
OEM, cuentas ni configuración de cámaras o portón.

## Compilar e instalar desde las fuentes

1. Comprueba el modelo, versión y compatibilidad en [ADB y arranque](docs/adb.md).
2. Obtén un respaldo de tu propia unidad y prepara el arranque con tu clave pública
   ADB. El desbloqueo requiere restablecimiento de fábrica: se borran cuentas y
   configuración. Conserva los originales para recuperación.
3. En Windows x64, instala Python 3.11 o posterior y Git. Desde la raíz del repositorio:

   ```powershell
   python scripts/fetch_tools.py
   python scripts/build_app.py --public-release
   ```

   El APK firmado queda en `build/app/x04g-home.apk`. Las herramientas se descargan
   con versiones y SHA-256 fijados. La clave de firma nueva se guarda sólo en
   `.local/signing/`; consérvala para poder actualizar tu instalación.
4. La app lee la tipografía e iconos del launcher instalado en el propio reloj.
   Si esos recursos no están disponibles, usa la tipografía del sistema e iconos
   vectoriales propios. Consulta [integración y construcción del módulo](docs/carousel.md).
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
| `installer/` | Instalador Windows, comprobación, UID automático y pruebas aisladas |
| `docs/` | Instrucciones de instalación, uso y desarrollo |

No se incluyen contraseñas, claves ADB, cuentas, SSID, códigos RF ni imágenes de
cámaras particulares. No hay claves compartidas entre instalaciones. Los datos
locales de trabajo están excluidos por `.gitignore`.

