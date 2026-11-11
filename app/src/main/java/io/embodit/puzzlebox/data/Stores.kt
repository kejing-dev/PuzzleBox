package io.embodit.puzzlebox.data

import android.content.Context
import android.util.Log
import io.embodit.puzzlebox.domain.GameStore
import io.embodit.puzzlebox.domain.Language
import io.embodit.puzzlebox.domain.Record
import io.embodit.puzzlebox.domain.RecordStore
import io.embodit.puzzlebox.domain.Records
import io.embodit.puzzlebox.domain.SaveCodec
import io.embodit.puzzlebox.domain.SavedGame
import io.embodit.puzzlebox.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Atomic text file: temp file, read-back check, rename; the previous good copy stays as .bak (Chess Stores.kt pattern). */
private fun atomicWrite(dir: File, name: String, text: String, check: (String) -> Boolean) {
    val file = File(dir, name)
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    check(check(tmp.readText())) { "read-back mismatch" }
    if (file.exists()) file.copyTo(File(dir, "$name.bak"), overwrite = true)
    if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
}

/** files/game.txt, written after every action (design dataModel.save). */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir

    override fun load(): SavedGame? =
        listOf("game.txt", "game.txt.bak").firstNotNullOfOrNull { n -> runCatching { File(dir, n).takeIf { it.exists() }?.let { SaveCodec.decode(it.readText()) } }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching { atomicWrite(dir, "game.txt", text) { SaveCodec.decode(it) == game } }.onFailure { Log.e("PuzzleBox", "SAVE failed", it) }
    }

    override fun clear() { File(dir, "game.txt").delete(); File(dir, "game.txt.bak").delete() }
}

/** files/records.txt: `id=done,bestMs,bestMoves` lines + `soma=<key>;<key>...` (design dataModel.records). */
class FileRecordStore(context: Context) : RecordStore {
    private val dir = context.filesDir

    override fun load(): Records = runCatching {
        val f = File(dir, "records.txt").takeIf { it.exists() } ?: File(dir, "records.txt.bak").takeIf { it.exists() } ?: return Records()
        decode(f.readText())
    }.getOrDefault(Records())

    override fun save(r: Records) {
        runCatching { atomicWrite(dir, "records.txt", encode(r)) { decode(it) == r } }.onFailure { Log.e("PuzzleBox", "RECORDS save failed", it) }
    }

    companion object {
        fun encode(r: Records): String = (r.boxes.entries.sortedBy { it.key }.map { (id, v) -> "$id=${if (v.done) 1 else 0},${v.bestMs ?: ""},${v.bestMoves ?: ""}" } +
            "soma=" + r.somaWays.sorted().joinToString(";")).joinToString("\n")

        fun decode(text: String): Records {
            val boxes = HashMap<String, Record>()
            var soma = emptySet<String>()
            for (line in text.lines()) {
                if ("=" !in line) continue
                val k = line.substringBefore("="); val v = line.substringAfter("=")
                if (k == "soma") { soma = v.split(";").filter { it.isNotBlank() }.toSet(); continue }
                val f = v.split(",")
                boxes[k] = Record(f[0] == "1", f.getOrNull(1)?.toLongOrNull(), f.getOrNull(2)?.toIntOrNull())
            }
            return Records(boxes, soma)
        }
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("firstRunDone", s.firstRunDone)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
        )
    }.getOrElse { Settings() }
}
