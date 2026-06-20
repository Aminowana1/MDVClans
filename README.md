# MDVClans 1.9.0

Actualización de roles customizables y permisos por clan.

## Cambios principales

- Migración automática de roles 0-5 al nuevo sistema 0-4.
- Eliminado el rol fijo `Plebeyo` como nivel separado.
- El líder queda como rango 4 y siempre conserva todos los permisos.
- El menú de permisos de información ahora muestra rangos 0-4.
- Nuevo editor de permisos desde Ajustes del clan.
- Cada clan puede activar/desactivar permisos por rol con lanas verdes/rojas.
- Los permisos reales de comandos y acciones GUI ahora respetan la configuración custom del clan.
- Botón para resetear los permisos del clan a los valores por defecto de `config.yml`.

## Compilar

```bash
mvn clean package
```

Jar esperado:

```text
target/MDVClans-1.9.0.jar
```
