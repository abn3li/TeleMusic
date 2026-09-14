package com.abn3li.telemusic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abn3li.telemusic.repository.LocalAudioFile
import com.abn3li.telemusic.repository.stableSongId

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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Import from folder", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
                if (selectableCount > 0) {
                    TextButton(onClick = {
                        files.forEach { file ->
                            if (!alreadyImportedIds.contains(file.stableSongId())) {
                                selectedIds[file.stableSongId()] = !allSelected
                            }
                        }
                    }) {
                        Text(if (allSelected) "Deselect all" else "Select all")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            when {
                isScanning -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                files.isEmpty() -> Text(
                    text = "No audio files found in that folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )

                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(files, key = { it.stableSongId() }) { file ->
                        val songId = file.stableSongId()
                        val alreadyImported = alreadyImportedIds.contains(songId)
                        val checked = alreadyImported || selectedIds[songId] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyImported) {
                                    selectedIds[songId] = !(selectedIds[songId] ?: false)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !alreadyImported)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = file.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (alreadyImported) "${file.artist} • Already imported" else file.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    onImport(files.filter { selectedIds[it.stableSongId()] == true && !alreadyImportedIds.contains(it.stableSongId()) })
                },
                enabled = selectedCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedCount > 0) "Import ($selectedCount)" else "Import")
            }
        }
    }
}
