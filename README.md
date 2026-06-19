# MDVClans 1.6.1

Sistema de clanes social para MDVCRAFT, pensado para Purpur/Paper 1.21.6.

## V1 base incluida

- Crear clan con ID + nombre.
- Costo configurable por Vault, desactivado por defecto.
- Límite de miembros configurable.
- Invitación y clanes abiertos.
- Miembros con roles numéricos 0-5.
- Nombres personalizados de roles por clan.
- Chat de clan toggle y mensaje directo.
- Base del clan: `/clan setbase` y `/clan base`.
- Cancelación de PvP entre miembros.
- Lista e info de clanes.
- Relaciones básicas: neutral, aliado, enemigo.
- PlaceholderAPI para LPC.
- SQLite.
- Menú nativo dinámico con `/clan menu`.
- GUIs de miembros, info, relaciones, almacén/banco, lista de clanes, buzón, bajas y ranking.
- Tablero de clan editable por rangos.
- Buzón de clan interno con correos entre clanes.

## Nuevo en V1.5

- Banco de clan con Vault:
  - todos pueden depositar por defecto.
  - rangos altos pueden retirar.
- Almacén compartido de clan.
- Estandarte oficial del clan.
- Nametags por relación:
  - mismo clan verde.
  - aliados azul.
  - enemigos rojo.
  - neutrales gris.
- Logs básicos por clan.
- Top simple de clanes por fuerza, kills y banco.
- Estadísticas de kills entre clanes.
- Menús YAML compatibles con MDVSocial generados en `plugins/MDVClans/Menus`.
- Base SQLite guardada en `plugins/MDVClans/Data/clans.db`.
- Config preparada para futura migración a MySQL.


## Nuevo en V1.6.1

- Botón de **Ajustes del clan** en el menú principal.
- Menú de ajustes para rangos altos:
  - cambiar nombre del clan.
  - cambiar ID/tag del clan.
  - cambiar nombres de rangos.
  - cambiar/quitar banner.
  - ver permisos por rango.
  - alternar clan abierto/invitación.
  - instrucciones para disolver.
- Solicitudes pendientes de ingreso:
  - si un clan está cerrado, `/clan unirse <ID>` crea solicitud.
  - el menú muestra cabezas con nombre, estado, nivel y raza si PlaceholderAPI/MMOCore responde.
  - click izquierdo acepta; click derecho borra.
  - si el jugador entra a otro clan, su solicitud se limpia.
- Click en miembro ahora abre submenú de acciones.
- Click en clan de la lista ahora abre submenú de acciones.
- Click en correo ahora abre submenú para responder o eliminar.
- Acciones bloqueadas aparecen como `GRAY_DYE` si no tienes rango.
- Menús puente nuevos: `clan_ajustes.yml` y `clan_solicitudes.yml`.

## Comandos principales

```txt
/clan ayuda
/clan menu
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
/clan banco
/clan banco depositar <cantidad>
/clan banco retirar <cantidad>
/clan depositar <cantidad>
/clan retirar <cantidad>
/clan almacen
/clan estandarte set
/clan estandarte ver
/clan estandarte quitar
/clan logs [pagina]
/clan top [fuerza|kills|banco]
/clan bajas [ID]
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
%mdvclans_bank%
%mdvclans_kills%
%mdvclans_deaths%
%mdvclans_strength%
```

Para LPC puedes usar, por ejemplo:

```txt
{prefix}%mdvclans_lpc_tag%{name} » {message}
```

## Menús MDVSocial

MDVClans genera estos archivos:

```txt
plugins/MDVClans/Menus/clan.yml
plugins/MDVClans/Menus/clan_gestion.yml
plugins/MDVClans/Menus/clan_rankings.yml
plugins/MDVClans/Menus/clan_ajustes.yml
plugins/MDVClans/Menus/clan_solicitudes.yml
```

Puedes copiarlos a:

```txt
plugins/MDVSocial/Menus/
```

Luego recarga MDVSocial para que los lea.

## Build

Requiere Java 21 y Maven.

```bash
mvn clean package
```

El jar quedará en:

```txt
target/MDVClans-1.6.1.jar
```

El proyecto incluye GitHub Actions para compilarlo en la nube.

## Compatibilidad MDVSocial

MDVClans V1.6.1 mantiene `softdepend: MDVSocial`, pero no depende de MDVSocial para funcionar. Los menús se entregan como YAML compatibles para copiarlos manualmente a la carpeta de menús de MDVSocial.


## Menús nativos V1.6

Usa `/clan menu` para abrir la interfaz dinámica. Si el jugador no tiene clan, verá lista de clanes y crear clan. Si tiene clan, verá miembros, tablero/info, relaciones, almacén/banco, lista de clanes y base.

## Tablero y correo de clan

```txt
/clan tablero ver
/clan tablero set <texto>
/clan tablero limpiar
/clan correo clan <ID> <mensaje>
/clan correo borrar <id>
```

Los menús YAML generados en `plugins/MDVClans/Menus/` son accesos rápidos para MDVSocial; puedes copiarlos a `plugins/MDVSocial/Menus/`.
