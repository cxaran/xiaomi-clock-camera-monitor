# Aplicación de cámaras

## Uso en el reloj

La página Cámaras forma parte del carrusel original. Mantén pulsado el centro durante 700 ms para abrir el selector. Desde allí puedes elegir otra cámara o entrar en **Administrar**. La reproducción comienza sólo para la cámara seleccionada y visible.

- Doble toque sobre una zona de la imagen: amplía a 2× alrededor del toque.
- Arrastre con zoom: desplaza el encuadre. Otro doble toque vuelve al encuadre completo.
- Icono de altavoz: activa o silencia el audio. Comienza silenciado.
- Deslizamiento horizontal sin zoom: navega con el pager original. Con zoom, usa los bordes para cambiar de página.
- Volver al reloj, abrir otra superficie OEM o pausar la Activity detiene la conexión, el decoder y la salida de cámara.

La imagen normal no añade nombre ni hora sobre el vídeo. El selector y el editor sí muestran la hora. El brillo automático y la hora principal siguen siendo responsabilidad del sistema original.

## Añadir o corregir una cámara

En **Administrar → Añadir**, introduce nombre, dirección local, puerto RTSP, ruta, usuario local de cámara y contraseña. Para una Tapo que ofrezca RTSP, un perfil habitual es puerto `554`, ruta `/stream2` y una cuenta local configurada desde la app de la cámara. La cuenta de nube del fabricante no sustituye esa cuenta local. Comprueba la disponibilidad de RTSP en el modelo concreto.

Pulsa **Teclado** para introducir datos con el teclado propio del reloj; no requiere instalar un IME. Usa **Probar** para revisar imagen/audio y **Guardar** para confirmar. Guardar una entrada pendiente permite corregirla después; no la habilita en el selector hasta verificarla. La lista permite editar, quitar, ocultar y reordenar. Admite hasta 12 entradas, una cámara en reproducción a la vez.

Si cambia una IP, edita la dirección o usa **Buscar en LAN**. El descubrimiento WS-Discovery propone candidatos ONVIF; no cambia direcciones automáticamente ni acredita identidad. Prueba la cámara antes de guardar. Una reserva DHCP en el router evita cambios frecuentes. Una contraseña vacía al editar conserva la anterior.

**Desde el teléfono** abre por acción explícita un servidor HTTP local temporal. Escanea la dirección que muestra el reloj e introduce el código de emparejamiento mostrado en su pantalla. El servidor se cierra al salir, al pausar, a los cinco minutos o tras 90 segundos de inactividad. Sólo atiende la interfaz IPv4 privada seleccionada y un transporte a la vez. La reproducción se detiene antes de abrirlo. Esta modalidad usa HTTP, por lo que requiere una LAN de confianza; para introducir secretos sin transporte HTTP, usa el editor nativo. No se publica ningún puerto en Internet. Las peticiones de prueba del teléfono necesitan confirmación en el reloj.

## Compilar y previsualizar

Desde la raíz del repositorio:

```powershell
python scripts/build_app.py
adb -P 5142 -s SERIAL_DEL_RELOJ install -r build/app/x04g-home.apk
adb -P 5142 -s SERIAL_DEL_RELOJ shell am start -n org.x04g.home/.PreviewActivity --es page cameras
```

Sustituye `SERIAL_DEL_RELOJ` por el transporte que muestra `adb devices -l`. La Activity de diagnóstico exige el permiso `DUMP`, disponible para la invocación ADB. No registra una app HOME ni un icono LAUNCHER: instalar sólo el APK no añade páginas al carrusel. Sigue [la integración](carousel.md) para incorporarlas al launcher original y [la guía ADB](adb.md) si aún no hay acceso.

La Activity independiente y el launcher ejecutan la configuración bajo UID distintos, con claves AndroidKeyStore independientes. Añadir una cámara en PreviewActivity no la añade al carrusel OEM. Configura de nuevo en la página integrada; no copies archivos cifrados entre UID.

La compilación genera una clave nueva en `.local/signing`. Conserva esa clave local para actualizar tus propias instalaciones. No distribuyas el almacén de claves ni credenciales de cámaras. El APK de un usuario no sustituye al de otro con firma diferente mediante `install -r`.

## Arquitectura y límites

| Código | Función |
| --- | --- |
| `AppSession` | Visibilidad, ciclo de vida, foco de audio y propietario de una sesión |
| `ui/CameraPage` | TextureView, selector, zoom, controles y arbitraje de gestos |
| `camera/` | RTSP/TCP interleaved, Digest/Basic, RTP, H.264, G.711 y MediaCodec/AudioTrack |
| `config/` | Validación, CRUD con revisión, archivos atómicos y AES-GCM/AndroidKeyStore |
| `ui/admin/` | Editor nativo con teclado y prueba de cámara |
| `adminweb/`, `AdminWebSession` | Administración local temporal y emparejamiento |
| `integration/` dentro de `app/src` | Adaptación de las vistas al carrusel OEM |

No necesita WebView, FFmpeg, Node ni servicios de nube en el reloj. Hay una conexión RTSP, un decoder H.264 y como máximo una salida AudioTrack. Admite H.264 hasta 1280×720 visibles (altura codificada máxima 736), RTP H.264 packetization-mode 0/1 y audio PCMA mono de 8 kHz. No incluye H.265, AAC, audio bidireccional ni grabación. La sesión termina ante un cambio de resolución: abre otra vez la cámara para renegociar.

Se limita el tamaño de cabeceras, paquetes y unidades H.264; no se acumula una cola Java de imágenes. Las alarmas y el Asistente conservan prioridad de foco. Los timeouts detienen la conexión sin reconexión automática infinita. Ante falta de memoria se pausa el vídeo.

Las credenciales completas se cifran con AES-GCM en el directorio privado `noBackupFilesDir/x04g-home`, bajo el UID que ejecuta las vistas. La clave AndroidKeyStore no se exporta. Reinstalar, borrar datos o cambiar de UID puede exigir configurar otra vez. No se forman URLs con contraseñas ni se registran credenciales en logs.

Se conserva el módulo opcional Portón de la app actual, sin perfil, clave LAN ni código RF. No es necesario para ver cámaras. Sin configuración no puede emitir una orden. Este repositorio no incluye una configuración particular de domótica.

## Alcance de las comprobaciones

El reproductor, editor nativo, zoom, cambios entre cámaras y retorno al reloj se comprobaron en un X04G con launcher coincidente. El panel web HTTP incluido requiere validación física propia; la compilación y las pruebas HOST no sustituyen esa comprobación. Una prueba de protocolo o compilación no acredita continuidad de vídeo durante días, sonido audible ni sincronía A/V. Usa [la lista de verificación](verification.md) después de instalar en tu unidad.
