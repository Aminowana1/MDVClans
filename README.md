# MDVClans 1.0.0

Sistema de clanes social para MDVCRAFT, pensado para Purpur/Paper 1.21.6.

## V1 incluido

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

Para LPC puedes usar, por ejemplo:

```txt
{prefix}%mdvclans_lpc_tag%{name} » {message}
```

## Build

Requiere Java 21 y Maven.

```bash
mvn clean package
```

El jar quedará en:

```txt
target/MDVClans-1.0.0.jar
```

El proyecto incluye GitHub Actions para compilarlo en la nube.

## Compatibilidad MDVSocial

MDVClans V1 deja `softdepend: MDVSocial`, pero no depende de MDVSocial todavía. La integración de correos, menús y tablero de clan se puede hacer en V1.5/V2 sin romper esta base.
