package app.meebook.launcher

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.meebook.launcher.device.Eink
import app.meebook.launcher.device.HeaderPictures
import app.meebook.launcher.device.Toggles
import app.meebook.launcher.ui.home.Header
import app.meebook.launcher.ui.home.catgirlDecors
import app.meebook.launcher.ui.theme.EinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toggles = Toggles(this)
        val prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        // TEMPORARY (Task 8 device check): `-e picturesDir <path>` points the header at a test folder.
        val picturesDir = intent.getStringExtra("picturesDir")?.let(::File)
            ?: File(Environment.getExternalStorageDirectory(), "Pictures")
        setContent {
            EinkTheme {
                // TEMPORARY (Task 8): header only. Task 9 builds the real home screen —
                // picture/decor choice on onStart, storage permission, full refresh.
                val picture by produceState<File?>(null) {
                    value = withContext(Dispatchers.IO) {
                        HeaderPictures(picturesDir, prefs).next()
                    }
                }
                val decor = remember { catgirlDecors(assets).randomOrNull() ?: "" }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Header(
                        picture = picture,
                        decor = decor,
                        toggles = toggles,
                        onRefresh = { Eink.fullRefresh(this@MainActivity) },
                    )
                }
            }
        }
    }
}
