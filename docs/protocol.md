# Protocolo del control (celular ↔ TV)

La app de la TV levanta un servidor HTTP + WebSocket en el puerto **8080**.

- `GET /` → página del control (`tv-app/src/main/assets/web/index.html`)
- `GET /<archivo>` → archivos estáticos de `assets/web/`
- `WS  /ws` → canal de input

La TV muestra un QR con `http://<ip-tv>:8080/`.

## Mensajes (JSON, texto)

### Celular → TV

| Mensaje | Cuándo |
|---|---|
| `{"t":"hello","name":"Pixel 7","id":"k3j9x0a2qe"}` | Al abrir la conexión. `id` es estable por celular (localStorage): al reconectar, la TV le devuelve el mismo puesto aunque la conexión anterior no haya expirado |
| `{"t":"s","b":<int>}` | Cada vez que cambia el estado de los botones, y como keep-alive cada 1 s |
| `{"t":"ping","ts":<number>}` | Cada 2 s, para medir latencia |
| `{"t":"menu"}` | Botón de menú (pausa / menú de la TV) |

`b` es un **bitmask con el estado completo** de los botones (no eventos sueltos),
así un mensaje perdido nunca deja un botón pegado:

| Bit | Botón |
|---|---|
| 0 | UP |
| 1 | DOWN |
| 2 | LEFT |
| 3 | RIGHT |
| 4 | A |
| 5 | B |
| 6 | X |
| 7 | Y |
| 8 | L |
| 9 | R |
| 10 | START |
| 11 | SELECT |

### TV → Celular

| Mensaje | Significado |
|---|---|
| `{"t":"welcome","player":1}` | Conexión aceptada, asignado como jugador N (1–4) |
| `{"t":"full"}` | No hay puestos libres; la TV cierra la conexión |
| `{"t":"pong","ts":<number>}` | Respuesta al ping con el mismo `ts` |
| `{"t":"rumble","ms":<int>}` | Opcional: vibrar el celular |

## Subir juegos (HTTP)

`POST /api/upload?name=<nombre-de-archivo-url-encoded>`

- Cuerpo: los bytes crudos del archivo (`Content-Type: application/octet-stream`), no multipart.
- Formatos aceptados: `.nes`, `.sfc`, `.smc`, `.gb`, `.gbc`, `.gba`, `.md`, `.gen`, `.smd`, `.sms`, `.gg`
  y `.zip` que contenga uno de esos (se extrae el primero que se encuentre).
- Tamaño máximo: 64 MB.
- Respuesta `200`: `{"ok":true,"title":"Super Juego","system":"SNES"}`
- Respuesta de error (`400`/`413`/`500`): `{"ok":false,"error":"Formato no soportado"}` (mensaje en español, para mostrar tal cual).

`GET /api/systems` → `{"extensions":["nes","sfc",...,"zip"]}` (para el `accept` del input de archivo).

Al terminar una subida, la biblioteca de la TV se actualiza sola.

## Juegos instalados (HTTP)

`GET /api/games` →

```json
{"games":[{"id":"NES-Thwaite.nes","title":"Thwaite","system":"NES","systemName":"Nintendo",
           "size":24592,"addedAt":1759441234000,"saves":2}]}
```

- Ordenados por título. `size` en bytes, `addedAt` en ms (fecha del archivo), `saves` = ranuras
  de guardado usadas (0-4).

`POST /api/delete?id=<id-url-encoded>` (sin cuerpo) →
`{"ok":true}` o `{"ok":false,"error":"Juego no encontrado"}` (404).

Borra la ROM y también sus partidas guardadas (ranuras y SRAM). La biblioteca de la TV se
actualiza sola.

## Reglas

- Si un jugador se desconecta, la TV suelta todos sus botones (`b = 0`) y libera el puesto.
- Si no llega nada de un jugador en 3 s, se considera desconectado.
- El servidor usa `TCP_NODELAY`.
