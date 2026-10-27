# Comprobaciones después de instalar

Usa el servidor ADB y la clave privada de tu instalación. Selecciona siempre el
serial del reloj; los ejemplos usan `adb -P 5142 -s SERIAL_DEL_RELOJ`.

## Compilación sin dispositivo

En Windows:

```powershell
python scripts/fetch_tools.py
python scripts/build_app.py
python -m unittest discover -s tools/boot -p 'test_*.py'
python -m unittest discover -s emulator -p 'test_extract_partitions.py'
python -m unittest discover -s emulator -p 'test_controls.py'
python -m unittest discover -s emulator/fixture -p 'test_fixture.py'
python scripts/audit_export.py
```

En WSL/Linux con OpenJDK 17/21, desde la raíz del mismo repositorio:

```bash
python3 app/tests/run_host.py
```

El informe de compilación está en `build/app/build-report.json`; las pruebas
Java, en `build/tests/host-report.json`. Comprueban protocolos, límites,
configuración, criptografía y políticas con datos sintéticos. No usan cámaras
reales ni verifican MediaCodec, brillo, sonido audible o ciclo de vida Android.

## Identidad y arranque

```powershell
adb -P 5142 -s SERIAL_DEL_RELOJ shell getprop ro.product.device
adb -P 5142 -s SERIAL_DEL_RELOJ shell getprop ro.build.version.sdk
adb -P 5142 -s SERIAL_DEL_RELOJ shell getenforce
adb -P 5142 -s SERIAL_DEL_RELOJ shell id
adb -P 5142 -s SERIAL_DEL_RELOJ shell sha256sum /product/app/com.google.assistant.launcher/app.apk
```

Espera `mico_x04g`, API29, `Enforcing`, shell UID2000 y el hash OEM indicado en
[carrusel](carousel.md). La elevación `su` se comprueba por separado. Reinicia
una vez y verifica de nuevo ADB, reloj y páginas: una instalación en memoria
durante una sesión no demuestra persistencia.

## Interfaz y cámaras

| Comprobación | Resultado esperado |
| --- | --- |
| Reloj, alarmas, menú y ajuste de estilo | Siguen funcionando desde sus pantallas originales |
| Carrusel | Una página de cámaras; páginas OEM mantienen su orden relativo |
| Cámara configurada | Imagen en movimiento, no sólo conexión RTSP aceptada |
| Cambio de cámara | Una cámara activa; la anterior deja de reproducirse |
| Sonido | Se escucha la fuente elegida; silencio y foco de alarma funcionan |
| Pulsación prolongada | Abre selector y acceso a Administrar |
| Doble toque | Amplía la zona elegida y el siguiente restaura la imagen completa |
| Editor | Teclado visible, alta/edición/baja y prueba antes de guardar |
| Contraseña en blanco al editar | Conserva la existente |
| Salida al reloj | Libera RTSP y salida de audio de cámara |
| Cambio de luz ambiente | Se conserva la regulación OEM |
| WAN desconectada, LAN activa | Continúa vídeo local; la meteorología puede quedar sin actualización |

Para observar recursos durante y después del vídeo:

```powershell
adb -P 5142 -s SERIAL_DEL_RELOJ shell dumpsys meminfo com.google.android.apps.mediashell
adb -P 5142 -s SERIAL_DEL_RELOJ shell dumpsys media.codec
adb -P 5142 -s SERIAL_DEL_RELOJ shell dumpsys media.audio_flinger
```

Compara varias muestras y una sesión larga con tu cámara. PSS incluye el
launcher y los componentes OEM; no atribuyas toda esa memoria a la extensión.
Una prueba virtual o el consumo de AudioTrack no prueban calidad audible ni
sincronía audiovisual. No publiques logs completos o capturas con direcciones,
nombres de cámaras privadas o imágenes personales.

Para desactivar la extensión y volver al carrusel sin páginas nuevas sigue
[retirada del módulo](carousel.md#diagnóstico-y-retirada).

