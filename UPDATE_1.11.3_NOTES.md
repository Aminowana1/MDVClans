# MDVClans 1.11.3 — Título nativo

Esta versión corrige la presentación del título: aparece integrado en la línea nativa debajo del nombre, junto al nametag original. Ya no se crea ningún TextDisplay ni armor stand para el título, ni se necesita mover o teletransportar un texto siguiendo al jugador.

Se conservan las correcciones de 1.11.2: un editor de almacén por clan para evitar sobrescribir objetos, protección de slots de navegación y edición de almacenes con `/mdvclans almacen <ID> [pagina]` para administradores.

## Instalar y configurar

Con el servidor apagado, sustituye el jar anterior de MDVClans por `MDVClans-1.11.3.jar` y vuelve a iniciar. Conserva la carpeta de datos y tu configuración. El reinicio también elimina las líneas temporales de la versión anterior, que no eran persistentes.

Dentro del apartado `nametags:` que ya tienes, sustituye únicamente el bloque `equipped-title-line:` por:

```yaml
  equipped-title-line:
    enabled: true
    position: 'below'
    format: '{title}'
    update-interval-ticks: 20
```

Mantén los demás ajustes de `nametags:` tal como los tienes. Las antiguas opciones de separación, altura, sombra y distancia del texto flotante ya no se utilizan. También se ignora `position: above`, porque Minecraft solo ofrece esta línea nativa debajo.

Puedes cambiar `format`, por ejemplo a `'&7Título: &r{title}'`. Para aplicar cambios de configuración posteriores, usa `/mdvclans reload`.

## Qué se conserva

- El formato existente del nombre, prefijo del clan, sufijo del nivel y equipos de nametag.
- El scoreboard lateral, los objetivos de otros plugins y sus puntuaciones.
- El scoreboard que cada jugador ya tiene asignado: esta función no lo sustituye.
- El banco monetario, los tiers, rankings y datos de clanes.

La nueva línea usa un objetivo propio de tipo BELOW_NAME, en el mismo scoreboard actual. Cada jugador tiene su título personal, sin mostrar un número delante. Si otro plugin ya utiliza BELOW_NAME (por ejemplo para mostrar vida), MDVClans lo respeta y deja un aviso en consola en vez de reemplazarlo. No puede haber dos objetivos nativos en ese mismo espacio.

## Límites del cliente

La altura, separación y alcance pertenecen al nametag nativo de Minecraft. En Java 1.21.6, esta línea se ve dentro de aproximadamente 10 bloques. No se puede colocar encima mediante esta función nativa. Cuando un jugador no tiene título, el texto y el número quedan vacíos; en clientes 1.21.6 puede quedar un pequeño espacio o fondo vacío debajo del nombre.

El formato de texto personalizado necesita un cliente Java 1.20.3 o posterior. Geyser reciente ya traduce este formato a Bedrock; comprueba la visualización con la versión de Geyser que tienes instalada. Clientes anteriores o traducciones antiguas pueden mostrar el valor numérico en lugar del texto.

Referencias: [API de puntuaciones Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/org/bukkit/scoreboard/Score.html), [límites del nametag nativo](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Belowname), [traducción de Geyser](https://raw.githubusercontent.com/GeyserMC/Geyser/master/core/src/main/java/org/geysermc/geyser/scoreboard/display/slot/BelownameDisplaySlot.java).

## Comprobar en el servidor

Equipa distintos títulos en MDVSocial con dos jugadores y confirma que cada título sale justo debajo de su nombre. Muévete y teletranspórtate: la línea debe formar parte del nametag, sin un texto flotante persiguiendo al jugador. Comprueba también que nombre, clan, nivel y scoreboard lateral conservan su formato.

La compilación terminó correctamente y pasaron las 32 pruebas automatizadas: 12 del título nativo y 20 de almacenamiento. La comprobación visual con tu servidor y tus clientes sigue siendo necesaria.
