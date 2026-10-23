package app.nekoshelf.launcher.ui

import android.content.Context
import coil3.ImageLoader
import coil3.request.crossfade
import coil3.svg.SvgDecoder

/**
 * The app's one Coil image loader: SVG support for the catgirl decor, and no crossfade
 * (no animations on e-ink). Requests should set a target size so big images are
 * decoded downsampled.
 */
object EinkImages {
    @Volatile private var instance: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .crossfade(false)
                .build()
                .also { instance = it }
        }
}
