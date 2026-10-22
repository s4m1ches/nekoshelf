package app.meebook.launcher.data.koreader

import app.meebook.launcher.data.lua.LuaParseException
import app.meebook.launcher.data.lua.LuaTable
import java.io.File
import java.io.IOException

data class BookState(
    val percent: Double?,
    val status: String?,
    val title: String?,
    val authors: String?,
    val series: String?,
    val seriesIndex: Double?,
    val language: String?,
)

object Sidecar {
    fun read(bookFile: File): BookState? {
        val name = bookFile.name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1)
        val sidecar = File(bookFile.parentFile, "${name.substring(0, dot)}.sdr/metadata.$ext.lua")
        val root: Map<*, *> = try {
            if (!sidecar.isFile) return null
            LuaTable.parse(sidecar.readText(Charsets.UTF_8)) as? Map<*, *> ?: return null
        } catch (e: LuaParseException) {
            return null
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }

        val props = root["doc_props"] as? Map<*, *>
        val stats = root["stats"] as? Map<*, *>
        fun str(key: String): String? =
            (props?.get(key) as? String)?.takeIf { it.isNotEmpty() }
                ?: (stats?.get(key) as? String)?.takeIf { it.isNotEmpty() }

        val series = str("series")?.takeIf { it != "N/A" }
        val idx = when (val v = props?.get("series_index") ?: stats?.get("series_index")) {
            is Long -> v.toDouble()
            is Double -> v
            else -> null
        }
        return BookState(
            percent = when (val p = root["percent_finished"]) {
                is Double -> p
                is Long -> p.toDouble()
                else -> null
            },
            status = ((root["summary"] as? Map<*, *>)?.get("status") as? String)?.takeIf { it.isNotEmpty() },
            title = str("title"),
            authors = str("authors"),
            series = series,
            seriesIndex = if (series != null) idx else null,
            language = str("language"),
        )
    }
}
