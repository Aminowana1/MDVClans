# MDVClans 1.11.2

## Instalar

1. Con el servidor apagado, sustituye el jar anterior de MDVClans por `MDVClans-1.11.2.jar`.
2. Conserva la carpeta `plugins/MDVClans`, tu configuración y la base de datos existentes.
3. Inicia el servidor. MDVSocial debe estar instalado y activo para mostrar sus títulos; no necesita cambios en su código.

El proyecto conserva Java 21 y la dependencia Paper 1.21.6 del original. No cambia el esquema de la base de datos ni los formatos anteriores del nombre, nivel, clan o scoreboards.

## Línea de título

La nueva línea se activa por defecto y muestra el título equipado real, con sus colores. Si no hay título o MDVSocial no está disponible, la línea se oculta. No usa el objetivo `belowName`, ni añade texto al prefijo/sufijo existente.

En tu `config.yml` de MDVClans, añade este bloque **dentro del apartado `nametags:` existente**, sin duplicar ni sustituir ese apartado:

```yaml
  equipped-title-line:
    enabled: true
    position: 'above' # 'above' encima; 'below' debajo del nombre
    format: '{title}'
    line-gap: 0.32
    name-height-offset: 0.5
    max-distance-blocks: 32
    update-interval-ticks: 20
    shadow: true
    background: false
```

Para ponerlo debajo, cambia `position` a `'below'`. `line-gap` cambia la separación y `name-height-offset` ajusta la altura de referencia. `format` permite, por ejemplo, `'&7Título: &r{title}'`. Ejecuta `/mdvclans reload` después de editar la configuración.

El título sigue al jugador y se limpia al desconectarse, teleportarse, cambiar de mundo, recargar o apagar. Se oculta para el propio jugador y mientras está agachado, invisible, muerto o en espectador; respeta la visibilidad del jugador para cada observador.

## Almacén y administración

Cada clan permite **una persona editando el almacén a la vez**, también si abre otra página o usa el comando de admin. Esto evita que una copia antigua sobrescriba depósitos o retiradas de otra persona. Los demás pueden abrirlo después de que se cierre y guarde.

Puedes personalizar el aviso añadiendo dentro de `storage-chest:`:

```yaml
  busy-message: '&cOtra persona está usando el almacén de este clan. Espera a que lo cierre.'
```

Con permiso `mdvclans.admin`, usa:

```text
/mdvclans almacen <ID> [pagina]
```

Permite retirar y depositar objetos, usar Shift, arrastrar sobre espacios útiles y cambiar de página. Guarda los cambios igual que el almacén normal. No exige pertenecer al clan ni tener un rango en él. Los slots de navegación y los huecos fuera de la capacidad siguen bloqueados; el doble clic de recogida se bloquea en páginas con slots protegidos.

El banco monetario conserva su comando anterior:

```text
/mdvclans banco <ID> <agregar|quitar> <cantidad>
```

Si falla el guardado de una página, se conserva su sesión en memoria y se impide abrir una copia antigua. Al intentar abrirlo de nuevo se reintenta primero ese guardado.

## Comprobación en el servidor

- Equipa otro título en MDVSocial y verifica el cambio de la línea sin alterar nombre, clan, nivel ni scoreboard.
- Prueba `above` y `below` y recarga para ajustar la altura si es necesario.
- Abre el mismo almacén con dos jugadores: el segundo recibe el aviso. Deposita/retira con el primero, cierra y abre con el segundo para confirmar los objetos.
- Repite con uno de los jugadores usando `/mdvclans almacen`, y comprueba que sus depósitos y retiradas persisten al reabrir y al cambiar de página.
- Comprueba también la visualización con tus clientes Java y Bedrock y los demás plugins del servidor.

La compilación y las pruebas automatizadas se verifican en esta entrega. No se ha ejecutado en tu servidor real.
