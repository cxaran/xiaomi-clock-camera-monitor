# Dependencias y recursos

- La biblioteca Java QR de Project Nayuki está incluida con su
  [licencia MIT](app/src/org/x04g/home/qr/LICENSE-MIT.txt).
- La API Zygisk y el instalador de módulos proceden de
  [Magisk](https://github.com/topjohnwu/Magisk). La cabecera de API conserva su
  licencia permisiva ISC; el instalador conserva su licencia GPL-3.0 y avisos en
  `integration/official/`.
- Android SDK, sus herramientas, imágenes de sistema y NDK se obtienen de Google
  bajo sus respectivas condiciones. Los archivos descargados quedan fuera del
  repositorio. [Herramientas SDK](https://developer.android.com/tools).
- Eclipse ECJ se descarga de Maven Central y pertenece al proyecto Eclipse JDT.
  [Código y licencias de JDT](https://github.com/eclipse-jdt/eclipse.jdt.core).
- Temurin se descarga de Adoptium. [OpenJDK/Temurin](https://adoptium.net/).
- MTKClient se obtiene del commit indicado en la guía de ADB, con su licencia
  GPL-3.0. [Repositorio upstream](https://github.com/bkerler/mtkclient).
- Los APK, tipografías e iconos OEM se extraen del respaldo del usuario; no se
  redistribuyen. Xiaomi y Google conservan los derechos sobre sus componentes.
- La guía de acceso se apoya en el estudio público
  [Informatic/xiaomi-x04g-research](https://github.com/Informatic/xiaomi-x04g-research).
  El helper de este repositorio mantiene ADB autenticado por RSA y SELinux enforcing.

Las dependencias adicionales del entorno OEM se documentan junto a sus fuentes
y recetas en `emulator/`.
