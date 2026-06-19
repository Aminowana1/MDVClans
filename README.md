# MDVClans 1.6.2

Sistema de clanes social para MDVCRAFT, pensado para Purpur/Paper 1.21.6.

## Base incluida

- Crear clan con ID + nombre.
- Costo configurable por Vault, desactivado por defecto.
- Límite de miembros configurable.
- Invitación, clanes abiertos y solicitudes de ingreso para clanes cerrados.
- Miembros con roles numéricos 0-5.
- Nombres personalizados de roles por clan.
- Chat de clan toggle y mensaje directo.
- Base del clan: `/clan setbase` y `/clan base`.
- Cancelación de PvP entre miembros.
- Lista e info de clanes.
- Relaciones básicas: neutral, aliado, enemigo.
- Banco de clan.
- Almacén compartido.
- Banner oficial con `WHITE_BANNER` por defecto.
- Nametags por relación.
- Logs básicos.
- Top simple de clanes.
- Estadísticas de kills entre clanes.
- Menús nativos con paginación y margen.
- Menús puente para MDVSocial.
- SQLite organizado en `plugins/MDVClans/Data/clans.db`.
- `messages.yml` para prefijo y mensajes principales editables.

## Cambios 1.6.2

- `/clan menu` ahora actúa como entrada limpia desde MDVSocial:
  - con clan: abre directamente **Gestión del clan**.
  - sin clan: abre **Lista de clanes / Crear clan**.
- El panel completo queda disponible con `/clan menu principal`, `/clan menu completo` o haciendo click en el banner del menú de gestión.
- Las listas grandes usan margen de 1 bloque y paginación: miembros, clanes, relaciones, buzón, logs y solicitudes.
- El banner del menú de información ahora solo permite ver; para cambiarlo se usa Ajustes del clan.
- Reforzada la validación de ID/nombre duplicados al crear y renombrar.
- Añadido `messages.yml`.

## Comandos principales

```txt
/clan ayuda
/clan crear <ID> <nombre>
/clan info [ID]
/clan lista [pagina]
/clan invitar <jugador>
/clan aceptar [ID]
/clan unirse <ID>
/clan abierto <on/off>
/clan salir
/clan expulsar <jugador>
/clan promover <jugador>
/clan degradar <jugador>
/clan setrango <jugador> <0-5>
/clan rol <0-5> <nombre>
/clan chat [mensaje]
/clan c <mensaje>
/clan setbase
/clan base
/clan relacion <ID> <neutral|aliado|enemigo>
/clan banco [depositar|retirar|log]
/clan depositar <cantidad>
/clan retirar <cantidad>
/clan almacen
/clan estandarte <set|ver|quitar>
/clan logs [pagina]
/clan top [fuerza|kills|banco]
/clan bajas
/clan tablero <ver|set|limpiar>
/clan correo <ver|clan|borrar>
/clan editar <nombre|id> <valor>
/clan solicitudes
/clan menu
/clan menu principal
/clan disolver confirmar
/mdvclans reload
```

## Placeholders

```txt
%mdvclans_id%
%mdvclans_name%
%mdvclans_tag%
%mdvclans_lpc_tag%
%mdvclans_role%
%mdvclans_role_number%
%mdvclans_member_count%
%mdvclans_is_in_clan%
%mdvclans_open%
```

Para LPC puedes usar:

```txt
{prefix}%mdvclans_lpc_tag%{name} » {message}
```

## Menús MDVSocial

Se generan en:

```txt
plugins/MDVClans/Menus/
```

Puedes copiarlos a:

```txt
plugins/MDVSocial/Menus/
```

Recomendación: desde el menú principal de MDVSocial, usa un botón con `COMMAND_PLAYER` y comando `clan menu` para evitar un menú intermedio.

## Build

Requiere Java 21 y Maven.

```bash
mvn clean package
```

El jar queda en:

```txt
target/MDVClans-1.6.2.jar
```
