package com.example.chord.ui.explore

import android.app.Application
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeRepository
import com.example.chord.data.home.HomeShelf
import com.example.chord.ui.home.Shelf
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class ChartRegion(val code: String, val label: String)
private val chartRegions = listOf(
    ChartRegion("IN", "India"),
    ChartRegion("ZZ", "Global"),
    ChartRegion("US", "US"),
    ChartRegion("GB", "UK"),
    ChartRegion("KR", "Korea"),
)

data class ExploreUi(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val charts: List<HomeShelf> = emptyList(),
    val discovery: List<HomeShelf> = emptyList(),
    val selectedRegion: String = "IN",
    val error: String? = null,
)

class ExploreViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HomeRepository()
    private val _state = MutableStateFlow(ExploreUi())
    val state: StateFlow<ExploreUi> = _state.asStateFlow()
    private val loadedAt = HashMap<String, Long>()
    private val activeChartRequests = mutableSetOf<String>()
    private var exploreRequestRunning = false

    init { refreshAll() }

    fun onShown() {
        val region = _state.value.selectedRegion
        if (System.currentTimeMillis() - (loadedAt[region] ?: 0L) > 30 * 60_000L) refreshAll()
    }

    fun selectRegion(code: String) {
        if (code == _state.value.selectedRegion) return
        _state.value = _state.value.copy(selectedRegion = code, charts = emptyList(), error = null)
        loadCharts(code)
    }

    fun refreshAll() {
        val region = _state.value.selectedRegion
        val fetchCharts = activeChartRequests.add(region)
        val fetchExplore = !exploreRequestRunning
        if (fetchExplore) exploreRequestRunning = true
        if (!fetchCharts && !fetchExplore) return
        _state.value = _state.value.copy(
            loading = _state.value.charts.isEmpty() && _state.value.discovery.isEmpty(),
            refreshing = true,
            error = null,
        )
        viewModelScope.launch {
            val chartsTask = if (fetchCharts) async { repo.charts(region) } else null
            val exploreTask = if (fetchExplore) async { repo.explore() } else null
            chartsTask?.await()?.onSuccess { page ->
                if (_state.value.selectedRegion == region) {
                    _state.value = _state.value.copy(charts = page.shelves)
                    loadedAt[region] = System.currentTimeMillis()
                }
            }?.onFailure { e ->
                if (_state.value.selectedRegion == region) _state.value = _state.value.copy(error = e.message ?: "Couldn't load charts")
            }
            exploreTask?.await()?.onSuccess { page -> _state.value = _state.value.copy(discovery = page.shelves) }
            if (fetchCharts) activeChartRequests.remove(region)
            if (fetchExplore) exploreRequestRunning = false
            _state.value = _state.value.copy(loading = false, refreshing = false)
        }
    }

    private fun loadCharts(region: String) {
        if (!activeChartRequests.add(region)) return
        _state.value = _state.value.copy(loading = true, refreshing = false, error = null)
        viewModelScope.launch {
            repo.charts(region)
                .onSuccess { page ->
                    if (_state.value.selectedRegion == region) {
                        _state.value = _state.value.copy(charts = page.shelves, loading = false)
                        loadedAt[region] = System.currentTimeMillis()
                    }
                }
                .onFailure { e ->
                    if (_state.value.selectedRegion == region) _state.value = _state.value.copy(loading = false, error = e.message ?: "Couldn't load charts")
                }
            activeChartRequests.remove(region)
        }
    }

}

@Composable
fun ExploreScreen(
    nowPlayingId: String?,
    isPlaying: Boolean,
    onItem: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.onShown() }
    val selected = chartRegions.firstOrNull { it.code == ui.selectedRegion } ?: chartRegions.first()

    Box(modifier.fillMaxSize().background(ChordTheme.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Explore", style = ChordType.Display, color = ChordTheme.text)
                    Text("Charts, releases and music worth finding", color = ChordTheme.textDim, fontSize = 12.sp)
                }
            }

            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = viewModel::refreshAll,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "region-heading") {
                        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                            Text("TOP CHARTS", style = ChordType.Eyebrow, color = ChordTheme.textDim, modifier = Modifier.padding(horizontal = 20.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.foundation.lazy.LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(chartRegions, key = { it.code }) { region ->
                                        val active = region.code == ui.selectedRegion
                                        Box(
                                            Modifier.clip(CircleShape)
                                                .background(if (active) Color.White else Color.White.copy(alpha = 0.07f))
                                                .border(0.6.dp, Color.White.copy(alpha = if (active) 0f else 0.12f), CircleShape)
                                                .clickable { viewModel.selectRegion(region.code) }
                                                .padding(horizontal = 17.dp, vertical = 9.dp),
                                        ) {
                                            Text(region.label, color = if (active) Color.Black else ChordTheme.text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            Text(
                                when (selected.code) {
                                    "IN" -> "India's most-played music"
                                    "ZZ" -> "What's moving around the world"
                                    else -> "Popular music in ${selected.label}"
                                },
                                color = ChordTheme.textDim, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                            )
                        }
                    }
                    when {
                        ui.loading && ui.charts.isEmpty() -> item(key = "chart-loading") {
                            Box(Modifier.fillMaxWidth().padding(44.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = ChordTheme.text, strokeWidth = 2.dp)
                            }
                        }
                        ui.error != null && ui.charts.isEmpty() -> item(key = "chart-error") {
                            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                                Text(ui.error.orEmpty(), color = ChordTheme.textDim, fontSize = 13.sp)
                                Text("Retry", color = ChordTheme.text, modifier = Modifier.clickable { viewModel.refreshAll() }.padding(top = 12.dp))
                            }
                        }
                        ui.charts.isEmpty() -> item(key = "chart-empty") {
                            Text("No chart sections were returned for this region.", color = ChordTheme.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
                        }
                        else -> items(ui.charts, key = { "${ui.selectedRegion}:${it.title}:${it.items.firstOrNull()?.browseId}" }) { shelf ->
                            Shelf(shelf, nowPlayingId, isPlaying, onItem)
                        }
                    }
                    if (ui.discovery.isNotEmpty()) {
                        item(key = "discovery-title") {
                            Column(Modifier.padding(start = 20.dp, top = 28.dp, bottom = 2.dp)) {
                                Text("DISCOVER MORE", style = ChordType.Eyebrow, color = ChordTheme.textDim)
                                Text("New & trending", color = ChordTheme.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                        items(ui.discovery, key = { "discover:${it.title}:${it.items.firstOrNull()?.browseId}" }) { shelf ->
                            Shelf(shelf, nowPlayingId, isPlaying, onItem)
                        }
                    }
                }
            }
        }

    }
}
