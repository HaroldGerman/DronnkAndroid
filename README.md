# Jarvis Android

Jarvis es la reestructuración completa de Dronnk como asistente personal Android. La aplicación conserva el package `com.german.dronnk` para mantener la continuidad de actualización, pero la funcionalidad musical anterior fue retirada.

## Versión actual
- App: Jarvis
- Versión: `2.0.0`
- `versionCode`: `14`
- `compileSdk`: 36
- `targetSdk`: 36
- `minSdk`: 26
- Package: `com.german.dronnk`

## Capacidades actuales
- Entrada por voz con SpeechRecognizer en español de Perú.
- Respuesta hablada mediante TextToSpeech.
- Entrada de órdenes por texto.
- Abrir aplicaciones instaladas.
- Abrir cámara, configuración, Wi-Fi y Bluetooth.
- Encender y apagar la linterna.
- Subir, bajar o silenciar el volumen multimedia.
- Consultar hora, fecha y porcentaje de batería.
- Preparar llamadas telefónicas.
- Preparar mensajes SMS.
- Abrir navegación hacia un lugar.
- Realizar búsquedas web.
- Crear alarmas.
- Preparar eventos de calendario.
- Buscar nuevas versiones publicadas en GitHub Releases y descargar su APK.

## Privacidad y permisos
Jarvis solicita los permisos Android únicamente cuando una función los necesita. No intenta concederse permisos por sí mismo ni saltarse las restricciones del sistema operativo.

Permisos declarados actualmente:
- `INTERNET`
- `RECORD_AUDIO`
- `CAMERA`
- `REQUEST_INSTALL_PACKAGES` para el flujo de actualización por APK.

## Actualizaciones
Jarvis consulta la última GitHub Release de `HaroldGerman/DronnkAndroid`. Si encuentra una versión superior, permite descargar el APK con DownloadManager. No se usan GitHub Actions para compilar ni publicar la aplicación.

## Música
La reproducción, biblioteca, favoritos, playlists, descargas de música/video, Media3/ExoPlayer y el backend musical de Dronnk no forman parte de Jarvis.
