package com.abn3li.telemusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.repository.LocalAudioFile
import com.abn3li.telemusic.repository.stableSongId
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.GroupCardColor
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupLabelColor

/**
 * Lets the user pick which of the audio files found in a folder they picked (see
 * SettingsScreen's own ActivityResultContracts.OpenDocumentTree() launcher) to add to their
 * library. Files already imported (matched by stableSongId()) show as checked and disabled -
 * re-picking them would just upsert the same row again anyway (see
 * MusicRepository.importLocalSongs()), but disabling avoids implying a redundant re-import is a
 * distinct action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportLocalMusicSheet(
    files: List<LocalAudioFile>,
    alreadyImportedIds: Set<Long>,
    isScanning: Boolean,
    onImport: (List<LocalAudioFile>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Selection defaults to "everything not already imported" - keyed by stableSongId since
    // that's stable across recompositions of this same scan result.
    val selectedIds = remember(files) {
        mutableStateMapOf<Long, Boolean>().apply {
            files.forEach { put(it.stableSongId(), !alreadyImportedIds.contains(it.stableSongId())) }
        }
    }
    val selectableCount = remember(files, alreadyImportedIds) {
        files.count { !alreadyImportedIds.contains(it.stableSongId()) }
    }
    val selectedCount = files.count { selectedIds[it.stableSongId()] == true && !alreadyImportedIds.contains(it.stableSongId()) }
    val allSelected = selectableCount > 0 && selectedCount == selectableCount

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GroupCardColor,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Import from Folder", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (selectableCount > 0) {
                    Text(
                        if (allSelected) "Deselect All" else "Select All",
                        color = AppAccent,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                files.forEach { file ->
                                    if (!alreadyImportedIds.contains(file.stableSongId())) {
                                        selectedIds[file.stableSongId()] = !allSelected
                                    }
                                }
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                isScanning -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = AppAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp)) }

                files.isEmpty() -> Text(
                    text = "No audio files found in that folder.",
                    color = GroupLabelColor,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )

                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(files, key = { _, file -> file.stableSongId() }) { index, file ->
                        val songId = file.stableSongId()
                        val alreadyImported = alreadyImportedIds.contains(songId)
                        val checked = alreadyImported || selectedIds[songId] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyImported) {
                                    selectedIds[songId] = !(selectedIds[songId] ?: false)
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .alpha(if (alreadyImported) 0.5f else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CheckCircle(checked)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (alreadyImported) "${file.artist} · Already imported" else file.artist,
                                    color = GroupLabelColor,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (index < files.lastIndex) GroupDivider(start = 58.dp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(50.dp)
                    .alpha(if (selectedCount > 0) 1f else 0.4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppAccent)
                    .clickable(enabled = selectedCount > 0) {
                        onImport(files.filter { selectedIds[it.stableSongId()] == true && !alreadyImportedIds.contains(it.stableSongId()) })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (selectedCount > 0) "Import ($selectedCount)" else "Import",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Round pink check, like iOS edit-mode selection. */
@Composable
private fun CheckCircle(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(AppAccent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(Modifier.size(24.dp).border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape))
    }
}
