package compressedge.joshattic.us.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.CompressorUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CompressingScreen(
    state: CompressorUiState,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var thumbnailMap by remember { mutableStateOf<Map<android.net.Uri, ImageBitmap>>(emptyMap()) }

    val currentUri = state.currentlyCompressingUri 
        ?: if (state.isBatchMode && state.queue.isNotEmpty()) {
            state.queue.getOrNull(state.currentlyCompressingIndex)?.uri ?: state.selectedUri
        } else {
            state.selectedUri
        }
    
    // Preload/load Thumbnails
    LaunchedEffect(state.queue, state.selectedUri, currentUri) {
        val urisToLoad = if (state.isBatchMode && state.queue.isNotEmpty()) {
            state.queue.map { it.uri }
        } else {
            listOfNotNull(state.selectedUri)
        }
        
        withContext(Dispatchers.IO) {
            for (uri in urisToLoad) {
                if (!thumbnailMap.containsKey(uri)) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(context, uri)
                        val bitmap = retriever.getFrameAtTime(0)
                        if (bitmap != null) {
                            val imageBitmap = bitmap.asImageBitmap()
                            withContext(Dispatchers.Main) {
                                thumbnailMap = thumbnailMap + (uri to imageBitmap)
                            }
                        }
                        try { retriever.release() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    val formattedSize = state.formattedCurrentOutputSize

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f/9f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.DarkGray)
            ) {
                AnimatedContent(
                    targetState = currentUri,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 400)) togetherWith 
                            fadeOut(animationSpec = tween(durationMillis = 400))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "ThumbnailFadeAnimation"
                ) { uri ->
                    val targetThumbnail = uri?.let { thumbnailMap[it] }
                    if (targetThumbnail != null) {
                        Image(
                            bitmap = targetThumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray)
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        formattedSize.forEach { char ->
                            AnimatedContent(
                                targetState = char,
                                transitionSpec = {
                                    if (char.isDigit()) {
                                        slideInVertically { height -> height } + fadeIn() togetherWith
                                        slideOutVertically { height -> -height } + fadeOut()
                                    } else {
                                        fadeIn() togetherWith fadeOut()
                                    }
                                },
                                label = "CharAnimation"
                            ) { targetChar ->
                                Text(
                                    text = targetChar.toString(),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    val labelText = if (state.isBatchMode && state.queue.isNotEmpty()) {
                        val total = state.queue.size
                        val currentNum = (state.currentlyCompressingIndex + 1).coerceIn(1, total)
                        val currentName = state.queue.getOrNull(state.currentlyCompressingIndex)?.originalName ?: ""
                        if (currentName.isNotBlank()) {
                            stringResource(R.string.compressing_video_label) + " ($currentNum/$total) • $currentName"
                        } else {
                            stringResource(R.string.compressing_video_label) + " ($currentNum/$total)"
                        }
                    } else {
                        stringResource(R.string.compressing_video_label)
                    }

                    AnimatedContent(
                        targetState = labelText,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "LabelFadeAnimation"
                    ) { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    if (state.isBatchMode && state.queue.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val totalItems = state.queue.size
                            for (i in 0 until totalItems) {
                                val itemProgress = when {
                                    state.progress >= (i + 1).toFloat() / totalItems -> 1f
                                    state.progress > i.toFloat() / totalItems -> (state.progress * totalItems) - i
                                    else -> 0f
                                }
                                val animatedItemProgress by animateFloatAsState(
                                    targetValue = itemProgress,
                                    animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                                    label = "SegmentProgressAnimation_$i"
                                )
                                LinearProgressIndicator(
                                    progress = { animatedItemProgress },
                                    modifier = Modifier.weight(1f).height(6.dp),
                                    strokeCap = StrokeCap.Round
                                )
                            }
                        }
                    } else {
                        val animatedProgress by animateFloatAsState(
                            targetValue = state.progress,
                            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                            label = "ProgressAnimation"
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(
                    stringResource(R.string.cancel),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        }
    }
}
