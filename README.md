# Teclado XP

Teclado para Android en español, con las funciones de un teclado moderno, que además convierte la escritura en un juego: cada palabra te da experiencia (XP), subes de nivel, desbloqueas temas y consigues logros.

## Funciones de teclado

- **Escritura deslizante (glide):** desliza el dedo por las letras sin levantarlo y el teclado reconoce la palabra comparando la forma de tu trazo con la de cada palabra del diccionario.
- **Autocorrector inteligente:** corrige al presionar espacio o un signo de puntuación. Tiene en cuenta qué teclas están cerca (es más fácil confundir *r* con *t* que con *m*), letras intercambiadas y tildes (`cancion` → `canción`, `manana` → `mañana`). Si presionas ⌫ justo después, se deshace la corrección y el teclado aprende tu palabra.
- **Sugerencias:** tres sugerencias arriba del teclado mientras escribes; la del centro (resaltada) es la que se aplicará.
- **Predicción de la siguiente palabra:** aprende qué palabras sueles escribir después de otras.
- **Aprende de ti:** las palabras que usas y que no están en el diccionario se agregan solas.
- **Mantener presionado:** acentos, números de la fila superior, signos (en el punto) y emojis (en la coma).
- **Gestos:** deslizar sobre la barra espaciadora mueve el cursor; deslizar a la izquierda desde ⌫ borra palabras enteras; mantener la barra espaciadora cambia de teclado; doble toque en ⇧ bloquea mayúsculas; doble espacio pone punto.
- Mayúscula automática al empezar oraciones, botón Enter que cambia según la app (enviar, buscar, siguiente…), teclados de símbolos, vibración y tres alturas de teclado.
- En contraseñas no se guardan palabras ni se dan sugerencias.

## Sistema de experiencia

- La insignia redonda a la izquierda de la barra de sugerencias muestra tu **nivel** con un anillo de progreso, y debajo hay una **barra de XP**.
- Cada palabra da XP según sus letras, con bonus por escribir deslizando, por usar sugerencias y por autocorrecciones.
- **Combo:** si escribes palabras seguidas sin pausas largas, el multiplicador sube hasta x2 (🔥).
- **Racha diaria:** el primer mensaje del día da un bono que crece con los días seguidos que escribes.
- **Niveles 1–100** con rangos: Novato, Tecleador, Aprendiz, Escriba, Veloz, Experto, Maestro, Gran Maestro, Leyenda, Mítico y Dios del Teclado.
- Al subir de nivel aparece una animación con confeti.
- **Temas desbloqueables:** Noche y Claro (nivel 1), Océano (5), Bosque (10), Neón (15), Atardecer (20), Oro (30), Matrix (40), Arcoíris (50) y Galaxia (75).
- **16 logros** con recompensas de XP.

Si tocas tu nivel en el teclado se abre la pantalla de perfil, con estadísticas, logros, temas y ajustes.

## Cómo está hecho

- Kotlin, sin librerías externas: el teclado es un `InputMethodService` con una única vista dibujada a mano (`KeyboardView`).
- Diccionario de 50,000 palabras en español con frecuencia de uso (`assets/es.txt`).
- `Dictionary.kt`: autocorrector (distancia de edición con peso por cercanía de teclas), autocompletado, reconocimiento de trazos y aprendizaje.
- `XpManager.kt`: XP, niveles, combos, rachas y logros.
- El APK se compila automáticamente con GitHub Actions.
