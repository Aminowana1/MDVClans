# MDVClans 1.7.0

Sistema de clanes social para MDVCRAFT.

## Cambio importante de arquitectura

MDVClans ahora funciona como **motor de clanes + UIs dinamicas base**.
MDVSocial puede construir los menus bonitos y llamar a MDVClans con comandos simples.

- MDVClans mantiene datos, permisos, roles, banco, almacen, relaciones, correos, estadisticas y UIs dinamicas.
- MDVSocial mantiene el hub visual, navegacion e items editables por YAML.

## UIs dinamicas disponibles

```txt
/clan abrir auto
/clan abrir gestion
/clan abrir sinclan
/clan abrir miembros
/clan abrir info
/clan abrir relaciones
/clan abrir relaciones_lista
/clan abrir almacen
/clan abrir lista
/clan abrir lista_sinclan
/clan abrir correo
/clan abrir top
/clan abrir top_kills
/clan abrir top_banco
/clan abrir bajas
/clan abrir logs
/clan abrir ajustes
/clan abrir rangos
/clan abrir permisos
/clan abrir solicitudes
```

Tambien funcionan aliases: `/clan ui <menu>`, `/clan interfaz <menu>` y `/clan menu <menu>`.

## Para MDVSocial

La forma recomendada es que MDVSocial use:

```yaml
action: MDVCLANS_OPEN
clans-menu: miembros
```

O, si usas solo comandos:

```yaml
action: COMMAND_PLAYER
commands:
  - 'clan abrir miembros'
```

## Build

```bash
mvn clean package
```

El jar queda en `target/MDVClans-1.7.0.jar`.
