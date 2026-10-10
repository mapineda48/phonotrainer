"""Vocabulary of the heuristic non-English scan of check_repo.py (X-AC2).

Function words that English text never uses, per language; Spanish also has a weak list of short words that English
code and prose rarely use. This file is exempt from the language scan because it is the scan's own word list.
"""

SPANISH_STRONG = frozenset("""
que qué los las una unos unas como cómo pero esta este estos estas esto porque cuando cuándo muy sobre también hasta
donde dónde desde toda todos todas entre más está están estar puede pueden cada tiene tienen hacer aquí ahora mismo
misma así nuevo nueva ejemplo siguiente después antes mientras según durante ella ellos usted eso esa ese archivo
archivos carpeta además entonces luego otro otra otros otras debe deben algo nada nunca siempre usuario mensaje
pantalla datos botón descarga descargar guardar borrar eliminar crear agregar añadir cambiar prueba pruebas cual
tarea tareas nuevas nuevos editar cancelar aceptar volver atrás anterior buscar ajustes configuración cargando mis
tus notas pasos enlaces enlace opcional obligatorio título descripción lista pendiente pendientes hecha hecho marcar
deshacer rehacer todavía aún bien buen trabajo compartir enviar recibir subir solicitud servidor conexión conectar
emparejar código ordenador computadora teléfono inicio biblioteca práctica aprender palabra palabras frase frases
sonido sonidos ejemplos lección mirar escuchar hablar repetir menos hoy ayer mañana sí ningún ninguna alguna algún
""".split())
SPANISH_WEAK = frozenset("de la el en y o se un es lo al su sus mi te le les nos con sin del para hay ya".split())
PORTUGUESE_STRONG = frozenset("""
não você isso então muito pelo pela nós eles elas ainda agora aqui depois quando onde sempre arquivo usuário tela
dados botão fazer mais já também uma porque
""".split())
FRENCH_STRONG = frozenset("""
avec pour dans sont cette nous vous leur très être aussi comme ainsi donc alors mais fichier utilisateur écran données
bouton faire avoir sans chez quand elle ils
""".split())
GERMAN_STRONG = frozenset("""
und nicht sich auch wird werden oder wenn kann eine einen einem einer ich wir nach über zum zur beim noch schon sehr
datei benutzer bildschirm daten
""".split())
ACCENTED = set("áéíóúñãõâêôçàèìòùäöüß")
LOANWORDS = frozenset("""
café cafés cliché clichés résumé résumés fiancé fiancée naïve déjà vu façade façades protégé entrée entrées rôle
über à crème brûlée soufflé touché née jalapeño piñata señor señora attaché exposé sauté purée outré décor
""".split())
