# Dronnk Android

App Android independiente de TushNH.

## Flujo principal
1. Buscar canción.
2. Tocar canción.
3. Si ya existe localmente, reproducir de inmediato.
4. Si no existe, pedir al backend un MP3 preparado.
5. Guardarlo en `Music/Dronnk` mediante MediaStore.
6. Reproducir **solo la URI local** con Media3/ExoPlayer.

Este diseño evita depender de streaming durante la reproducción y permite audio con pantalla bloqueada una vez descargado el archivo.

## Identidad
- App: Dronnk
- Package: `com.german.dronnk`
- Versión: `1.0` (`versionCode=1`)

## Backend inicial
Por compatibilidad, `ApiClient` apunta temporalmente a `https://haroldstream.me/` para búsqueda y preparación del MP3. La arquitectura deja `network/` aislado para cambiarlo después por `api.dronnk...` sin tocar el reproductor.

## Importante sobre video
La UI incluye la acción `Descargar video`, pero el backend nuevo de Dronnk debe conectarse a un proveedor de video autorizado. No se mezcló esa función con el flujo automático del MP3.

## Abrir
Abrir la carpeta `DronnkAndroid` en Android Studio, sincronizar Gradle y ejecutar en Android.
