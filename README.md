# MDVClans 1.10.0

Actualización de tiers/progreso de clanes para MDVCRAFT.

## Cambios principales

- Tiers de clan configurables: Banda, Hermandad, Compañía, Gremio y Reino.
- Cada clan guarda su tier en SQLite (`clans.tier`).
- Subida de tier usando dinero del banco del clan.
- Permiso nuevo `tier-upgrade` para decidir qué roles pueden mejorar el clan.
- Límites por tier: miembros, bases, aliados y almacén.
- Almacén dinámico por tier, con páginas cuando supera 54 slots.
- Bases múltiples básicas con `/clan setbase [n]` y `/clan base [n]`.
- Placeholders internos de menús para tier, límites y mejoras.

## Compilar

```bash
mvn clean package
```

El jar queda en `target/MDVClans-1.10.0.jar`.
