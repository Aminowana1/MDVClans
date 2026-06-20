# MDVClans 1.8.1

Parche de corrección sobre 1.8.0.

## Cambios

- La descripción pública ahora se divide automáticamente en líneas.
  - Usa `clan-description.max-length` y `clan-description.lines`.
  - Ejemplo: 150 caracteres / 5 líneas = aprox. 30 caracteres por línea.
- Los correos de clan ahora se dividen automáticamente en `message_line_1` a `message_line_10`.
- El tablero del clan sigue usando líneas manuales con `|`, pensado para reglas o avisos intencionales.
- El item de descripción del menú `info` ahora solo permite ver la descripción.
- La descripción se edita desde el item de `settings`.
- Corrección visual para evitar que el item quede como ghost item en la mano al editar descripción desde GUI.
- Ajuste de nametags para evitar desync de colores cuando el scoreboard principal es compartido.
- Re-sincronización extra de nametags al aceptar alianzas.

## Compilar

```bash
mvn clean package
```

Jar esperado:

```text
target/MDVClans-1.8.1.jar
```
