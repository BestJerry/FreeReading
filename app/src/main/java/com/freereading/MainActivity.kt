package com.freereading

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freereading.audio.PlaybackHub
import com.freereading.audio.PlaybackSnapshot
import com.freereading.data.BookRecord
import com.freereading.reader.ReaderActivity
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import com.freereading.ui.MutedInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreeReadingTheme {
                LibraryScreen(model) { bookId ->
                    startActivity(ReaderActivity.intent(this, bookId))
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(model: LibraryViewModel, openBook: (String) -> Unit) {
    val books by model.books.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val playback by PlaybackHub.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importBook(uri)
    }
    val snackbar = remember { SnackbarHostState() }
    var voiceSettings by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BookRecord?>(null) }
    val context = LocalContext.current
    val importAction = { picker.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream")) }

    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (playback.bookId != null) {
                NowPlaying(playback, openBook)
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Forest, modifier = Modifier.size(27.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("FreeReading", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { voiceSettings = true }) {
                        Icon(Icons.Default.SettingsVoice, stringResource(R.string.voice_settings), tint = Forest)
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.library_eyebrow), fontSize = 10.sp, letterSpacing = 2.sp, color = MutedInk)
                    Text(stringResource(R.string.library_hero), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    Row(
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFE9EDDF)).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Headphones, null, tint = Forest, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.offline_badge), fontSize = 11.sp, color = Forest)
                    }
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.book_count, books.size), fontSize = 12.sp, color = MutedInk)
                    }
                    if (books.isNotEmpty()) {
                        Button(onClick = importAction, enabled = !busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (busy) R.string.importing_book else R.string.import_book), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (books.isEmpty()) {
                item { EmptyLibrary(busy, importAction) }
            } else {
                items(books, key = { it.id }) { book ->
                    BookRow(book, onOpen = { openBook(book.id) }, onDelete = { deleting = book })
                }
            }
        }
    }

    if (voiceSettings) {
        AlertDialog(
            onDismissRequest = { voiceSettings = false },
            icon = { Icon(Icons.Default.SettingsVoice, null, tint = Forest) },
            title = { Text(stringResource(R.string.voice_settings)) },
            text = { Text(stringResource(R.string.voice_settings_description)) },
            confirmButton = {
                TextButton(onClick = { voiceSettings = false; PlaybackHub.openVoiceSettings(context) }) {
                    Text(stringResource(R.string.open_voice_settings))
                }
            },
            dismissButton = { TextButton(onClick = { voiceSettings = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.operation_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.close)) } },
        )
    }
    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_book)) },
            text = { Text(stringResource(R.string.delete_book_message, book.title)) },
            confirmButton = { TextButton(onClick = { deleting = null; model.removeBook(book) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EmptyLibrary(busy: Boolean, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(Modifier.size(width = 210.dp, height = 138.dp)) {
            val w = size.width
            val h = size.height
            drawOval(Color(0xFFE5E8D9), topLeft = Offset(w * .08f, h * .12f), size = Size(w * .84f, h * .82f))
            val left = Path().apply {
                moveTo(w * .17f, h * .24f)
                quadraticTo(w * .35f, h * .18f, w * .5f, h * .35f)
                lineTo(w * .5f, h * .84f)
                quadraticTo(w * .35f, h * .66f, w * .17f, h * .73f)
                close()
            }
            val right = Path().apply {
                moveTo(w * .5f, h * .35f)
                quadraticTo(w * .65f, h * .18f, w * .83f, h * .24f)
                lineTo(w * .83f, h * .73f)
                quadraticTo(w * .65f, h * .66f, w * .5f, h * .84f)
                close()
            }
            drawPath(left, Color(0xFFFDFCF6))
            drawPath(right, Color(0xFFEEE9DB))
            for (i in 0..3) {
                val y = h * (.35f + i * .085f)
                drawLine(Color(0xFFADB7A0), Offset(w * .23f, y), Offset(w * .43f, y + h * .05f), strokeWidth = 2.dp.toPx())
                drawLine(Color(0xFFADB7A0), Offset(w * .57f, y + h * .05f), Offset(w * .77f, y), strokeWidth = 2.dp.toPx())
            }
        }
        Text(stringResource(R.string.empty_title), fontFamily = FontFamily.Serif, fontSize = 19.sp)
        Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyMedium, color = MutedInk, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier.padding(top = 8.dp).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (busy) R.string.importing_book else R.string.import_book))
        }
    }
}

@Composable
private fun BookRow(book: BookRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFEFEEE7), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(72.dp).aspectRatio(.69f))
            Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { stringResource(R.string.unknown_author) }, fontSize = 12.sp, color = MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Forest, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.open_book), color = Forest, fontSize = 12.sp)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), tint = MutedInk) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_book)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: BookRecord, modifier: Modifier = Modifier) {
    val cover by produceState<ImageBitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 500).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val color = remember(book.id) {
        listOf(Color(0xFF536D5A), Color(0xFF976F55), Color(0xFF66747F), Color(0xFF88715F))[book.id.hashCode().and(Int.MAX_VALUE) % 4]
    }
    Box(modifier.clip(RoundedCornerShape(topStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp, bottomStart = 3.dp)).background(color)) {
        val bitmap = cover
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("FR /", fontSize = 8.sp, letterSpacing = 1.sp, color = Color.White.copy(alpha = .65f))
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, color = Color.White)
                HorizontalDivider(color = Color.White.copy(alpha = .45f), modifier = Modifier.width(24.dp))
            }
        }
        Box(Modifier.width(4.dp).fillMaxHeight().background(Color.Black.copy(alpha = .12f)))
    }
}

@Composable
private fun NowPlaying(state: PlaybackSnapshot, openBook: (String) -> Unit) {
    val context = LocalContext.current
    Surface(color = Forest, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().clickable { state.bookId?.let(openBook) }.padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Headphones, null, tint = Color(0xFFDDE6D4), modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(state.bookTitle, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state.isPreparing) stringResource(R.string.preparing_audio) else state.chapterTitle.ifBlank { stringResource(R.string.currently_listening) },
                    color = Color.White.copy(alpha = .65f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { PlaybackHub.toggle(context) }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f))) {
                if (state.isPreparing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (state.isPlaying) R.string.pause_playback else R.string.resume_playback), tint = Color.White)
            }
        }
    }
}
