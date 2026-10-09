# Ludolog se publica reducido con R8 (ver app/build.gradle.kts) pero sin ofuscar: los mensajes de
# error que ve el usuario y los informes de fallos (Diagnostics) llevan nombres de clases, y asi se
# entienden sin el fichero de mapeo. Lo que se pierde en tamano es poco.
-dontobfuscate
# Y con fichero y linea en las trazas de los fallos.
-keepattributes SourceFile,LineNumberTable
