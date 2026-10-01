# Dronnk 2.0 — Checklist final

## Código funcional terminado
- [x] Branding Dronnk.
- [x] Tema negro + rojo escarlata.
- [x] Inicio, Herramientas, Conversación y Ajustes.
- [x] Entrada por voz `es-PE`.
- [x] TextToSpeech en español.
- [x] Apertura de apps instaladas.
- [x] Cámara y linterna.
- [x] Volumen multimedia.
- [x] Wi-Fi, Bluetooth y ajustes.
- [x] Hora, fecha y batería.
- [x] Navegación con fallback a app de mapas compatible.
- [x] Búsqueda web.
- [x] Alarmas.
- [x] Eventos de calendario.
- [x] Llamadas por número.
- [x] Llamadas por nombre de contacto con permiso en runtime.
- [x] Preparación de SMS por número/contacto.
- [x] Búsqueda de música externa en Spotify.
- [x] Búsqueda de música externa en YouTube Music.
- [x] Búsqueda de música externa en YouTube.
- [x] Actualizador mediante GitHub Releases.
- [x] Sin GitHub Actions.
- [x] Gradle wrapper alineado con AGP 8.9.x.

## Validaciones obligatorias antes de publicar APK
- [ ] Ejecutar compilación real con Android SDK/API 36 instalado.
- [ ] Probar en Android 16.
- [ ] Verificar micrófono, TTS, cámara, contactos y navegación.
- [ ] Verificar Spotify/YouTube Music/YouTube instalados y fallbacks.
- [ ] Obtener o identificar la misma clave de firma de Dronnk 1.x.
- [ ] Firmar Dronnk 2.0 con ese mismo certificado.
- [ ] Comparar certificados con `scripts/verify-update-signature.ps1`.
- [ ] Instalar Dronnk 2.0 encima de Dronnk 1.x sin desinstalar.
- [ ] Publicar el APK en GitHub Releases solo después de superar todo lo anterior.

No se debe afirmar que el APK está listo para actualizar mientras exista algún punto pendiente de esta segunda sección.
