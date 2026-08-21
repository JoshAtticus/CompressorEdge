package compressedge.joshattic.us.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.model.QueueItem
import compressedge.joshattic.us.ui.components.SelectionChip
import compressedge.joshattic.us.utils.expressiveScale
import compressedge.joshattic.us.utils.formatFileSize
import compressedge.joshattic.us.viewmodel.CompressorViewModel

@Composable
fun QueueTab(state: CompressorUiState, viewModel: CompressorViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = state.queue,
            key = { it.uri.toString() }
        ) { item ->
            Box(modifier = Modifier.animateItem()) {
                QueueItemCard(item = item, state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    item: QueueItem,
    state: CompressorUiState,
    viewModel: CompressorViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val hasAnyOverrides = item.targetSizePercentageOverride != null ||
            item.targetResolutionHeightOverride != null ||
            item.targetFpsOverride != null ||
            item.audioBitrateOverride != null ||
            item.removeAudioOverride != null ||
            item.audioVolumeOverride != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (hasAnyOverrides) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.originalName ?: "Video",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatFileSize(item.originalSize)} • ${item.originalWidth}x${item.originalHeight} • ${item.originalFps.toInt()}fps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        expanded = !expanded
                    }
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.Edit,
                        contentDescription = "Edit Overrides",
                        tint = if (hasAnyOverrides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.removeQueueItem(item.uri)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "File Settings & Overrides",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (hasAnyOverrides) {
                                TextButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateQueueItem(item.uri) {
                                            it.copy(
                                                targetSizePercentageOverride = null,
                                                targetResolutionHeightOverride = null,
                                                targetFpsOverride = null,
                                                audioBitrateOverride = null,
                                                removeAudioOverride = null,
                                                audioVolumeOverride = null
                                            )
                                        }
                                    }
                                ) {
                                    Text("Reset All", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        // 1. Target Size Percentage Override
                        Column {
                            val pct = item.targetSizePercentageOverride ?: state.globalTargetSizePercentage
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Target Size",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${pct.toInt()}%" + if (item.targetSizePercentageOverride == null) " (Global)" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.targetSizePercentageOverride != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Slider(
                                value = pct,
                                onValueChange = { newValue ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.updateQueueItem(item.uri) { it.copy(targetSizePercentageOverride = newValue) }
                                },
                                valueRange = 10f..100f,
                                steps = 17
                            )
                            if (item.targetSizePercentageOverride != null) {
                                TextButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateQueueItem(item.uri) { it.copy(targetSizePercentageOverride = null) }
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Reset Target Size", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // 2. Resolution Override
                        Column {
                            val shortSide = if (item.originalWidth > 0 && item.originalHeight > 0) minOf(item.originalWidth, item.originalHeight) else item.originalHeight
                            val currentRes = item.targetResolutionHeightOverride ?: 0

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.resolution),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (item.targetResolutionHeightOverride != null) {
                                    TextButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(targetResolutionHeightOverride = null) }
                                        }
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SelectionChip(
                                    selected = currentRes == 0 || currentRes == shortSide,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateQueueItem(item.uri) { it.copy(targetResolutionHeightOverride = null) }
                                    },
                                    label = stringResource(R.string.original) + " • ${shortSide}p"
                                )
                                val resList = listOf(
                                    4320 to "8K",
                                    2160 to "4K",
                                    1440 to "2K",
                                    1080 to "1080p",
                                    720 to "720p",
                                    540 to "540p",
                                    480 to "480p",
                                    360 to "360p",
                                    240 to "240p"
                                )
                                resList.filter { it.first < shortSide }.forEach { (h, label) ->
                                    SelectionChip(
                                        selected = currentRes == h,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(targetResolutionHeightOverride = h) }
                                        },
                                        label = label
                                    )
                                }
                            }
                        }

                        // 3. Framerate Override
                        Column {
                            val currentFps = item.targetFpsOverride ?: 0

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.framerate),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (item.targetFpsOverride != null) {
                                    TextButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(targetFpsOverride = null) }
                                        }
                                    ) {
                                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SelectionChip(
                                    selected = currentFps == 0,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateQueueItem(item.uri) { it.copy(targetFpsOverride = null) }
                                    },
                                    label = stringResource(R.string.original) + " (${item.originalFps.toInt()})"
                                )
                                if (item.originalFps >= 50f) {
                                    SelectionChip(
                                        selected = currentFps == 60,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(targetFpsOverride = 60) }
                                        },
                                        label = "60 FPS"
                                    )
                                }
                                SelectionChip(
                                    selected = currentFps == 30,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.updateQueueItem(item.uri) { it.copy(targetFpsOverride = 30) }
                                    },
                                    label = "30 FPS"
                                )
                            }
                        }

                        // 4. Remove Audio Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.remove_audio),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (item.removeAudioOverride != null) "Overridden for this video" else "Using global setting",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val isRemoved = item.removeAudioOverride ?: state.removeAudio
                            Switch(
                                checked = isRemoved,
                                onCheckedChange = { checked ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.updateQueueItem(item.uri) { it.copy(removeAudioOverride = checked) }
                                }
                            )
                        }

                        val isAudioRemoved = item.removeAudioOverride ?: state.removeAudio
                        if (!isAudioRemoved) {
                            // 5. Audio Bitrate Override
                            Column {
                                val currentBitrate = item.audioBitrateOverride ?: 0

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.audio_bitrate),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (item.audioBitrateOverride != null) {
                                        TextButton(
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                viewModel.updateQueueItem(item.uri) { it.copy(audioBitrateOverride = null) }
                                            }
                                        ) {
                                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SelectionChip(
                                        selected = currentBitrate == 0,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(audioBitrateOverride = null) }
                                        },
                                        label = stringResource(R.string.original) + if (item.originalAudioBitrate > 0) " • ${item.originalAudioBitrate / 1000}k" else ""
                                    )
                                    val rates = listOf(320000, 256000, 192000, 160000, 128000, 96000, 64000)
                                    rates.forEach { rate ->
                                        if (item.originalAudioBitrate == 0 || rate <= item.originalAudioBitrate) {
                                            SelectionChip(
                                                selected = currentBitrate == rate,
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    viewModel.updateQueueItem(item.uri) { it.copy(audioBitrateOverride = rate) }
                                                },
                                                label = "${rate / 1000}k"
                                            )
                                        }
                                    }
                                }
                            }

                            // 6. Audio Volume Override
                            Column {
                                val currentVol = item.audioVolumeOverride ?: state.audioVolume
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.volume),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${(currentVol * 100).toInt()}%" + if (item.audioVolumeOverride == null) " (Global)" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.audioVolumeOverride != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Slider(
                                    value = currentVol,
                                    onValueChange = { newValue ->
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.updateQueueItem(item.uri) { it.copy(audioVolumeOverride = newValue) }
                                    },
                                    valueRange = 0f..2f,
                                    steps = 19
                                )
                                if (item.audioVolumeOverride != null) {
                                    TextButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.updateQueueItem(item.uri) { it.copy(audioVolumeOverride = null) }
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Reset Volume", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
