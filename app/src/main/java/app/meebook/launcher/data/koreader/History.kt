package app.meebook.launcher.data.koreader

import app.meebook.launcher.data.lua.LuaParseException
import app.meebook.launcher.data.lua.LuaTable
import java.io.File
import java.io.IOException

data class RecentOpen(val file: File, val time: Long)

object History {
    fun read(root: File): List<RecentOpen> {
        val table: Map<*, *> = try {
            val f = File(root, "history.lua")
            if (!f.isFile) return emptyList()
            LuaTable.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: return emptyList()
        } catch (e: LuaParseException) {
            return emptyList()
        } catch (e: IOException) {
            return emptyList()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return table.values.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            val path = m["file"] as? String ?: return@mapNotNull null
            val time = when (val t = m["time"]) {
                is Long -> t
                is Double -> t.toLong()
                else -> 0L
            }
            val file = File(path)
            if (file.exists()) RecentOpen(file, time) else null
        }.sortedByDescending { it.time }
    }
}
