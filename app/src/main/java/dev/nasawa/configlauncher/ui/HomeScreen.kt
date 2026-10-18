package dev.nasawa.configlauncher.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var openFolder by remember { mutableStateOf<ResolvedItem?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val drag = remember { DragController({ vm.store.engine }, onCancel = { vm.store.pollNow() }) { vm.store.edit(it) } }
    var cloudUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { if (vm.store.isCloudFolder(uri)) cloudUri = uri else vm.store.useFolder(uri) }
    }
    LaunchedEffect(ui.load.notice) { ui.load.notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.store.engine.consumeNotice() } }
    val toast = { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    val launchApp = { app: InstalledApp -> if (!vm.apps.launch(app)) toast("Couldn't open ${Labels.clean(app.label)}") }

    val onOpen: (ResolvedItem) -> Unit = { r ->
        when (val item = r.item) {
            is FolderItem -> openFolder = r
            is ShortcutItem -> when {
                r.workDisabled -> toast("Turn on \"Include work apps in config\" to use this.")
                !vm.apps.launch(item) -> toast("Shortcut no longer available")
            }
            is AppItem -> when {
                r.workDisabled -> toast("Turn on \"Include work apps in config\" to use this.")
                r.app == null -> toast("Not installed")
                else -> launchApp(r.app)
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ui.load.sourceName, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.weight(1f))
                    TextButton({ drawerOpen = true }) { Text("Apps") }
                    Box {
                        TextButton({ menuOpen = true }) { Text("⋮") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem({ Text("Pick config folder…") }, { menuOpen = false; pickFolder.launch(null) })
                            DropdownMenuItem({ Text("Use app storage") }, { menuOpen = false; vm.store.useAppStorage() })
                            DropdownMenuItem({ Text("Reload launcher.json") }, { menuOpen = false; vm.store.pollNow() })
                        }
                    }
                }
                if (ui.load.conflict) ConflictBanner(ui.load.pendingEdits, onUseFile = vm.store::useFile, onKeepMine = vm.store::keepMine)
                ui.load.issue?.let { IssueBanner(it, ui.load.reconnectable, onReconnect = { pickFolder.launch(null) }, onCreate = vm.store::createFromCurrentLayout, onUseAppStorage = vm.store::useAppStorage) }
                val pages = ui.layout.pages
                val pager = rememberPagerState { pages.size }
                pages.getOrNull(pager.currentPage).let { p ->
                    drag.currentPageId = p?.id; drag.currentPageSynthetic = p?.synthetic == true
                }
                drag.firstPageId = pages.firstOrNull { !it.synthetic }?.id
                drag.grid = ui.layout.grid
                drag.dockCount = ui.layout.dock.size
                HorizontalPager(
                    pager, Modifier.weight(1f).fillMaxWidth(), key = { pages.getOrNull(it)?.id ?: it },
                    userScrollEnabled = drag.dragged == null,
                ) { index ->
                    pages.getOrNull(index)?.let { PageGrid(it, ui.layout.grid, vm.apps, drag, onOpen) }
                }
                if (pages.size > 1) PageDots(pages.size, pager.currentPage)
                Dock(ui.layout.dock, vm.apps, drag, onOpen)
            }
        }

        cloudUri?.let { uri ->
            AlertDialog(
                onDismissRequest = { cloudUri = null },
                confirmButton = { TextButton({ cloudUri = null; vm.store.useFolder(uri) }) { Text("Use anyway") } },
                dismissButton = { TextButton({ cloudUri = null }) { Text("Cancel") } },
                text = { Text("Cloud folders can take a while to sync. A Syncthing or git folder works best.") },
            )
        }

        openFolder?.let { folder ->
            AlertDialog(
                onDismissRequest = { openFolder = null },
                confirmButton = { TextButton({ openFolder = null }) { Text("Close") } },
                title = { Text(folder.title) },
                text = {
                    LazyVerticalGrid(GridCells.Fixed(4)) {
                        items(folder.children, key = { it.item.id }) { c -> Tile(c, vm.apps) { openFolder = null; onOpen(c) } }
                    }
                },
            )
        }

        if (drawerOpen) {
            ModalBottomSheet({ drawerOpen = false }) {
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxHeight(0.85f).padding(8.dp)) {
                    items(ui.layout.drawer, key = { it.key }) { app ->
                        Tile(ResolvedItem(AppItem("drawer", app.packageName, app.activityName), app), vm.apps) {
                            drawerOpen = false; launchApp(app)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictBanner(count: Int, onUseFile: () -> Unit, onKeepMine: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF4A3B00), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("launcher.json changed while you were editing ($count change${if (count == 1) "" else "s"}).", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onUseFile) { Text("Use file") }
        TextButton(onKeepMine) { Text("Keep mine") }
    }
}

@Composable
private fun IssueBanner(issue: dev.nasawa.configlauncher.store.Issue, reconnectable: Boolean, onReconnect: () -> Unit, onCreate: () -> Unit, onUseAppStorage: () -> Unit) {
    val errors = issue.details
    var expanded by remember(issue) { mutableStateOf(false) }
    val title = when (issue) {
        is dev.nasawa.configlauncher.store.Issue.ParseError, is dev.nasawa.configlauncher.store.Issue.ReadFailed -> "Can't read launcher.json. Showing the last working layout."
        is dev.nasawa.configlauncher.store.Issue.Invalid -> "launcher.json has ${errors.size} problem${if (errors.size == 1) "" else "s"}. Showing the last working layout."
        is dev.nasawa.configlauncher.store.Issue.AccessLost -> "Can't reach your config folder. Showing the last working layout."
        dev.nasawa.configlauncher.store.Issue.Missing -> "No launcher.json yet."
        is dev.nasawa.configlauncher.store.Issue.SaveFailed -> "Couldn't save your change."
        is dev.nasawa.configlauncher.store.Issue.TooNew -> "This file needs a newer Config Launcher."
    }
    Column(
        Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF5C1F1F), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }.padding(10.dp),
    ) {
        Text(title, color = Color.White)
        Row {
            BannerActions.forIssue(issue, reconnectable).forEach { a ->
                when (a) {
                    BannerAction.CREATE -> TextButton(onCreate) { Text("Create from current layout") }
                    BannerAction.RECONNECT -> TextButton(onReconnect) { Text("Reconnect") }
                }
            }
        }
        val shown = if (expanded) errors else errors.take(1)
        shown.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCDD2)) }
        if (!expanded && errors.size > 1) Text("Tap to see all", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}

@Composable
private fun PageGrid(page: ResolvedPage, grid: Grid, apps: InstalledApps, drag: DragController, onOpen: (ResolvedItem) -> Unit) {
    val byCell = page.items.associateBy { (it.item.x ?: 0) to (it.item.y ?: 0) }
    val rows = if (page.synthetic) maxOf(grid.rows, (page.items.maxOfOrNull { it.item.y ?: 0 } ?: 0) + 1) else grid.rows
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        page.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Color.LightGray, modifier = Modifier.padding(4.dp)) }
        val body: @Composable ColumnScope.() -> Unit = {
            for (y in 0 until rows) {
                Row(Modifier.fillMaxWidth().then(if (page.synthetic) Modifier.height(96.dp) else Modifier.weight(1f))) {
                    for (x in 0 until grid.columns) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            byCell[x to y]?.let { r -> with(drag) { Tile(r, apps, Modifier.draggable(r, page.synthetic)) { onOpen(r) } } }
                        }
                    }
                }
            }
        }
        if (page.synthetic) {
            Column(Modifier.weight(1f).verticalScrollCompat(), content = body)
        } else Column(Modifier.weight(1f).onGloballyPositioned { drag.gridRect = it.boundsInRoot() }, content = body)
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.padding(3.dp).size(6.dp).background(if (i == current) Color.White else Color.DarkGray, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun Dock(items: List<ResolvedItem>, apps: InstalledApps, drag: DragController, onOpen: (ResolvedItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 64.dp).onGloballyPositioned { drag.dockRect = it.boundsInRoot() }.background(Color(0x22FFFFFF), RoundedCornerShape(20.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { r -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { with(drag) { Tile(r, apps, Modifier.draggable(r, false), showLabel = false) { onOpen(r) } } } }
        if (items.isEmpty()) Text("Dock: long-press an app and drop it here", color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
fun Tile(r: ResolvedItem, apps: InstalledApps, modifier: Modifier = Modifier, showLabel: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(4.dp).alpha(if (r.missing || r.app?.paused == true) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (r.item is FolderItem) FolderIcon(r, apps) else AppIconImage(r.app, apps, 48)
        if (showLabel) Text(
            Labels.clean(r.title), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.widthIn(max = 76.dp),
        )
    }
}

@Composable
private fun AppIconImage(app: InstalledApp?, apps: InstalledApps, sizeDp: Int) {
    val bmp = remember(app) { app?.let { apps.icon(it, 144) } }
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(sizeDp.dp))
    else Box(Modifier.size(sizeDp.dp).background(Color.DarkGray, RoundedCornerShape(12.dp)))
}

@Composable
private fun FolderIcon(r: ResolvedItem, apps: InstalledApps) {
    Box(Modifier.size(48.dp).background(Color(0x33FFFFFF), RoundedCornerShape(14.dp)).padding(5.dp)) {
        Column {
            r.children.take(4).chunked(2).forEach { row ->
                Row { row.forEach { c -> Box(Modifier.padding(1.dp)) { AppIconImage(c.app, apps, 17) } } }
            }
        }
    }
}
