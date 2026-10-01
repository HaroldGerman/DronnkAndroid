# Dronnk Assistant Android

Dronnk 2.0 es la reestructuración de la antigua app Dronnk como asistente personal Android. Conserva el package `com.german.dronnk` para mantener la continuidad de actualización, pero elimina por completo el reproductor, biblioteca, playlists, favoritos, descargas y backend musical propios de la app anterior.

## Versión actual
- App: Dronnk
- Versión: `2.0.0`
- `versionCode`: `14`
- `compileSdk`: 36
- `targetSdk`: 36
- `minSdk`: 26
- Package: `com.german.dronnk`
- Identidad visual: negro + rojo escarlata

## Interfaz
Dronnk usa una interfaz oscura con acentos escarlata y cuatro áreas principales:
- Inicio: estado del asistente, acceso rápido al micrófono y ejemplos de órdenes.
- Herramientas: accesos a apps, llamadas, música externa, cámara, linterna, volumen, Wi‑Fi, Bluetooth, navegación, alarmas, calendario y batería.
- Conversación: historial de órdenes/respuestas, entrada por texto y botón de voz.
- Ajustes: voz, permisos, apariencia, actualizaciones e información de la app.

## Voz
- `SpeechRecognizer` configurado en español de Perú (`es-PE`).
- Respuestas habladas con `TextToSpeech`.
- Entrada de órdenes por voz o texto.
- La escucha se inicia de forma visible desde Dronnk; no se oculta un micrófono permanente en segundo plano.

## Comandos disponibles
- "Abre WhatsApp" y otras aplicaciones instaladas.
- "Llama a mamá" / "llama a Juan" / "llama al 999...".
- Preparar SMS por número o contacto.
- "Pon Dash Berlin en Spotify".
- "Reproduce ... en YouTube Music".
- "Pon ... en YouTube".
- Abrir cámara.
- Encender/apagar linterna.
- Subir, bajar o silenciar volumen multimedia.
- Abrir Wi‑Fi, Bluetooth y configuración.
- Consultar hora, fecha y batería.
- Navegar a un lugar mediante una app de mapas compatible.
- Realizar búsquedas web.
- Crear alarmas.
- Preparar eventos de calendario.
- Buscar nuevas versiones de Dronnk en GitHub Releases y descargar el APK publicado.

## Contactos
Para órdenes como "llama a mamá", Dronnk solicita `READ_CONTACTS` en tiempo de ejecución, busca el contacto por nombre y abre el marcador con el número encontrado. No realiza llamadas ocultas ni evita las protecciones de Android.

## Música externa
Dronnk ya no incorpora un reproductor musical propio. Los comandos de música se redirigen a apps externas compatibles:
- Spotify
- YouTube Music
- YouTube

La capacidad exacta de reproducción automática depende de los intents/deep links que cada app externa acepte. Si no puede abrir la app nativa, Dronnk usa su búsqueda web como fallback cuando corresponde.

## Privacidad y permisos
Permisos declarados:
- `INTERNET`
- `RECORD_AUDIO`
- `CAMERA`
- `READ_CONTACTS`
- `REQUEST_INSTALL_PACKAGES` para el flujo de actualización por APK.

Los permisos sensibles se solicitan cuando la función correspondiente los necesita. Dronnk no intenta concederse permisos por sí mismo ni saltarse restricciones del sistema operativo.

## Build local
El proyecto usa Android Gradle Plugin 8.9.x y Gradle Wrapper 8.11.1. No se usan GitHub Actions.

Antes de publicar una actualización hay que verificar que el nuevo APK esté firmado con el mismo certificado que Dronnk 1.x. El repositorio incluye `scripts/verify-update-signature.ps1` para comparar el SHA-256 de ambos certificados con `apksigner`.

## Actualizaciones
Dronnk consulta la última GitHub Release de `HaroldGerman/DronnkAndroid`. Si encuentra una versión superior, permite descargar su APK con `DownloadManager`.

## Antes de publicar 2.0
1. Compilar con Android SDK/API 36.
2. Probar las funciones en Android 16.
3. Firmar con el mismo certificado de Dronnk 1.x.
4. Comparar las firmas con `scripts/verify-update-signature.ps1`.
5. Instalar encima de Dronnk 1.x sin desinstalar.
6. Solo entonces publicar el APK en GitHub Releases.

Consulta `docs/FINAL_CHECKLIST.md` para el control final.
