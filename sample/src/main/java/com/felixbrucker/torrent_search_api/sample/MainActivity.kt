package com.felixbrucker.torrent_search_api.sample

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.felixbrucker.torrent_search_api.Category
import com.felixbrucker.torrent_search_api.CloudflareWebViewResolver
import com.felixbrucker.torrent_search_api.ExtToProvider
import com.felixbrucker.torrent_search_api.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                TorrentSearchScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TorrentSearchScreen() {
        var query by remember { mutableStateOf("regular show 1080p") }
        var isLoading by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        var showBottomSheet by remember { mutableStateOf(false) }
        var webViewForBottomSheet by remember { mutableStateOf<WebView?>(null) }

        val scope = rememberCoroutineScope()

        val resolver = remember {
            CloudflareWebViewResolver(
                context = this,
                onPromoteToBottomSheet = { webView ->
                    runOnUiThread {
                        webViewForBottomSheet = webView
                        showBottomSheet = true
                    }
                },
                onDismissBottomSheet = {
                    runOnUiThread {
                        showBottomSheet = false
                        webViewForBottomSheet = null
                    }
                }
            )
        }

        val provider = remember { ExtToProvider(resolver) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Torrent Search API - ext.to") }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search Term") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (query.isBlank()) return@Button
                            isLoading = true
                            errorMessage = null
                            results = emptyList()

                            scope.launch(Dispatchers.IO) {
                                val searchResult = provider.search(query, category = Category.TV_SHOWS)
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    searchResult.fold(
                                        onSuccess = {
                                            results = it.results
                                        },
                                        onFailure = {
                                            errorMessage = it.message ?: "An unexpected error occurred"
                                        }
                                    )
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Text("Search")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results) { item ->
                        SearchResultCard(item)
                    }
                }
            }

            if (showBottomSheet && webViewForBottomSheet != null) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = {
                        showBottomSheet = false
                        webViewForBottomSheet = null
                    },
                    sheetState = sheetState
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.7f)
                    ) {
                        AndroidView(
                            factory = { context ->
                                val wv = webViewForBottomSheet!!
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchResultCard(item: SearchResultItem) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                val sizeMb = item.sizeInBytes / (1024 * 1024)
                Text(
                    text = "Size: ${sizeMb} MB | Seeders: ${item.seeder} | Leechers: ${item.leecher}",
                    style = MaterialTheme.typography.bodyMedium
                )

                item.uploadedBy?.let { uploader ->
                    Text(
                        text = "Uploaded by: $uploader",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
