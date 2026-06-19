# MDVClans 1.7.2

Sistema de clanes para MDVCRAFT.

## Cambios de 1.7.2

- `native-menus.yml` ahora soporta texturas en items `PLAYER_HEAD`, igual que MDVSocial.
- Soporta claves: `texture`, `custom-head-texture`, `head-texture`, `skull-texture`, `texture-base64`.
- También soporta `head-owner` y placeholders como `{player}` cuando aplique.
- Los botones de volver/back de las GUIs nativas son configurables por menú.
- El back puede abrir otra GUI nativa, abrir un menú de MDVSocial, ejecutar un comando, cerrar o intentar volver al panel anterior.

## Ejemplo de cabeza con textura

```yaml
global:
  back:
    material: PLAYER_HEAD
    texture: 'BASE64_DE_MINECRAFT_HEADS'
    name: '&6&lVolver'
    lore:
      - '&7Regresa al menú anterior.'
```

## Ejemplo de back hacia GUI nativa

```yaml
menus:
  members:
    back:
      slot: 49
      material: ARROW
      name: '&6&lVolver'
      action: NATIVE
      target: gestion
```

## Ejemplo de back hacia MDVSocial

```yaml
menus:
  management:
    back:
      slot: 22
      material: PLAYER_HEAD
      texture: 'BASE64_DE_MINECRAFT_HEADS'
      name: '&6&lVolver al menú social'
      action: MDVSOCIAL
      target: clan_con_clan
```

## Acciones disponibles para back

- `NATIVE`: abre otra GUI nativa de MDVClans usando `target`.
- `MDVSOCIAL`: ejecuta `/social <target>`.
- `COMMAND`: ejecuta el comando de `command` como jugador.
- `CLOSE`: cierra el inventario.
- `PREVIOUS`: intenta volver al panel nativo anterior.

## Compilar

```bash
mvn clean package
```

El jar saldrá en:

```text
target/MDVClans-1.7.2.jar
```
