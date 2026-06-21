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



## 1.10.3

- Los items de menús de acción nativos ahora respetan el YAML:
  - si el bloque del item no existe, no se dibuja;
  - si el bloque tiene `enabled: false`, no se dibuja;
  - si se oculta, tampoco responde al click.
- Afecta `member-action`, `clan-action` y `mail-action`.
- `profile` sigue siendo especial: para mostrarse debe tener `enabled: true`.

## 1.10.2
- El botón de perfil en el menú de acciones de miembro ahora es opcional por YAML.
- `menus.member-action.items.profile` solo aparece si tiene `enabled: true`.

## 1.10.1

Añade menú nativo de bases: `/clan abrir bases`, botones de TP y botones para establecer bases, con estado desbloqueado/no establecido/bloqueado por tier.

## 1.10.4

- Soporte para mostrar títulos equipados de MDVSocial en la lista de miembros.
- Placeholders internos: `{title}`, `{title_plain}`, `{title_colored}`, `{title_prefix}`, `{title_prefix_plain}`, `{title_id}`, `{active_title}`.
- En `NativeMenus/30-members.yml` se recomienda usar:

```yaml
- '&7Título: &r{title_colored}'
```

También se parsea PlaceholderAPI en las cabezas de miembros después de reemplazar `{player}`, por lo que también puede usarse:

```yaml
- '&7Título: &r%mdvsocial_title_colored_of_{player}%'
```
