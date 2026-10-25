# Wii Backup Manager para Android

Convierte copias de seguridad de tus propios discos de Wii entre **ISO ↔ WBFS** directamente en el teléfono.

## Qué hace

- Elige una carpeta con tus juegos (busca también en subcarpetas) y muestra ID, título, región, formato y tamaño.
- Marca varios juegos y conviértelos por lotes: las `.iso` pasan a `.wbfs` y los `.wbfs` pasan a `.iso`.
- Conversión sin pérdida: la ISO reconstruida desde el WBFS es idéntica byte a byte a la original.
- WBFS más pequeño: los bloques de 256 KiB que solo contienen ceros no se guardan.
- Divide el WBFS en partes de 4 GB (`.wbfs`, `.wbf1`, `.wbf2`…) para tarjetas FAT32, y lee WBFS divididos.
- Crea la estructura que esperan los cargadores USB: `wbfs/Título [ID]/ID.wbfs` y `games/Título [ID]/game.iso`.
- Trabaja en segundo plano con notificación de progreso y botón de cancelar. Si algo falla o cancelas, borra el archivo a medio escribir.
- Nunca borra ni sobrescribe tus archivos originales.
- Mantén pulsado un juego para ver su información.

## Cómo compilar el APK

1. Abre la carpeta `WiiBackupManager` con **Android Studio** (Ladybug o más reciente). Descargará el SDK y Gradle.
2. Menú **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Cópialo al teléfono e instálalo (hay que permitir «instalar apps desconocidas»).

Por línea de comandos, con Gradle 8.9 o superior y `ANDROID_HOME` apuntando al SDK: `gradle assembleDebug`.

Requiere Android 8.0 (API 26) o superior. No usa librerías externas.

## Límites que conviene conocer

- Solo discos de **Wii**. Los de GameCube se rechazan. Los formatos comprimidos (CISO, WIA, RVZ) hay que pasarlos a ISO antes.
- No «limpia» (scrub) el disco: no descifra las particiones, así que solo ahorra espacio donde la ISO ya tiene bloques en cero. Una ISO de retail con relleno aleatorio dará un WBFS casi igual de grande. Para limpiar de verdad hay que descifrar el disco con la clave común de la Wii, que no incluyo.
- Una ISO de más de 4 GB no cabe en FAT32; usa WBFS dividido, o una carpeta en memoria interna o exFAT.
- Al pasar WBFS → ISO, la opción «rellenar hasta el tamaño estándar» deja la ISO en 4.699.979.776 bytes (una capa) u 8.511.160.320 (doble capa).

## Pruebas del núcleo (en un PC con Java 17+)

```
javac -d out app/src/main/java/com/wbm/core/*.java tools/CoreTest.java
java -cp out CoreTest /ruta/de/trabajo
```

Comprueba ISO → WBFS → ISO con SHA-256, WBFS dividido, tamaños no múltiplos del bloque, rechazo de GameCube y de archivos inválidos, partes que faltan y cancelación.

## Estructura

- `app/src/main/java/com/wbm/core/`: formato WBFS (sin dependencias de Android).
- `app/src/main/java/com/wbm/app/`: pantalla, servicio de conversión y acceso a carpetas (SAF).
