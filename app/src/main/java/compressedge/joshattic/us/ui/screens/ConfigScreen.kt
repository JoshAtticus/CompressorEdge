package compressedge.joshattic.us.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compressedge.joshattic.us.R
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.ui.components.InfoCard
import compressedge.joshattic.us.ui.components.TargetSizeWarning
import compressedge.joshattic.us.ui.tabs.AudioOptionsTab
import compressedge.joshattic.us.ui.tabs.PresetsTab
import compressedge.joshattic.us.ui.tabs.QueueTab
import compressedge.joshattic.us.ui.tabs.VideoOptionsTab
import compressedge.joshattic.us.utils.expressiveScale
import compressedge.joshattic.us.viewmodel.CompressorViewModel
import kotlinx.coroutines.launch

@Composable
fun ConfigScreen(
    state: CompressorUiState,
    viewModel: CompressorViewModel,
    context: Context,
    onStartCompression: () -> Unit = { viewModel.startCompression(context) }
) {
    val tabs = if (state.isBatchMode) {
        listOf("Queue", stringResource(R.string.tab_presets), stringResource(R.string.tab_video), stringResource(R.string.tab_audio))
    } else {
        listOf(stringResource(R.string.tab_presets), stringResource(R.string.tab_video), stringResource(R.string.tab_audio))
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val originalMb = state.originalSize / (1024f * 1024f)
    val actualEst = maxOf(state.targetSizeMb, state.minimumSizeMb)
    val isLarger = originalMb > 0 && actualEst > (originalMb + 0.01f)
    
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val useSplitLayout = maxWidth >= 600.dp 
        
        if (useSplitLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEachIndexed { index, title ->
                        val icon = when (title) {
                            "Queue" -> Icons.Outlined.FormatListBulleted
                            stringResource(R.string.tab_presets) -> Icons.Outlined.BookmarkBorder
                            stringResource(R.string.tab_video) -> Icons.Default.Movie
                            else -> Icons.Default.MusicNote
                        }
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(title, fontWeight = FontWeight.Bold) }
                        )
                        if (index < tabs.size - 1) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
                
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                
                         Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                     Column(
                         modifier = Modifier.fillMaxSize(),
                         horizontalAlignment = Alignment.CenterHorizontally
                     ) {
                     Column(
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 12.dp)
                         ) {
                             InfoCard(state)
                         }
                        
                        Box(modifier = Modifier.weight(1f)) {
                             HorizontalPager(
                                 state = pagerState,
                                 modifier = Modifier.fillMaxSize(),
                                 userScrollEnabled = false
                             ) { index ->
                                 val title = tabs[index]
                                 when (title) {
                                     "Queue" -> QueueTab(state, viewModel)
                                     stringResource(R.string.tab_presets) -> PresetsTab(state, viewModel)
                                     stringResource(R.string.tab_video) -> VideoOptionsTab(state, viewModel)
                                     stringResource(R.string.tab_audio) -> AudioOptionsTab(state, viewModel)
                                 }
                             }
                        }
                        
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                    
                     Column(
                         modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                    startY = 0f,
                                    endY = 100f
                                )
                            )
                    ) {
                          Column(
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .background(MaterialTheme.colorScheme.background.copy(alpha=0.9f))
                                 .padding(24.dp),
                              horizontalAlignment = Alignment.CenterHorizontally
                          ) {
                             if (state.targetSizeWarning) {
                                 TargetSizeWarning(state, viewModel)
                             }
                             val interactionSource = remember { MutableInteractionSource() }
                             Button(
                                onClick = { 
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStartCompression()
                                },
                                enabled = !isLarger,
                                interactionSource = interactionSource,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .expressiveScale(interactionSource),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(stringResource(R.string.start_compression), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                 modifier = Modifier.fillMaxSize(),
                 contentAlignment = Alignment.TopCenter
            ) {
                 Column(
                     modifier = Modifier.fillMaxSize(),
                     horizontalAlignment = Alignment.CenterHorizontally
                 ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(top = 24.dp, bottom = 12.dp)
                     ) {
                          InfoCard(state)
                     }
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 16.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEachIndexed { index, title ->
                                val selected = pagerState.currentPage == index
                                val tabIcon = when (title) {
                                    "Queue" -> Icons.Outlined.FormatListBulleted
                                    stringResource(R.string.tab_presets) -> Icons.Outlined.BookmarkBorder
                                    stringResource(R.string.tab_video) -> Icons.Default.Movie
                                    else -> Icons.Default.MusicNote
                                }

                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    shape = CircleShape,
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 2.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = tabIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.weight(1f)) {
                         HorizontalPager(
                             state = pagerState,
                             modifier = Modifier.fillMaxSize()
                         ) { index ->
                             val title = tabs[index]
                             when (title) {
                                 "Queue" -> QueueTab(state, viewModel)
                                 stringResource(R.string.tab_presets) -> PresetsTab(state, viewModel)
                                 stringResource(R.string.tab_video) -> VideoOptionsTab(state, viewModel)
                                 stringResource(R.string.tab_audio) -> AudioOptionsTab(state, viewModel)
                             }
                         }
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                startY = 0f,
                                endY = 100f
                            )
                        )
                ) {
                     Column(
                         modifier = Modifier
                             .fillMaxWidth()
                             .background(MaterialTheme.colorScheme.background.copy(alpha=0.9f))
                             .padding(24.dp),
                         horizontalAlignment = Alignment.CenterHorizontally
                     ) {
                         if (state.targetSizeWarning) {
                             TargetSizeWarning(state, viewModel)
                         }
                         val interactionSource = remember { MutableInteractionSource() }
                         Button(
                            onClick = { 
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartCompression()
                            },
                            enabled = !isLarger,
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .widthIn(max = 600.dp)
                                .fillMaxWidth()
                                .height(56.dp)
                                .expressiveScale(interactionSource),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(stringResource(R.string.start_compression), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
