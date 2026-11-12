package io.embodit.puzzlebox.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.puzzlebox.domain.Language
import io.embodit.puzzlebox.domain.Levels
import io.embodit.puzzlebox.domain.PieceType
import io.embodit.puzzlebox.domain.Turn
import io.embodit.puzzlebox.domain.Where
import io.embodit.puzzlebox.ui.Page
import io.embodit.puzzlebox.ui.Ui
import io.embodit.puzzlebox.ui.board.BoardListener
import io.embodit.puzzlebox.ui.board.BoardNames
import io.embodit.puzzlebox.ui.board.BoardTargets
import io.embodit.puzzlebox.ui.board.SceneHolder
import io.embodit.puzzlebox.ui.board.input.BoardInputLog
import io.embodit.puzzlebox.ui.board.input.InputSource
import io.embodit.puzzlebox.ui.board.input.resolveUp
import io.embodit.puzzlebox.ui.board.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.puzzlebox.DEBUG -p io.embodit.puzzlebox --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S17 · tap <holder> (pc:3 / pad:1:0 / tab:pitch / plate) · pick <i> · rot yaw|pitch|roll · back · drop "<cx> <cz>"
 *   drag "<cx> <cz>" (hold: drag "<cx> <cz> hold") · take <i> · hint on|off · help · undo · reset · confirm · no · solve [ms] · stop
 *   level <id> · place <n> (first n steps of the known solution) · hold S04|S05|S06|S09|S10|off · lang zh|en|system
 *   sound on|off · volume 0-2 · reduced on|off · page play|boxes|rules|settings · group 0-2 · exitask · clearask · welcome
 *   closepanel · reopen · s00hold on|off · testf on|off · replay "<holder> [hand-ray|eye-hand|controller]" · hover <holder>|off
 *   dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun st() = flow.state.value
        fun fresh(id: String = "M4") { flow.freeze(null); flow.stopAuto(); flow.chooseLevel(Levels.indexOf(id)); flow.confirm(); flow.again(); Ui.page = Page.PLAY }
        fun first(t: PieceType) = st().pieces.indices.firstOrNull { st().pieces[it].where == Where.TRAY && st().pieces[it].type == t } ?: 0
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00: s00hold on, force-stop and start, capture within 10 s")
                "S01" -> { graph.settings.update { it.copy(firstRunDone = false) }; flow.freeze(null); flow.start() }
                "S02" -> { fresh(); later(300) { flow.debugPlaceSolution(3) } }
                "S03" -> { fresh(); later(300) { flow.debugPlaceSolution(1); later(200) { flow.tapPiece(first(PieceType.L)) } } }
                "S04" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.L)); later(500) { flow.freeze(100); flow.turn(Turn.PITCH) } } }
                "S05" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.I4)); later(500) { flow.freeze(420); flow.tapPad(0, 1) } } }
                "S06" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.L)); later(500) { flow.turn(Turn.ROLL); later(400) { flow.freeze(450); flow.tapPad(1, 1) } } } }
                "S06B" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.I4)); later(500) { flow.freeze(450); flow.tapPad(1, 1) } } }
                "S07" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.O)); later(500) { flow.tapPad(0, 0) } } }
                "S07HOLE" -> { fresh(); later(300) { flow.tapPiece(first(PieceType.L)); later(500) { flow.turn(Turn.PITCH); later(400) { flow.tapPad(1, 1) } } } }
                "S09" -> { fresh(); later(300) { flow.freeze(700); flow.help() } }
                "S10" -> { fresh("W1"); later(300) { flow.autoSolve(200) } }
                "S10HOLD" -> { fresh("W1"); later(300) { flow.debugPlaceSolution(1); later(200) { flow.help(); later(2500) { flow.debugHoldSolved(a.substringAfter(" ", "250").toLongOrNull() ?: 250) } } } }
                "S10SPIN" -> { fresh("S5"); later(300) { flow.autoSolve(150); later(9000) { flow.debugHoldSolved(1300) } } }
                "S11" -> Ui.page = Page.BOXES
                "S12" -> Ui.page = Page.RULES
                "S13" -> Ui.page = Page.SETTINGS
                "S14" -> { fresh(); later(300) { flow.debugPlaceSolution(2); later(200) { flow.requestRestart() } } }
                "S14BOX" -> { fresh(); later(300) { flow.debugPlaceSolution(2); later(200) { Ui.page = Page.BOXES; flow.chooseLevel(0) } } }
                "S15" -> graph.window.closeControlOnly()
                "S16" -> Log.i(TAG, "S16: place a block, force-stop, relaunch")
                "S17" -> { fresh(); later(300) { flow.debugPlaceSolution(2); later(200) { flow.tapPiece(st().pieces.indices.first { st().pieces[it].type == PieceType.L && st().pieces[it].where == Where.BOX && st().grid().canTakeOut(it) }) } } }
                "S17BLOCKED" -> { fresh(); later(300) { flow.debugPlaceSolution(3); later(200) { flow.tapPiece(st().pieces.indices.first { st().pieces[it].type == PieceType.I4 }) } } }
                else -> Log.w(TAG, "unknown state $a")
            }
            "tap" -> routeTap(graph, a)
            "pick" -> a.toIntOrNull()?.let { routeTap(graph, BoardNames.piece(it)) }
            "rot" -> Turn.parse(a)?.let { routeTap(graph, BoardNames.tab(it)) }
            "back" -> st().selected?.let { routeTap(graph, BoardNames.piece(it)) }
            "drop" -> if (f.size >= 2) routeTap(graph, BoardNames.pad(f[0].toInt(), f[1].toInt()))
            "drag" -> if (f.size >= 2) { val i = st().selected ?: st().pieces.indices.first { st().pieces[it].where == Where.TRAY }; flow.debugDrag(i, f[0].toInt(), f[1].toInt(), release = f.getOrNull(2) != "hold") }
            "take" -> a.toIntOrNull()?.let { routeTap(graph, BoardNames.piece(it)) }
            "hint" -> flow.setHint(on)
            "help" -> flow.help()
            "undo" -> flow.undo()
            "reset" -> flow.requestRestart()
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "solve" -> flow.autoSolve(a.toLongOrNull() ?: 300L)
            "stop" -> flow.stopAuto()
            "level" -> { flow.freeze(null); flow.chooseLevel(Levels.indexOf(a)); flow.confirm(); Ui.page = Page.PLAY }
            "place" -> flow.debugPlaceSolution(a.toIntOrNull() ?: 0)
            "hold" -> if (a.equals("off", true)) flow.freeze(null) else onReceive(context, Intent(intent).putExtra("cmd", "goto").putExtra("a", a))
            "lang" -> graph.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> graph.settings.update { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> graph.settings.update { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> graph.settings.update { it.copy(reducedMotion = on) }
            "page" -> Ui.page = when (a) { "boxes" -> Page.BOXES; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "group" -> { Ui.group = a.toIntOrNull() ?: -1; Ui.page = Page.BOXES }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "clearask" -> { Ui.page = Page.SETTINGS; Ui.clearConfirm = true }
            "welcome" -> flow.welcomeDone()
            "closepanel" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.reopen()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "testf" -> SceneHolder.testF = on
            // replay <holder> [src]: the real scene entities a ray lands on for that holder (holder, marker, deepest GLB node)
            // through the template's resolveUp, then the deepest one into the same listener.onTap
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, BoardTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> square=${BoardTargets.squareOf(t, st())}")
                    last = t
                }
                if (ents.isNotEmpty()) BoardListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
