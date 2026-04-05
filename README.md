# ScreenMirror - Screen Mirroring Nativo para Android

Una aplicación de alto rendimiento construida con Kotlin y Jetpack Compose para transmitir la pantalla de tu dispositivo Android a Smart TVs y receptores locales (Chromecast, DLNA, AirPlay) de forma 100% privada y local.

## 🚀 Características Principales

- **Mirroring de Baja Latencia**: Optimizado mediante un sistema de `Channels` de alto rendimiento para evitar el lag.
- **Bitrate Adaptativo Inteligente**: Ajusta la calidad del video dinámicamente según la congestión de tu red Wi-Fi (desde 1Mbps hasta 4Mbps).
- **Compatibilidad Extrema (Media3 Fix)**: Utiliza `H.264 Baseline Profile` para solucionar bugs comunes de reproducción en Android TV y dispositivos con procesadores Amlogic/Allwinner.
- **Descubrimiento Robusto**: Soporte para protocolos SSDP/DLNA corregido para Android 14+ mediante vinculación de red explícita.
- **Diseño Moderno (Material You)**: Interfaz dinámica que se adapta a tus colores y un Modo Oscuro de alto contraste para máxima legibilidad.

## 🛠️ Tecnologías Utilizadas

- **UI**: Jetpack Compose con Material 3.
- **Captura**: MediaProjection API.
- **Encoding**: MediaCodec (AVC/H.264).
- **Servidor Local**: Ktor (CIO) para servir el stream `.ts`.
- **Protocolos**: NSD (Network Service Discovery), SSDP, Google Cast SDK.

## 📋 Requisitos

- Android 8.0 (API 26) o superior.
- Conexión a una Red Local (Wi-Fi).
- Permiso de Grabación de Pantalla (otorgado al iniciar).

## 🔧 Detalles Técnicos de Optimización

Para garantizar la mejor experiencia, la aplicación implementa:
1. **Escalado a 720p**: Reduce la carga de red manteniendo la relación de aspecto nativa del dispositivo.
2. **Buffer Drop Strategy**: Si el receptor es lento, la aplicación descarta paquetes antiguos en lugar de congelar el teléfono.
3. **Throttling de Parámetros**: Los ajustes de calidad se realizan de forma graduada (máximo una vez por segundo) para no saturar el hardware del encoder.

## 📄 Licencia

Este proyecto es de código abierto bajo la licencia MIT.
