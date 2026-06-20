# MDVClans 1.7.4

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


## Cambios 1.7.4

- Creación de clan guiada por chat: primero nombre, luego ID/tag.
- Menús nativos separados en `plugins/MDVClans/NativeMenus/`.
- Permisos de clan con nombres/descripciones en español y lanas por jerarquía.
- Raza/nivel de MMOCore lee una lista configurable de placeholders e ignora `NoMatch`.
- `%mdvclans_board%`, `%mdvclans_board_line_1%`, `%mdvclans_board_line_2%`, `%mdvclans_board_line_3%` para menús de MDVSocial.
- Correo personal desde el menú de miembro: pide el texto por chat y lo envía con MDVSocial.
- Edición de tablero y logs quedan restringidos por rango.
