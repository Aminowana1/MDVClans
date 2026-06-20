# MDVClans 1.7.5

Actualización de pulido técnico para MDVCRAFT.

## Cambios principales

- `NativeMenus/*.yml` controla los items/títulos/lore de las GUIs nativas, separado por secciones para que no quede un archivo gigante.
- Corrección de nametags por relación: mismo clan verde, aliado azul, enemigo rojo simétrico, neutral gris.
- Sync extra de nametags en join/respawn y tras cambios de relación.
- `/mdvclans wipebases confirmar` borra todas las bases de clanes para reset de mundo.
- Banners de clan ocultan tooltip de patrones/ingredientes si `banners.hide-patterns: true`.

## Menús nativos

Edita:

```text
plugins/MDVClans/NativeMenus/*.yml
```

MDVSocial solo debe abrir nativas con comandos como:

```bash
/clan abrir miembros
/clan abrir info
/clan abrir lista
```

## Compilar

```bash
mvn clean package
```


## Cambios 1.7.5

- Creación de clan guiada por chat: primero nombre, luego ID/tag.
- Menús nativos separados en `plugins/MDVClans/NativeMenus/`.
- Permisos de clan con nombres/descripciones en español y lanas por jerarquía.
- Raza/nivel de MMOCore lee una lista configurable de placeholders e ignora `NoMatch`.
- `%mdvclans_board%`, `%mdvclans_board_line_1%`, `%mdvclans_board_line_2%`, `%mdvclans_board_line_3%` para menús de MDVSocial.
- Correo personal desde el menú de miembro: pide el texto por chat y lo envía con MDVSocial.
- Edición de tablero y logs quedan restringidos por rango.

## MDVClans 1.7.5

Cambios principales:

- El tablero de información soporta hasta 10 líneas.
- Placeholders PAPI añadidos: `%mdvclans_board_line_1%` hasta `%mdvclans_board_line_10%`.
- Los menús nativos usan `{board_line_1}` hasta `{board_line_10}`.
- La GUI de permisos se rediseñó como tabla visual por rangos, con columnas de rango y lanas verdes/rojas indicando si cada rango tiene el permiso.
- Se agregaron `NativeMenus/60-actions.yml` y `NativeMenus/70-permission-labels.yml` para que también sean configurables los menús de acción y las etiquetas/descripciones de permisos.
- Se actualizó `native-menus.yml` combinado para compatibilidad, aunque se recomienda usar `NativeMenus/*.yml`.

Para separar líneas del tablero usa `|`:

```bash
/clan tablero set Bienvenidos|Farmeo hoy a las 20:00|No sacar del almacén sin avisar
```

Ejemplo en menú nativo:

```yaml
lore:
  - '&f{board_line_1}'
  - '&f{board_line_2}'
  - '&f{board_line_3}'
  - '&f{board_line_4}'
  - '&f{board_line_5}'
  - '&f{board_line_6}'
  - '&f{board_line_7}'
  - '&f{board_line_8}'
  - '&f{board_line_9}'
  - '&f{board_line_10}'
```
