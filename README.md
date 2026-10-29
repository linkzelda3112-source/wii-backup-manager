# Wii Backup Manager para Android

Convierte copias de seguridad de tus propios discos de Wii entre **ISO, RVZ/WIA y WBFS** directamente en el teléfono. Versión 0.0.8.

## Qué hace

- **Destino fijo**: elige una vez tu memoria USB (OTG), tarjeta SD o carpeta donde se guardan los juegos convertidos; queda seleccionado y se recuerda entre sesiones, como en Wii Backup Manager.
- **Agregar juegos** por separado del destino: con «Agregar juego (ISO/RVZ)» eliges uno o varios archivos sueltos, o con «Escanear carpeta» sumas de golpe todos los que haya en una carpeta (y subcarpetas). Muestra carátula, ID, título, región, formato y tamaño en una cuadrícula.
- Marca varios juegos y conviértelos por lotes: `.iso` y `.rvz`/`.wia` pasan a `.wbfs`; `.wbfs` pasa a `.iso`. El resultado siempre se guarda en el destino elegido.
- Conversión sin pérdida: la ISO reconstruida desde el WBFS es idéntica byte a byte a la original.
- **Soporte RVZ**: reconstruye la ISO original a partir de un `.rvz` (o `.wia`) de Dolphin, incluyendo la descompresión (Zstandard, LZMA/LZMA2, bzip2), el relleno de los datos de fábrica que RVZ no guarda y el recifrado de las particiones de Wii. Al terminar se verifica automáticamente que el resultado coincide con el hash que trae el propio archivo RVZ; si no coincide, la app avisa en vez de entregar un archivo dañado en silencio.
- WBFS más pequeño: los bloques de 256 KiB que solo contienen ceros no se guardan.
- Divide el WBFS en partes de 4 GB (`.wbfs`, `.wbf1`, `.wbf2`…) para tarjetas FAT32, y lee WBFS divididos. Al elegir la carpeta, la app intenta detectar si es FAT32, exFAT o NTFS y activa la división automáticamente cuando hace falta.
- Crea la estructura que esperan los cargadores USB: `wbfs/Título [ID]/ID.wbfs` y `games/Título [ID]/game.iso`.
- Carátulas descargadas de GameTDB, con caché para no repetir la descarga.
- Trabaja en segundo plano con notificación de progreso y botón de cancelar. Si algo falla o cancelas, borra el archivo a medio escribir.
- Nunca borra ni sobrescribe tus archivos originales.
- Mantén pulsada la carátula de un juego para ver su información.
- Pestaña «Donar» con los datos para aportar lo que quieras.

## Cómo compilar el APK

1. Abre la carpeta `wii-backup-manager` con **Android Studio** (Ladybug o más reciente). Descargará el SDK, Gradle y las librerías (zstd, xz, commons-compress, Glide).
2. Menú **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Cópialo al teléfono e instálalo (hay que permitir «instalar apps desconocidas»).

Por línea de comandos, con Gradle 8.9 o superior y `ANDROID_HOME` apuntando al SDK: `gradle assembleDebug`.

El repositorio también compila el APK automáticamente en GitHub Actions y lo publica como Release en cada cambio; no hace falta una PC para conseguirlo.

Requiere Android 8.0 (API 26) o superior.

## Límites que conviene conocer

- Solo discos de **Wii**. Los de GameCube se rechazan (el lector RVZ sí entiende contenedores de GameCube pero la conversión a WBFS no, porque WBFS es un formato exclusivo de Wii).
- No «limpia» (scrub) una ISO normal: no descifra las particiones, así que al convertir una `.iso` solo se ahorra espacio donde ya hay bloques en cero. Una ISO de retail con relleno aleatorio dará un WBFS casi igual de grande. Al convertir desde `.rvz`, en cambio, sí se reconstruye la ISO completa (el ahorro de espacio ya lo hizo Dolphin al crear el RVZ).
- El lector RVZ se construyó siguiendo la especificación pública del formato (documentación de Dolphin + la wiki técnica del disco de Wii), pero no se probó con un disco real todavía. Por eso compara automáticamente el resultado con el hash guardado en el propio archivo y avisa si no coincide; conviene revisar ese aviso antes de confiar en el WBFS resultante.
- Una ISO de más de 4 GB no cabe en FAT32; usa WBFS dividido, o una carpeta en memoria interna, exFAT o NTFS.
- Al pasar WBFS → ISO, la opción «rellenar hasta el tamaño estándar» deja la ISO en 4.699.979.776 bytes (una capa) u 8.511.160.320 (doble capa).
- La detección de FAT32/exFAT/NTFS depende de que el fabricante del teléfono exponga la ruta real del volumen (Android 11+); si no se puede saber, la app lo dice y divide los WBFS por seguridad.

## Pruebas del núcleo

El núcleo ISO/WBFS (`app/src/main/java/com/wbm/core/`, sin dependencias de Android) se puede probar en cualquier PC con Java 17+:

```
javac -d out app/src/main/java/com/wbm/core/*.java tools/CoreTest.java
java -cp out CoreTest /ruta/de/trabajo
```

Comprueba ISO → WBFS → ISO con SHA-256, WBFS dividido, tamaños no múltiplos del bloque, rechazo de GameCube y de archivos inválidos, partes que faltan y cancelación.

El núcleo RVZ (`app/src/main/java/com/wbm/core/rvz/`) tiene sus propias pruebas en `app/src/test/java/...`, que corren con `./gradlew test` (las ejecuta también la compilación automática en GitHub Actions). Verifican el árbol de hashes, el cifrado AES, el generador de datos de relleno, la descompresión Zstandard, y el formato de la cabecera (incluida la descompresión de las tablas `raw_data` y de grupos); también arman un archivo RVZ sintético completo de principio a fin y comprueban que se reconstruye byte a byte. No incluyen un archivo `.rvz` real de un juego porque no se dispuso de uno para las pruebas.

## Estructura

- `app/src/main/java/com/wbm/core/`: formato WBFS (sin dependencias de Android).
- `app/src/main/java/com/wbm/core/rvz/`: lector RVZ/WIA (sin dependencias de Android, salvo las librerías de descompresión).
- `app/src/main/java/com/wbm/app/`: pantalla, servicio de conversión, carátulas, detección de FAT32 y acceso a carpetas (SAF).
