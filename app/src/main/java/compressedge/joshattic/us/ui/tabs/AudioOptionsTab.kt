package compressedge.joshattic.us.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import compressedge.joshattic.us.utils.expressiveScale
import compressedge.joshattic.us.viewmodel.CompressorViewModel

@Composable
fun AudioOptionsTab(state: CompressorUiState, viewModel: CompressorViewModel) {
    val scrollState = rememberScrollState()
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .padding(bottom = 80.dp)
    ) {
         Text(
            stringResource(R.string.audio_options),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
         )
         
         Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { 
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.toggleRemoveAudio() 
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.remove_audio),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Switch(
                checked = state.removeAudio,
                onCheckedChange = { 
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.toggleRemoveAudio() 
                }
            )
        }

        AnimatedVisibility(visible = !state.removeAudio) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                 Text(
                    stringResource(R.string.audio_encoding),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                 )

                 Row(
                     modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                     horizontalArrangement = Arrangement.spacedBy(8.dp)
                 ) {
                     state.supportedAudioCodecs.forEach { codec ->
                         val labelText = when (codec) {
                             androidx.media3.common.MimeTypes.AUDIO_OPUS ->
                                 stringResource(R.string.audio_codec_opus)
                             else -> stringResource(R.string.audio_codec_aac)
                         }
                         compressedge.joshattic.us.ui.components.SelectionChip(
                             selected = state.audioCodec == codec,
                             onClick = {
                                 haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                 viewModel.setAudioCodec(codec)
                             },
                             label = labelText
                         )
                     }
                 }

                 Spacer(modifier = Modifier.height(24.dp))

                 Text(
                    stringResource(R.string.audio_bitrate),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                 )
                 
                 Row(
                     modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                     horizontalArrangement = Arrangement.spacedBy(8.dp)
                 ) {
                     val bitrates = listOf(320000, 256000, 192000, 160000, 128000, 96000, 64000)
                     val maxAudioBitrate = state.maxOriginalAudioBitrate
                     val effectiveSelected = if (state.audioBitrate == 0) maxAudioBitrate else state.audioBitrate
                     val origLabel = if (maxAudioBitrate > 0) {
                         stringResource(R.string.original) + " • ${maxAudioBitrate / 1000}k"
                     } else {
                         stringResource(R.string.original)
                     }
                     
                     compressedge.joshattic.us.ui.components.SelectionChip(
                         selected = state.audioBitrate == 0 || (maxAudioBitrate > 0 && effectiveSelected == maxAudioBitrate),
                         onClick = {
                             haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                             viewModel.setAudioBitrate(0)
                         },
                         label = origLabel
                     )
                     bitrates.forEach { rate ->
                         val showChip = maxAudioBitrate == 0 || rate < maxAudioBitrate
                         if (showChip) {
                             compressedge.joshattic.us.ui.components.SelectionChip(
                                 selected = state.audioBitrate == rate,
                                 onClick = {
                                     haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                     viewModel.setAudioBitrate(rate)
                                 },
                                 label = "${rate / 1000}k"
                             )
                         }
                     }
                 }

                 Spacer(modifier = Modifier.height(24.dp))

                 Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween,
                     verticalAlignment = Alignment.CenterVertically
                 ) {
                     Text(
                        stringResource(R.string.volume),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                     )
                     Text(
                         "${(state.audioVolume * 100).toInt()}%", 
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.primary
                     )
                 }
                 
                 var sliderPosition by remember(state.audioVolume) { mutableFloatStateOf(state.audioVolume) }
                 
                 Slider(
                    value = sliderPosition,
                    onValueChange = { 
                        sliderPosition = it
                        viewModel.setAudioVolume(it)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    valueRange = 0f..2f,
                    steps = 19
                )
            }
        }
    }
}
