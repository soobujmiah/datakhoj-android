/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.datakhoj.app.net.AndroidHttpClient
import dev.datakhoj.app.net.DuckDuckGoProvider
import dev.datakhoj.core.ai.SemanticRanker
import dev.datakhoj.core.intent.IntentParser
import dev.datakhoj.core.intent.SmartSearch
import dev.datakhoj.core.provider.DataKind
import dev.datakhoj.core.provider.ProviderRegistry
import dev.datakhoj.core.provider.SearchResult
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchResult>>(emptyList())
    var planText by mutableStateOf<String?>(null)
    var reasoning by mutableStateOf<List<String>>(emptyList())
    var detectedKind by mutableStateOf<DataKind?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var mergedCount by mutableStateOf(0)

    private val http = AndroidHttpClient()
    private val smart = SmartSearch(IntentParser(), ProviderRegistry, llm = null)
    // No model shipped, so this uses the token-overlap fallback. Installing an
    // embedder later upgrades it to semantic without touching this code.
    private val ranker = SemanticRanker(embedder = null)

    init {
        if (ProviderRegistry.all().isEmpty()) {
            ProviderRegistry.register(DuckDuckGoProvider())
        }
    }

    fun run() {
        val q = query.trim()
        if (q.isBlank() || busy) return
        busy = true; error = null; results = emptyList(); mergedCount = 0
        viewModelScope.launch {
            runCatching {
                val plan = smart.plan(q)
                planText = plan.intent.describe()
                reasoning = plan.intent.reasoning
                detectedKind = plan.intent.kind

                val (_, hits) = smart.search(q, http) { p, t ->
                    error = "${p.displayName}: ${t.message}"
                }
                val before = hits.size
                val deduped = ranker.dedupe(hits)
                mergedCount = before - deduped.size
                results = deduped
                if (deduped.isEmpty() && error == null) {
                    error = "No results. Try different words, or add a source in Settings."
                }
            }.onFailure { error = it.message ?: "Search failed" }
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val keyboard = LocalSoftwareKeyboardController.current
    val uri = LocalUriHandler.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TravelExplore, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        buildString { append("DataKhoj") },
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "Search anything. Export everything.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // ---- search field ----
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("500 mp3 arijit singh · ৫০০ গান · a URL to scrape") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide(); vm.run()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )

            // ---- what it understood ----
            vm.planText?.let { plan ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AutoAwesome, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                plan, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        vm.reasoning.take(3).forEach {
                            Text(
                                "· $it", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f),
                                modifier = Modifier.padding(start = 24.dp, top = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                vm.busy -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Searching…", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                vm.error != null && vm.results.isEmpty() ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ErrorOutline, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                vm.error!!, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                vm.results.isNotEmpty() -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${vm.results.size} results",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (vm.mergedCount > 0) {
                            Text(
                                "${vm.mergedCount} duplicates merged",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(20.dp, 6.dp, 20.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.results) { r -> ResultCard(r) { uri.openUri(r.url) } }
                    }
                }

                else -> EmptyState()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultCard(r: SearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                r.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (r.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    r.snippet, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.url.removePrefix("https://").removePrefix("http://").take(34),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                r.format?.let { Chip(it, MaterialTheme.colorScheme.secondary) }
                if (r.isDownloadable) {
                    Spacer(Modifier.width(6.dp))
                    Chip("↓ ${r.humanSize().ifBlank { "file" }}", MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = .14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Try:", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "arijit singh mp3" to Icons.Default.MusicNote,
            "python book pdf" to Icons.Default.MenuBook,
            "৫০০ গান" to Icons.Default.Translate,
            "laptop prices from daraz.com.bd" to Icons.Default.ShoppingCart,
        ).forEach { (t, icon) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
