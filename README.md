# MDVClans 1.8.0

Actualización para MDVCRAFT centrada en perfiles de jugador de MMOCore dentro de las GUIs de clan.

## Cambios 1.8.0

- Las solicitudes de ingreso ahora resuelven `{race}` y `{level}` usando la misma lógica que la lista de miembros.
- Se añadió caché interna `player_profiles` en SQLite para guardar la última raza/nivel conocidos.
- Si el jugador está desconectado, MDVClans intenta mostrar raza/nivel desde caché.
- Si MMOCore permite leer `PlayerData` offline, MDVClans también intenta leer nivel y clase/raza por reflexión.
- En join se actualiza la caché de perfil tras unos ticks, para dar tiempo a que MMOCore cargue los datos.

## Config nueva

En `config.yml`:

```yaml
integrations:
  mmocore:
    use-direct-api: true
    level-placeholders:
      - '%mmocore_level%'
    race-placeholders:
      - '%mmocore_race%'
      - '%mmocore_class%'
      - '%mmocore_class_name%'
      - '%mmocore_player_class%'
      - '%mmocore_profession%'

profile-cache:
  enabled: true
  update-on-join-delay-ticks: 80
  unknown-level-text: 'Sin datos'
  unknown-race-text: 'Sin raza'
```

## Menús nativos

Edita:

```text
plugins/MDVClans/NativeMenus/*.yml
```

MDVSocial debe abrir GUIs nativas con acciones/commands como:

```bash
/clan abrir miembros
/clan abrir info
/clan abrir solicitudes
/clan abrir lista
```

## Compilar

```bash
mvn clean package
```

Jar esperado:

```text
target/MDVClans-1.8.0.jar
```
