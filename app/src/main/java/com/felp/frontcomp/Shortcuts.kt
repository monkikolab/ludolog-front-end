package com.felp.frontcomp

import androidx.compose.ui.input.key.Key

/**
 * Los atajos del mando, y cuales de ellos se pueden cambiar.
 *
 * Hay dos clases y conviene no confundirlas. Unos son de UN boton —Select abre ajustes, Start
 * abre el menu de lo elegido— y esos se cambian. Otros son ACORDES: los dos hombros a la vez
 * abren el cajon de apps, los dos gatillos a la vez abren el cuaderno. Esos se ensenan pero no
 * se tocan, y no por pereza: un acorde tiene que ser una pareja que no se pulse por accidente
 * mientras se juega, y dejar elegir cualquier pareja es dejar elegir una que se dispare sola.
 *
 * Cada atajo acepta VARIOS codigos, no uno.
 *
 * No hay dos mandos que reporten los botones igual: el Select de un aparato llega como
 * ButtonSelect y el de otro como Menu, y con Start pasa lo mismo. Por eso un atajo es un
 * conjunto y no una tecla; y por eso, al elegir uno a mano, se guarda el que se eligio Y sus
 * sinonimos conocidos, o el atajo funcionaria en este mando y no en el siguiente.
 */
object Shortcuts {

    /** Un boton de mando, con el nombre con el que lo conoce quien lo aprieta. */
    enum class Button(val label: String, val keys: Set<Key>) {
        SELECT("Select", setOf(Key.ButtonSelect, Key.Menu)),
        START("Start", setOf(Key.ButtonStart)),
        X("X", setOf(Key.ButtonX)),
        Y("Y", setOf(Key.ButtonY)),
        L1("L1", setOf(Key.ButtonL1, Key.PageUp)),
        R1("R1", setOf(Key.ButtonR1, Key.PageDown)),
        L2("L2", setOf(Key.ButtonL2)),
        R2("R2", setOf(Key.ButtonR2)),
        L3("L3", setOf(Key.ButtonThumbLeft)),
        R3("R3", setOf(Key.ButtonThumbRight)),
    }

    /**
     * Lo que se puede atar a un boton.
     *
     * A y B no estan en la lista de candidatos y es a proposito: son entrar y volver en todas
     * las listas de la aplicacion. Atarles un atajo no seria configurar nada, seria romper la
     * navegacion desde dentro de la propia pantalla de configurarla.
     */
    enum class Action(
        val label: String,
        val about: String,
        val fallback: Button,
        /** Los que se ensenan pero no se cambian llevan aqui como se pulsan. */
        val chord: String? = null,
    ) {
        SETTINGS(
            "Settings",
            "Opens and closes this window from anywhere. Reaching the gear in the corner with " +
                "the D-pad would be a long trip.",
            Button.SELECT,
        ),
        QUICK_MENU(
            "Context menu",
            "The menu of whatever is selected: rename it, hide it, pick its emulator. It is " +
                "the pad's equivalent of holding a finger down on it.",
            Button.START,
        ),
        SEARCH(
            "Search",
            "Find a game in every console by its name, and go to it. The magnifier at the top " +
                "does the same with a finger.",
            Button.Y,
        ),
        APPS(
            "App drawer",
            "Every app on the device, with the emulators first.",
            Button.L1,
            chord = "L1 + R1",
        ),
        RECKONING(
            "Companion",
            "The record of what you played and what it cost. Also at the end of the console " +
                "list, if it is switched on.",
            Button.L2,
            chord = "L2 + R2",
        ),
        JUMP(
            "Next console",
            "Inside a game list, jumps to the previous or the next console without going back.",
            Button.L1,
            chord = "L1  /  R1",
        ),
    }

    /** Los que se pueden cambiar son los que no son acorde. */
    val editable: List<Action> = Action.entries.filter { it.chord == null }

    /**
     * Los botones que se pueden elegir para un atajo: todos menos hombros y gatillos.
     *
     * Esos cuatro son de los acordes y de saltar de consola, y la raiz los atiende ANTES que
     * los atajos. Atado a uno, el atajo no saltaba nunca, y si era el de los ajustes, Select
     * dejaba de abrirlos y con el mando no habia forma de volver a entrar para deshacerlo.
     */
    val pickable: List<Button> = Button.entries.filter {
        it != Button.L1 && it != Button.R1 && it != Button.L2 && it != Button.R2
    }

    private fun key(action: Action) = "key.${action.name.lowercase()}"

    fun button(prefs: Prefs, action: Action): Button {
        val stored = prefs.shortcut(key(action)) ?: return action.fallback
        // Uno guardado de antes en un hombro o un gatillo no funcionaba: vuelve al de fabrica.
        return pickable.firstOrNull { it.name == stored } ?: action.fallback
    }

    fun set(prefs: Prefs, action: Action, button: Button) = prefs.setShortcut(key(action), button.name)

    /**
     * Los codigos que valen para un atajo.
     *
     * Devuelve el conjunto del boton elegido, con sus sinonimos: asi «Select» sigue siendo
     * Select en un mando que lo manda como Menu.
     */
    fun keys(prefs: Prefs, action: Action): Set<Key> = button(prefs, action).keys
}
