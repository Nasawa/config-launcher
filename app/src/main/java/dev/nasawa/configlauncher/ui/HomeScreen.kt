package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.apps.AppEntry
import dev.nasawa.configlauncher.apps.InstalledApps

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val installed = remember { InstalledApps(context.applicationContext) }
    var apps by remember { mutableStateOf(installed.load()) }
    LaunchedEffect(Unit) { installed.changes().collect { apps = installed.load() } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxSize().systemBarsPadding().padding(8.dp),
            ) {
                items(apps, key = { it.key + it.userSerial }) { app ->
                    AppIcon(app, installed) { installed.launch(app) }
                }
            }
        }
    }
}

@Composable
fun AppIcon(app: AppEntry, installed: InstalledApps, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bmp = remember(app) { installed.icon(app, 144) }
        if (bmp != null) Image(bmp.asImageBitmap(), app.label, Modifier.size(48.dp))
        else Spacer(Modifier.size(48.dp))
        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
