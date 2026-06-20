# MDVClans 1.7.3

Actualización de pulido técnico para MDVCRAFT.

## Cambios principales

- `native-menus.yml` ahora controla prácticamente todos los items/títulos/lore de las GUIs nativas.
- Corrección de nametags por relación: mismo clan verde, aliado azul, enemigo rojo simétrico, neutral gris.
- Sync extra de nametags en join/respawn y tras cambios de relación.
- `/mdvclans wipebases confirmar` borra todas las bases de clanes para reset de mundo.
- Banners de clan ocultan tooltip de patrones/ingredientes si `banners.hide-patterns: true`.

## Menús nativos

Edita:

```text
plugins/MDVClans/native-menus.yml
```

MDVSocial solo debe abrir nativas con comandos como:

```bash
/clan abrir miembros
/clan abrir info
/clan abrir lista
```

## Compilar

```bash
mvn clean package
```
