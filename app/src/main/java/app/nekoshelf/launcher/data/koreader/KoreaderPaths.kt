package app.nekoshelf.launcher.data.koreader

import app.nekoshelf.launcher.data.lua.LuaParseException
import app.nekoshelf.launcher.data.lua.LuaTable
import java.io.File
import java.io.IOException

class KoreaderPaths(private val root: File) {
    fun homeDir(): File {
        val configured = try {
            val f = File(root, "settings.reader.lua")
            if (f.isFile) {
                (LuaTable.parse(f.readText(Charsets.UTF_8)) as? Map<*, *>)?.get("home_dir") as? String
            } else null
        } catch (e: LuaParseException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        return File(configured?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME)
    }

    companion object {
        const val DEFAULT_HOME = "/storage/emulated/0/Books"
    }
}
