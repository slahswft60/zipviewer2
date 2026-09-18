package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Categorizes files found inside the ZIP archive to display appropriate visual icons and badges.
 */
enum class FileCategory(
    val icon: ImageVector,
    val tintColor: Color,
    val backgroundColor: Color,
    val label: String
) {
    DIRECTORY(
        icon = Icons.Default.Folder,
        tintColor = Color(0xFFD97706),
        backgroundColor = Color(0xFFFEF3C7),
        label = "Folder"
    ),
    IMAGE(
        icon = Icons.Default.Image,
        tintColor = Color(0xFF0D9488),
        backgroundColor = Color(0xFFCCFBF1),
        label = "Image"
    ),
    AUDIO(
        icon = Icons.Default.AudioFile,
        tintColor = Color(0xFF7C3AED),
        backgroundColor = Color(0xFFEDE9FE),
        label = "Audio"
    ),
    VIDEO(
        icon = Icons.Default.VideoFile,
        tintColor = Color(0xFFE11D48),
        backgroundColor = Color(0xFFFFE4E6),
        label = "Video"
    ),
    DOCUMENT(
        icon = Icons.Default.Description,
        tintColor = Color(0xFF2563EB),
        backgroundColor = Color(0xFFDBEAFE),
        label = "Document"
    ),
    CODE(
        icon = Icons.Default.Code,
        tintColor = Color(0xFF059669),
        backgroundColor = Color(0xFFD1FAE5),
        label = "Code"
    ),
    ARCHIVE(
        icon = Icons.Default.FolderZip,
        tintColor = Color(0xFF4F46E5),
        backgroundColor = Color(0xFFE0E7FF),
        label = "Archive"
    ),
    GENERIC(
        icon = Icons.Default.InsertDriveFile,
        tintColor = Color(0xFF475569),
        backgroundColor = Color(0xFFF1F5F9),
        label = "File"
    )
}

/**
 * Resolves the [FileCategory] for a given [ZipEntryItem] based on its path and extension.
 */
fun resolveFileCategory(item: ZipEntryItem): FileCategory {
    if (item.isDirectory) {
        return FileCategory.DIRECTORY
    }
    val name = item.simpleName.lowercase(Locale.ROOT)
    return when {
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".svg") ||
                name.endsWith(".bmp") || name.endsWith(".ico") -> FileCategory.IMAGE

        name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") ||
                name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".aac") -> FileCategory.AUDIO

        name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".flv") -> FileCategory.VIDEO

        name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") ||
                name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".ppt") ||
                name.endsWith(".pptx") || name.endsWith(".txt") || name.endsWith(".md") ||
                name.endsWith(".csv") || name.endsWith(".rtf") -> FileCategory.DOCUMENT

        name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".py") ||
                name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".html") ||
                name.endsWith(".css") || name.endsWith(".json") || name.endsWith(".xml") ||
                name.endsWith(".sh") || name.endsWith(".c") || name.endsWith(".cpp") ||
                name.endsWith(".h") || name.endsWith(".sql") || name.endsWith(".yaml") ||
                name.endsWith(".yml") || name.endsWith(".gradle") || name.endsWith(".kts") -> FileCategory.CODE

        name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") ||
                name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".bz2") -> FileCategory.ARCHIVE

        else -> FileCategory.GENERIC
    }
}

/**
 * LazyColumn composable to display the list of files found within the selected ZIP archive,
 * including icons for different file types.
 *
 * @param entries The list of [ZipEntryItem] entries extracted from the archive.
 * @param onEntryClick Callback invoked when user clicks an entry.
 * @param modifier The layout modifier for the LazyColumn.
 */
@Composable
fun ZipFilesLazyColumn(
    entries: List<ZipEntryItem>,
    onEntryClick: (ZipEntryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = "Empty ZIP list",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No files match your search",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Try clearing the search filter above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("zip_files_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = entries,
                key = { _, item -> item.path }
            ) { index, item ->
                ZipEntryRowItem(
                    item = item,
                    index = index,
                    onClick = { onEntryClick(item) }
                )
            }
        }
    }
}

/**
 * Individual row item inside [ZipFilesLazyColumn], displaying file type icon, name, path,
 * size badge, and extraction call-to-action.
 */
@Composable
fun ZipEntryRowItem(
    item: ZipEntryItem,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val category = resolveFileCategory(item)

    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("zip_file_item_$index"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type Icon with themed background
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(category.backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = category.label,
                    tint = category.tintColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name and path details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = item.simpleName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Size badge & Action hint
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (item.isDirectory) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F5F9)
                ) {
                    Text(
                        text = item.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (!item.isDirectory) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("extract_button_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Extract",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Extract",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
