package app.meebook.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meebook.launcher.R
import app.meebook.launcher.domain.Book
import app.meebook.launcher.domain.Manga
import app.meebook.launcher.ui.EinkImages
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import kotlin.math.roundToInt

private val CoverShape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
private val CARD_HEIGHT = 84.dp
private val COVER_W = 47.dp
private val COVER_H = 68.dp

/**
 * «Сейчас читаю»: the most recently read KOReader book and «Манга» title as two outlined cards
 * (cover, title, position, progress bar). Tapping a card opens it. Empty states:
 * «KOReader ещё не открывал книги» and «Манга не установлена».
 */
@Composable
fun NowReading(
    book: Book?,
    bookCover: File?,
    manga: Manga?,
    mangaCover: File?,
    mangaAvailable: Boolean,
    loaded: Boolean,
    onBook: (Book) -> Unit,
    onManga: (Manga) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        Text(
            stringResource(R.string.now_reading).uppercase(),
            style = serif(11f, bold = true, color = Grey).copy(letterSpacing = 1.2.sp),
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val cardMod = Modifier.weight(1f).height(CARD_HEIGHT)
            if (!loaded) {
                // Before the first load: plain frames, no premature empty-state text.
                EmptyCard("", cardMod)
                EmptyCard("", cardMod)
                return@Row
            }
            if (book != null) {
                val pct = ((book.percent ?: 0.0) * 100).roundToInt().coerceIn(0, 100)
                ReadingCard(
                    title = book.title,
                    cover = bookCover,
                    line = if (book.status == "complete") stringResource(R.string.book_done) else stringResource(R.string.book_progress, pct),
                    progress = (book.percent ?: 0.0).toFloat(),
                    onClick = { onBook(book) },
                    modifier = cardMod,
                )
            } else {
                EmptyCard(stringResource(R.string.empty_koreader), cardMod)
            }
            if (manga != null) {
                val caughtUp = manga.chaptersTotal > 0 && manga.chaptersRead >= manga.chaptersTotal
                ReadingCard(
                    title = manga.title,
                    cover = mangaCover,
                    line = if (caughtUp) stringResource(R.string.caught_up)
                    else stringResource(R.string.manga_progress, manga.chaptersRead, manga.chaptersTotal),
                    progress = if (manga.chaptersTotal > 0) manga.chaptersRead.toFloat() / manga.chaptersTotal else 0f,
                    onClick = { onManga(manga) },
                    modifier = cardMod,
                )
            } else {
                EmptyCard(
                    stringResource(if (mangaAvailable) R.string.empty_manga_history else R.string.empty_manga),
                    cardMod,
                )
            }
        }
    }
}

@Composable
private fun ReadingCard(title: String, cover: File?, line: String, progress: Float, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.outlinedCard().clickable(onClick = onClick).padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(cover, title, COVER_W, COVER_H)
        Column(Modifier.padding(start = 9.dp).fillMaxHeight()) {
            Text(title, style = serif(14f, bold = true), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(line, style = serif(12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressBar(progress, Modifier.padding(top = 4.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun EmptyCard(text: String, modifier: Modifier) {
    Box(modifier.outlinedCard().padding(10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = serif(13f, color = Grey), textAlign = TextAlign.Center, maxLines = 3)
    }
}

/**
 * A cover image cropped into [width]×[height] with a thin black edge; without an image file, a
 * text-only cover with the [title] (also shown when the file fails to decode).
 */
@Composable
internal fun CoverImage(file: File?, title: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    Box(modifier.size(width, height).clip(CoverShape).background(Color.White)) {
        TextCover(title)
        if (file != null) {
            val wPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
            val hPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
            val request = remember(file, wPx, hPx) {
                ImageRequest.Builder(context).data(file).size(wPx, hPx).build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                imageLoader = EinkImages.loader(context),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().border(1.dp, Color.Black, CoverShape))
    }
}

@Composable
private fun TextCover(title: String) {
    Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
        Text(
            title,
            style = serif(9.5f, bold = true),
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
