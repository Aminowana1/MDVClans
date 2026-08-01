# MDVClans 1.10.25

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

El jar queda en `target/MDVClans-1.10.25.jar`.


## 1.10.25 - Administración de clanes

Permiso requerido: `mdvclans.admin`.

```text
/mdvclans almacen <ID> [pagina]
/mdvclans banco <ID> <agregar|quitar> <cantidad>
/mdvclans disolver <ID> confirmar
/mdvclans entrar <jugador> <ID>
```

- El almacén administrativo es completamente de solo lectura y permite navegar todas sus páginas.
- El banco puede ajustarse sin retirar o entregar dinero a un jugador.
- La disolución elimina inmediatamente el clan y expulsa a todos sus miembros.
- La entrada forzada ignora invitaciones y estado abierto/cerrado; no mueve al líder de otro clan para evitar dejarlo sin liderazgo.
- La lista de clanes muestra el nombre personalizado del rango líder y su jugador, por ejemplo `Emperador: Aminowana`.
- Placeholders internos nuevos: `{leader_role}`, `{leader_name}` y `{leader_line}`.

## 1.10.23 - Nombres seguros y color por tier

- El nombre y el ID/tag del clan ya no aceptan `&`, `§`, colores, formatos ni caracteres de control.
- Los clanes existentes se limpian automáticamente al iniciar.
- El nombre visible cambia por tier: T1 `&a`, T2 `&2`, T3 `&9`, T4 `&5`, T5 `&e`.
- El color se aplica en listas, rankings, información, menús y `%mdvclans_name%`.
- El nametag, el ID/tag y el prefijo de chat conservan su comportamiento anterior.
- `%mdvclans_name_plain%` devuelve el nombre sin color.



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


## 1.10.5

- Enemigos ahora son relación simétrica real: si A declara enemigo a B, B también registra a A como enemigo.
- Declarar enemigo cancela solicitudes diplomáticas pendientes entre ambos clanes.
- Si dos clanes son enemigos, volver a neutral ya no es instantáneo: envía una solicitud de paz al buzón del otro clan.
- Si dos clanes son enemigos, pedir alianza mantiene la enemistad activa hasta que el otro clan acepte.
- Las solicitudes de paz usan correo de clan con tipo `NEUTRAL_REQUEST`.
- El menú de correo puede mostrar botones `accept-neutral` y `reject-neutral` para aceptar o rechazar tratados de paz.

## Placeholder de chat para LPC

`%mdvclans_chat_prefix%` devuelve el bloque completo del clan usando `placeholders.chat-prefix-format`. Si el jugador no tiene clan, devuelve siempre una cadena vacía.

Ejemplo LPC:

```text
%mdvsocial_title_prefix%%mdvclans_chat_prefix%&6&l{name}{suffix} &r&e» &f{message}
```


## 1.10.16 - Copias ilimitadas del estandarte
- `/clan banner ver` funciona como dispensador seguro de copias del banner oficial.
- Cada clic entrega una copia limpia y mantiene la vista previa en el menú.
- El editor seguro de la 1.10.15 permanece sin cambios.

## 1.10.15 - Editor seguro de estandarte

`/clan banner set` abre una interfaz de 9 slots con entrada central exclusiva para banners, confirmación, cancelación y devolución segura del objeto si se cierra sin guardar.
