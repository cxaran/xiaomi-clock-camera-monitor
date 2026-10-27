# Integración temporal para diseño de UI en el PC

Este adaptador sólo acepta la instancia exportada `emulator-5602`, servidor ADB
`5102`, `ro.kernel.qemu=1` y el marcador `portable-oem-camera-lab`. No admite un
serial USB ni una dirección ADB de red. No utiliza Magisk ni escribe BOOT.

En una instancia nueva se puede abrir la Activity original por su componente
explícito, en lugar de esperar al shim HOME, Cast y al aprovisionamiento de Google
Home. Esa entrada es una **adaptación de laboratorio**, no demuestra configuración
oficial, conectividad de Assistant/Cast ni equivalencia completa con el reloj.
No se copian cuentas, Wi-Fi ni userdata desde una unidad física.

Requiere Linux/WSL con un **JDK17/21**, que incluya `javac` y el módulo `jdk.jdi`.
El JRE usado para construir el APK no incluye esas herramientas. Usa el
`session.json` que produjo `emulator/run_oem.py` y el APK recién compilado desde
las fuentes de este repositorio:

```bash
python3 emulator/lab/integrate.py --session /ruta/session.json install
python3 emulator/lab/integrate.py --session /ruta/session.json --capture selectcamera
python3 emulator/lab/integrate.py --session /ruta/session.json --capture selectclock
```

El modo `install` instala exclusivamente `org.x04g.home`, activa la depuración
JDWP de `com.google.android.apps.mediashell` en ese AVD, reinicia su Activity e
inicia la original. Comprueba el APK OEM intacto por SHA-256 y la copia instalada
del APK compañero contra el archivo local. No requiere ni realiza un inicio de
sesión de Google.

Se crea un forward JDWP efímero. Un breakpoint de un solo uso en el hilo MAIN
espera un toque en la Activity original; el wrapper envía un toque de diagnóstico
en `(12,460)`. JDI carga el código del APK compañero por `DexClassLoader` y llama
la misma integración `OemCarousel` usada por el módulo persistente. El breakpoint,
las referencias retenidas y el forward se liberan al cerrar. No se reempaquetan
los APK OEM ni se cambian campos de su pager para simular imágenes.

Si el APK ya está instalado y el mismo proceso continúa con JDWP habilitado,
el modo `attach` permite cargar las páginas sin reinstalar ni reiniciar la
Activity. No repitas `install` sólo porque una observación haya vencido: revisa
primero el proceso y sus recibos.

Los modos `selectclock`, `selectcamera` y `selectgate` llaman al método original
del pager para elegir una página. Son selección programática; no acreditan que
un gesto físico haya navegado. Comprueba después los gestos con
`emulator/controls.py` o con el visor.

Los recibos y PNG se guardan bajo `build/emulator-lab/`, excluido de Git. Un
recibo `ok` acredita que terminó la llamada JDI y se cerró el forward; revisa el
estado `snapshot_json` y la captura para comprobar que la vista está instalada
y visible. No atribuyas una imagen de muestra a una cámara real.

El emulador nuevo no tiene cámaras ni perfil del portón. En la página vacía,
mantén pulsado para abrir el selector y **Administrar**. Puedes ensayar la
configuración con una cámara de laboratorio local; configura sus credenciales
en la interfaz, nunca en las fuentes ni en una captura que vayas a publicar.

Para retirar las páginas de esa sesión:

```bash
python3 emulator/lab/integrate.py --session /ruta/session.json remove
```

Para quitar JDWP, usa el ADB Linux de esa instancia, su clave local y siempre
`-H 127.0.0.1 -P 5102 -s emulator-5602`, después de comprobar el marcador:

```text
adb -H 127.0.0.1 -P 5102 -s emulator-5602 shell am clear-debug-app
```

El reinicio del proceso elimina el código cargado temporalmente. Esta vía sirve
para diseñar y revisar la UI; la instalación persistente en hardware utiliza
[la pareja APK/módulo y su UID comprobado](../../docs/carousel.md).
