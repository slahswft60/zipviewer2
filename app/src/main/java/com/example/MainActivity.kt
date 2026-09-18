package com.example

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkColors = darkColorScheme(
                primary = Color(0xFF00B4D8),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF003566),
                surface = Color(0xFF101B35),
                onSurface = Color.White,
                surfaceVariant = Color(0xFF1B284A),
                onSurfaceVariant = Color(0xFF94A3B8),
                background = Color(0xFF070D1E),
                onBackground = Color.White
            )

            MaterialTheme(colorScheme = darkColors) {
                // Support RTL layout for Arabic interface
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ZipExplorerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipExplorerScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var urlInput by remember { mutableStateOf("") }
    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var archiveTitle by remember { mutableStateOf("") }
    var archiveTotalSize by remember { mutableStateOf(0L) }
    var bytesUsedForAnalysis by remember { mutableStateOf(0L) }
    var isRangeSupported by remember { mutableStateOf(false) }
    var archiveType by remember { mutableStateOf("ZIP") }

    var entries by remember { mutableStateOf<List<ZipEntryItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var isAnalyzingRemote by remember { mutableStateOf(false) }
    var showResultsView by remember { mutableStateOf(false) }

    var pendingEntryToExtract by remember { mutableStateOf<ZipEntryItem?>(null) }

    // SAF Document Creator launcher for extracting a single file
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri: Uri? ->
        val target = pendingEntryToExtract
        if (destUri != null && target != null) {
            isLoading = true
            statusText = "جاري استخراج ${target.simpleName} دون تحميل الأرشيف…"

            coroutineScope.launch {
                var success = false
                var errorMsg: String? = null

                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(destUri)?.use { os: OutputStream ->
                            if (target.isRemote) {
                                RemoteArchiveInspector.downloadSingleEntry(target, os) { written, total ->
                                    // Progress feedback
                                }
                                success = true
                            } else {
                                val localUri = selectedZipUri
                                if (localUri != null) {
                                    context.contentResolver.openInputStream(localUri)?.use { isStream ->
                                        BufferedInputStream(isStream).use { bis ->
                                            ZipInputStream(bis).use { zis ->
                                                var entry: ZipEntry? = zis.nextEntry
                                                while (entry != null) {
                                                    if (entry.name == target.path) {
                                                        val buffer = ByteArray(8192)
                                                        var read: Int
                                                        while (zis.read(buffer).also { read = it } != -1) {
                                                            os.write(buffer, 0, read)
                                                        }
                                                        os.flush()
                                                        zis.closeEntry()
                                                        success = true
                                                        break
                                                    }
                                                    zis.closeEntry()
                                                    entry = zis.nextEntry
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "خطأ أثناء حفظ الملف"
                    }
                }

                isLoading = false
                if (errorMsg != null) {
                    statusText = "فشل الاستخراج: $errorMsg"
                    snackbarHostState.showSnackbar("تعذر استخراج الملف: $errorMsg")
                } else if (success) {
                    statusText = "تم استخراج وحفظ ${target.simpleName} بنجاح!"
                    snackbarHostState.showSnackbar("تم استخراج ${target.simpleName} بنجاح دون تنزيل الأرشيف بالكامل!")
                } else {
                    statusText = "لم يتم العثور على الملف داخل الأرشيف."
                    snackbarHostState.showSnackbar("تعذر العثور على الملف.")
                }
                pendingEntryToExtract = null
            }
        }
    }

    // Function to run Remote Analysis
    fun analyzeRemoteArchive(urlToAnalyze: String) {
        val targetUrl = urlToAnalyze.trim()
        if (targetUrl.isEmpty()) {
            Toast.makeText(context, "يرجى إدخال رابط صالح أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        isLoading = true
        isAnalyzingRemote = true
        statusText = "جاري الاتصال بالخادم وفحص هيكل الأرشيف عن بُعد…"
        selectedZipUri = null

        coroutineScope.launch {
            try {
                val result = RemoteArchiveInspector.inspectRemoteArchive(targetUrl)
                entries = result.entries
                archiveTotalSize = result.totalArchiveSize
                bytesUsedForAnalysis = result.bytesDownloadedForAnalysis
                isRangeSupported = result.isRangeSupported
                archiveType = result.archiveType
                archiveTitle = targetUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "remote_archive.zip" }
                showResultsView = true
                statusText = "تم تحليل الأرشيف بنجاح! تم العثور على ${result.entries.size} ملف (استهلاك شبكة: ${formatBytes(result.bytesDownloadedForAnalysis)} فقط)"
                snackbarHostState.showSnackbar("تم تحليل محتويات الأرشيف بنجاح دون تنزيله!")
            } catch (e: Exception) {
                statusText = "خطأ في التحليل: ${e.message}"
                snackbarHostState.showSnackbar("تعذر تحليل الرابط: ${e.message}")
            } finally {
                isLoading = false
                isAnalyzingRemote = false
            }
        }
    }

    // SAF Document Picker launcher for selecting a local ZIP archive
    val pickZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedZipUri = uri
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            var fileName = "local_archive.zip"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex) ?: "local_archive.zip"
                        }
                    }
                }
            } catch (_: Exception) {}

            archiveTitle = fileName
            isLoading = true
            statusText = "جاري قراءة الملف المضغوط من الهاتف…"
            searchQuery = ""

            coroutineScope.launch {
                val parsedItems = mutableListOf<ZipEntryItem>()
                var parseError: String? = null

                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { isStream: InputStream ->
                            BufferedInputStream(isStream).use { bis ->
                                ZipInputStream(bis).use { zis ->
                                    var entry: ZipEntry? = zis.nextEntry
                                    while (entry != null) {
                                        val path = entry.name
                                        val isDir = entry.isDirectory || path.endsWith("/")
                                        parsedItems.add(
                                            ZipEntryItem(
                                                path,
                                                if (entry.size >= 0) entry.size else 0L,
                                                if (entry.compressedSize >= 0) entry.compressedSize else 0L,
                                                isDir
                                            )
                                        )
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        parseError = e.message
                    }
                }

                isLoading = false
                if (parseError != null) {
                    statusText = "خطأ في قراءة الملف: $parseError"
                    snackbarHostState.showSnackbar("تعذر قراءة الملف: $parseError")
                } else {
                    entries = parsedItems
                    showResultsView = true
                    statusText = "تم العثور على ${parsedItems.size} ملف في $fileName"
                }
            }
        }
    }

    // Filtered entries based on search query
    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            val query = searchQuery.trim().lowercase(Locale.ROOT)
            entries.filter {
                it.simpleName.lowercase(Locale.ROOT).contains(query) ||
                        it.path.lowercase(Locale.ROOT).contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            // Header with cyan/blue gradient as shown in the screenshot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00B4D8), Color(0xFF0077B6))
                        )
                    )
                    .padding(top = 40.dp, bottom = 14.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (showResultsView) {
                        IconButton(
                            onClick = { showResultsView = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(36.dp))
                    }

                    Text(
                        text = stringResource(R.string.zip_analyzer_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )

                    IconButton(
                        onClick = {
                            if (showResultsView) {
                                showResultsView = false
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "التالي",
                            tint = Color.White
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF070D1E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF070D1E))
        ) {
            if (!showResultsView) {
                // ==================== SCREEN 1: ANALYZER INPUT FORM (Matches Screenshot) ====================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        // Info Card with description and example
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF101B35)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = "📦 ",
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.analyzer_description),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFE2E8F0),
                                        lineHeight = 22.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                Text(
                                    text = stringResource(R.string.example_link_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Medium
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "https://example.com/file.zip",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF38BDF8),
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .clickable {
                                            urlInput = "https://example.com/file.zip"
                                        }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }

                    item {
                        // Supported Formats Label in vibrant cyan
                        Text(
                            text = stringResource(R.string.supported_formats),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF00D4F0),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    item {
                        // URL Input field with link icon
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.url_input_hint),
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = "رابط",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { analyzeRemoteArchive(urlInput) }
                            ),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF0E172C),
                                unfocusedContainerColor = Color(0xFF0E172C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00B4D8),
                                unfocusedBorderColor = Color(0xFF1E293B)
                            )
                        )
                    }

                    item {
                        // Main Action Button: "بدء التحليل"
                        Button(
                            onClick = { analyzeRemoteArchive(urlInput) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("start_analysis_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00569E),
                                contentColor = Color.White
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "جاري التحليل…",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = "بدء التحليل",
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.start_analysis),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Secondary Action: Pick local ZIP
                        OutlinedButton(
                            onClick = {
                                pickZipLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/x-zip",
                                        "application/x-tar",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("pick_local_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF94A3B8)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF334155)))
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "ملف محلي",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.pick_local_file),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (statusText.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF132244)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFCBD5E1)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ==================== SCREEN 2: ARCHIVE CONTENTS & SELECTIVE EXTRACTION ====================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Archive Info Summary Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101B35))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderZip,
                                        contentDescription = null,
                                        tint = Color(0xFF00B4D8),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = archiveTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF003566)
                                ) {
                                    Text(
                                        text = archiveType,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "الملفات: ${entries.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                                if (archiveTotalSize > 0) {
                                    Text(
                                        text = "الحجم الكلي: ${formatBytes(archiveTotalSize)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }

                            if (bytesUsedForAnalysis > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "تم التحليل باستهلاك ${formatBytes(bytesUsedForAnalysis)} فقط دون تحميل الأرشيف!",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF34D399),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search Filter Box
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_filter_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF64748B)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "بحث",
                                tint = Color(0xFF38BDF8)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "مسح",
                                        tint = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF101B35),
                            unfocusedContainerColor = Color(0xFF101B35),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00B4D8),
                            unfocusedBorderColor = Color(0xFF1E293B)
                        )
                    )

                    if (isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF00B4D8)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // File List with custom category icons
                    ZipFilesLazyColumn(
                        entries = filteredEntries,
                        onEntryClick = { entry ->
                            if (entry.isDirectory) {
                                Toast.makeText(
                                    context,
                                    "هذا مجلد، اختر ملفاً داخله للتنزيل.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                pendingEntryToExtract = entry
                                val suggestedName = entry.simpleName
                                saveDocumentLauncher.launch(suggestedName)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
