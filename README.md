# Dronnk Android

App Android independiente de TushNH, enfocada en reproducción local-first.

## Flujo principal
1. Buscar canción.
2. Tocar canción.
3. Si ya existe localmente, reproducir de inmediato.
4. Si no existe, pedir al backend de Dronnk el MP3 preparado.
5. Guardarlo en `Music/Dronnk` mediante MediaStore.
6. Reproducir la URI local con Media3/ExoPlayer.

Una vez descargado el archivo, la reproducción ya no depende del streaming y puede continuar con la pantalla bloqueada.

## Identidad
- App: Dronnk
- Package: `com.german.dronnk`
- Versión: `1.1` (`versionCode=2`)
- Icono propio de Dronnk incluido.

## Backend
`https://dronnk-api-production.up.railway.app/`

Endpoints usados por el cliente actual:
- `GET /buscar?termino=...`
- `GET /descargar?url=...`
- `GET /descargar-video?url=...`

## Interfaz v1.1
- Búsqueda rediseñada.
- Cards de canciones.
- Iconos vectoriales, sin emojis.
- Favoritos con corazón outline/solid.
- Menú de opciones.
- Mini reproductor.
- Reproductor completo.
- Descargas de audio y video.
- Biblioteca, playlists y ajustes.
