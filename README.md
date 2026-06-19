# MDVClans 1.5.0

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
target/MDVClans-1.5.0.jar
```

El proyecto incluye GitHub Actions para compilarlo en la nube.

## Compatibilidad MDVSocial

MDVClans V1.5 mantiene `softdepend: MDVSocial`, pero no depende de MDVSocial para funcionar. Los menús se entregan como YAML compatibles para copiarlos manualmente a la carpeta de menús de MDVSocial.
