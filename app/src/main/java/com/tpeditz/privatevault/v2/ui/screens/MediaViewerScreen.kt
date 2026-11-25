package com.tpeditz.privatevault.v2.ui.screens

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tpeditz.privatevault.v2.data.MediaKind
import com.tpeditz.privatevault.v2.data.MediaRecord
import com.tpeditz.privatevault.v2.security.StreamingMediaCryptoEngine
import com.tpeditz.privatevault.v2.ui.VaultViewModel
import kotlinx.coroutines.delay
import java.util.Arrays

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaId: String,
    viewModel: VaultViewModel,
    mediaList: List<MediaRecord>,
    onDeleteMedia: (String) -> Unit,
    onBack: () -> Unit
) {
    val mediaRecord = remember(mediaId, mediaList) {
        mediaList.find { it.id == mediaId }
    }

    if (mediaRecord == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Media not found", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onBack) { Text("Back", color = MaterialTheme.colorScheme.primary) }
            }
        }
        return
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Media") },
            text = { Text("Permanently delete this encrypted media file? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteMedia(mediaRecord.id)
                        onBack()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = mediaRecord.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${mediaRecord.chunkCount} verified chunk(s) • ${formatByteSize(mediaRecord.originalSizeBytes)}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            if (mediaRecord.mediaKind == MediaKind.PHOTO) {
                PhotoViewerContent(mediaRecord, viewModel)
            } else {
                VideoPlayerContent(mediaRecord, viewModel)
            }
        }
    }
}

@Composable
private fun PhotoViewerContent(
    mediaRecord: MediaRecord,
    viewModel: VaultViewModel
) {
    var photoBytes by remember(mediaRecord.id) { mutableStateOf<ByteArray?>(null) }
    var loadError by remember(mediaRecord.id) { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(mediaRecord.id) {
        try {
            photoBytes = viewModel.loadDecryptedPhotoBytes(mediaRecord)
        } catch (e: Exception) {
            loadError = "Unable to open this encrypted photo. Check that the vault is unlocked and try again."
        }
    }

    DisposableEffect(mediaRecord.id) {
        onDispose {
            photoBytes?.let { Arrays.fill(it, 0.toByte()) }
            photoBytes = null
        }
    }

    val imageBitmap = remember(photoBytes) {
        val bytes = photoBytes ?: return@remember null
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            loadError != null -> {
                Text(
                    text = loadError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            imageBitmap != null -> {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = mediaRecord.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            else -> {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun VideoPlayerContent(
    mediaRecord: MediaRecord,
    viewModel: VaultViewModel
) {
    var dataSource by remember(mediaRecord.id) {
        mutableStateOf<StreamingMediaCryptoEngine.EncryptedChunkMediaDataSource?>(null)
    }
    var mediaPlayer by remember(mediaRecord.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(mediaRecord.id) { mutableStateOf(false) }
    var isPlaying by remember(mediaRecord.id) { mutableStateOf(false) }
    var currentPosMs by remember(mediaRecord.id) { mutableIntStateOf(0) }
    var totalDurationMs by remember(mediaRecord.id) {
        mutableIntStateOf(mediaRecord.durationMs.toInt().coerceAtLeast(1))
    }
    var playerError by remember(mediaRecord.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaRecord.id) {
        try {
            dataSource = viewModel.openEncryptedVideoDataSource(mediaRecord)
        } catch (e: Exception) {
            playerError = "Unable to open this encrypted video. Check that the vault is unlocked and try again."
        }
    }

    LaunchedEffect(isPrepared, isPlaying) {
        while (isPrepared && isPlaying) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching {
                    currentPosMs = mp.currentPosition
                }
            }
            delay(250L)
        }
    }

    DisposableEffect(mediaRecord.id) {
        onDispose {
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { dataSource?.close() }
            dataSource = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                playerError != null -> {
                    Text(
                        text = playerError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                dataSource != null -> {
                    val ds = dataSource!!
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        val mp = MediaPlayer().apply {
                                            setDisplay(holder)
                                            setDataSource(ds)
                                            setOnPreparedListener {
                                                isPrepared = true
                                                val dur = it.duration
                                                if (dur > 0) totalDurationMs = dur
                                                it.start()
                                                isPlaying = true
                                            }
                                            setOnCompletionListener {
                                                isPlaying = false
                                                currentPosMs = totalDurationMs
                                            }
                                            setOnErrorListener { _, what, extra ->
                                                playerError = "Playback error ($what, $extra)"
                                                true
                                            }
                                            prepareAsync()
                                        }
                                        mediaPlayer = mp
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        runCatching {
                                            mediaPlayer?.stop()
                                            mediaPlayer?.release()
                                        }
                                        mediaPlayer = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Playback Controls
        Surface(
            color = Color.Black.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Slider(
                    value = currentPosMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                    onValueChange = { newVal ->
                        currentPosMs = newVal.toInt()
                        mediaPlayer?.seekTo(newVal.toInt())
                    },
                    valueRange = 0f..totalDurationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(currentPosMs.toLong()),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val newPos = (currentPosMs - 10000).coerceAtLeast(0)
                            currentPosMs = newPos
                            mediaPlayer?.seekTo(newPos)
                        }) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                        }

                        IconButton(onClick = {
                            val mp = mediaPlayer
                            if (mp != null && isPrepared) {
                                if (isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            }
                        }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Text(
                        text = formatDuration(totalDurationMs.toLong()),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
