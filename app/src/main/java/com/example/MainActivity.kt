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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
            MaterialTheme {
                ZipExplorerScreen()
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

    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var selectedZipFileName by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ZipEntryItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf(context.getString(R.string.status_no_file))
    }
    var pendingEntryToExtract by remember { mutableStateOf<ZipEntryItem?>(null) }

    // SAF Document Creator launcher for extracting a single file
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri: Uri? ->
        val target = pendingEntryToExtract
        val zipUri = selectedZipUri

        if (destUri != null && target != null && zipUri != null) {
            isLoading = true
            statusText = context.getString(R.string.status_extracting, target.simpleName)

            coroutineScope.launch {
                var found = false
                var errorMsg: String? = null

                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(zipUri)?.use { isStream ->
                            BufferedInputStream(isStream).use { bis ->
                                ZipInputStream(bis).use { zis ->
                                    var entry: ZipEntry? = zis.nextEntry
                                    while (entry != null) {
                                        if (entry.name == target.path) {
                                            found = true
                                            context.contentResolver.openOutputStream(destUri)?.use { os: OutputStream ->
                                                val buffer = ByteArray(8192)
                                                var read: Int
                                                while (zis.read(buffer).also { read = it } != -1) {
                                                    os.write(buffer, 0, read)
                                                }
                                                os.flush()
                                            }
                                            zis.closeEntry()
                                            break
                                        }
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message
                    }
                }

                isLoading = false
                if (errorMsg != null) {
                    statusText = context.getString(R.string.status_error, errorMsg)
                    snackbarHostState.showSnackbar("Error saving: $errorMsg")
                } else if (!found) {
                    statusText = "File not found inside ZIP archive."
                    snackbarHostState.showSnackbar("File not found in archive.")
                } else {
                    statusText = context.getString(R.string.status_extracted_success, target.simpleName)
                    snackbarHostState.showSnackbar("Extracted ${target.simpleName} successfully!")
                }
                pendingEntryToExtract = null
            }
        }
    }

    // SAF Document Picker launcher for selecting a ZIP archive
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

            // Resolve file name
            var fileName = "archive.zip"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex) ?: "archive.zip"
                        }
                    }
                }
            } catch (_: Exception) {}

            selectedZipFileName = fileName
            isLoading = true
            statusText = context.getString(R.string.status_reading)
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
                                                entry.size,
                                                entry.compressedSize,
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
                    statusText = context.getString(R.string.status_error, parseError)
                    snackbarHostState.showSnackbar("Failed to read ZIP: $parseError")
                    entries = emptyList()
                } else {
                    entries = parsedItems
                    if (parsedItems.isEmpty()) {
                        statusText = "ZIP archive is empty (0 files found)"
                    } else {
                        statusText = context.getString(
                            R.string.status_loaded,
                            parsedItems.size
                        ) + " ($fileName)"
                    }
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
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Selective File Extractor",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD0E1FD)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF2563EB)
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFFF8FAFC)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header & Action Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = {
                            pickZipLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/x-zip",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("pick_zip_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Pick ZIP archive",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.btn_pick_zip),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .testTag("progress_indicator"),
                            color = Color(0xFF2563EB)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B),
                        modifier = Modifier.testTag("status_text")
                    )
                }
            }

            // Search Filter Box (only shown when archive has entries)
            AnimatedVisibility(visible = entries.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("search_filter_input"),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF94A3B8)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = Color(0xFF94A3B8)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color(0xFF2563EB),
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )
            }

            // File Listing Area using LazyColumn composable
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFEFF6FF),
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.FolderZip,
                                    contentDescription = "ZIP placeholder",
                                    tint = Color(0xFF2563EB),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Archive Selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap 'Select ZIP Archive' above to open and browse files inside any ZIP without unpacking all files at once.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(horizontal = 24.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                ZipFilesLazyColumn(
                    entries = filteredEntries,
                    onEntryClick = { entry ->
                        if (entry.isDirectory) {
                            Toast.makeText(
                                context,
                                "This is a folder. Select a file inside to extract.",
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
