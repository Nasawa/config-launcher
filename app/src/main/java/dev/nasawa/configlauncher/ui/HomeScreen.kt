package dev.nasawa.configlauncher.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.nasawa.configlauncher.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var openFolder by remember { mutableStateOf<ResolvedItem?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf(vm.store.prefs.labelBackdrop) }
    var askMerge by remember { mutableStateOf(vm.store.prefs.askBeforeMerging) }
    var keyboardOnOpen by remember { mutableStateOf(vm.store.prefs.drawerKeyboard) }
    var menuOpen by remember { mutableStateOf(false) }
    var folderInfo by remember { mutableStateOf<String?>(null) }   // "" = app-only
    val drag = remember { DragController({ vm.store.engine }, onCancel = { vm.store.pollNow() }) { vm.store.edit(it) } }
    var cloudUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var onboarded by remember { mutableStateOf(vm.store.prefs.onboarded) }
    // true: the pick moves the layout (first run, menu); false: Reconnect just re-grants the folder.
    var moving by remember { mutableStateOf(false) }
    var moveAsk by remember { mutableStateOf<Pair<android.net.Uri, dev.nasawa.configlauncher.store.ConfigEngine.Inspection>?>(null) }
    val scope = rememberCoroutineScope()
    val proceed = { uri: android.net.Uri ->
        if (!moving) vm.store.useFolder(uri)
        else scope.launch {
            val ins = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.store.inspectFolder(uri) }
            if (ins == null || ins is dev.nasawa.configlauncher.store.ConfigEngine.Inspection.Empty) { vm.store.moveToFolder(uri, useMine = true); onboarded = true }
            else moveAsk = uri to ins
        }
        Unit
    }
    var pickCancelled by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickCancelled = uri == null
        if (uri != null) { if (vm.store.isCloudFolder(uri)) cloudUri = uri else proceed(uri) }
    }
    if (!onboarded) {
        FirstRunScreen(
            onChooseFolder = { moving = true; pickFolder.launch(null) },
            onSkip = { vm.store.skipOnboarding(); onboarded = true },
            cancelled = pickCancelled,
        )
        cloudUri?.let { uri -> CloudDialog({ cloudUri = null; proceed(uri) }, { cloudUri = null }, { cloudUri = null; pickFolder.launch(null) }) }
        moveAsk?.let { (uri, ins) -> MoveDialog(ins, { moveAsk = null; vm.store.moveToFolder(uri, it); onboarded = true }, { moveAsk = null }) }
        return
    }
    // Merge results use the in-app snackbar (ux.md §9.1); other one-shot notices are toasts.
    val res = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(ui.load.notice) { ui.load.notice?.let { Toast.makeText(ctx, it.resolve(res), Toast.LENGTH_LONG).show(); vm.store.engine.consumeNotice() } }
    val toast = { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    val launchApp = { app: InstalledApp -> if (!vm.apps.launch(app)) toast(res.getString(R.string.open_failed, Labels.clean(app.label))) }

    val onOpen: (ResolvedItem) -> Unit = { r ->
        when (val item = r.item) {
            is FolderItem -> openFolder = r
            is ShortcutItem -> when {
                r.workDisabled -> toast(res.getString(R.string.work_disabled))
                !vm.apps.launch(item) -> toast(res.getString(R.string.shortcut_gone))
            }
            is AppItem -> when {
                r.workDisabled -> toast(res.getString(R.string.work_disabled))
                r.app == null -> toast(res.getString(R.string.not_installed))
                else -> launchApp(r.app)
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ui.load.reconnectable) stringResource(R.string.source_folder, ui.load.sourceName.removePrefix("Folder: ")) else stringResource(R.string.source_app_storage), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.weight(1f))
                    TextButton({ drawerOpen = true }) { Text(stringResource(R.string.apps)) }
                    Box {
                        TextButton({ menuOpen = true }) { Text("⋮") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.menu_config_folder)) }, { menuOpen = false; folderInfo = vm.store.engine.source.location ?: "" })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_pick_folder)) }, { menuOpen = false; moving = true; pickFolder.launch(null) })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_use_phone_storage)) }, { menuOpen = false; vm.store.useAppStorage() })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_label_backdrop)) }, {
                                backdrop = !backdrop; vm.store.prefs.labelBackdrop = backdrop
                            }, trailingIcon = { Checkbox(backdrop, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_ask_before_merging)) }, {
                                askMerge = !askMerge; vm.store.setAskBeforeMerging(askMerge)
                            }, trailingIcon = { Checkbox(askMerge, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_open_keyboard)) }, {
                                keyboardOnOpen = !keyboardOnOpen; vm.store.prefs.drawerKeyboard = keyboardOnOpen
                            }, trailingIcon = { Checkbox(keyboardOnOpen, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.menu_reload)) }, { menuOpen = false; vm.store.pollNow() })
                        }
                    }
                }
                if (ui.load.conflict) ConflictBanner(ui.load.pendingEdits, onUseFile = vm.store::useFile, onKeepMine = vm.store::keepMine)
                ui.load.issue?.let { IssueBanner(it, ui.load.reconnectable, locate = vm.store.engine::locateErrors, onReconnect = { moving = false; pickFolder.launch(null) }, onCreate = vm.store::createFromCurrentLayout, onUseAppStorage = { vm.store.useAppStorage() }, onRetry = vm.store::retrySave) }
                val pages = ui.layout.pages
                val pager = rememberPagerState { pages.size }
                pages.getOrNull(pager.currentPage).let { p ->
                    drag.currentPageId = p?.id
                }
                drag.grid = ui.layout.grid
                drag.dockCount = ui.layout.dock.size
                CompositionLocalProvider(LocalLabelBackdrop provides backdrop) {
                HorizontalPager(
                    pager, Modifier.weight(1f).fillMaxWidth(), key = { pages.getOrNull(it)?.id ?: it },
                    userScrollEnabled = drag.dragged == null,
                ) { index ->
                    pages.getOrNull(index)?.let { PageGrid(it, ui.layout.grid, vm.apps, drag, onOpen) }
                }
                if (pages.size > 1) PageDots(pages.size, pager.currentPage)
                }
                Dock(ui.layout.dock, vm.apps, drag, onOpen)
                ui.load.merge?.let { m ->
                    MergeSnackbar(m, onUndo = { vm.store.undoMerge() }.takeIf { m.undo && ui.load.mergeUndo },
                        onDismiss = { vm.store.engine.dismissMergeUndo() })
                }
            }
        }

        cloudUri?.let { uri -> CloudDialog({ cloudUri = null; proceed(uri) }, { cloudUri = null }, { cloudUri = null; pickFolder.launch(null) }) }
        moveAsk?.let { (uri, ins) -> MoveDialog(ins, { moveAsk = null; vm.store.moveToFolder(uri, it) }, { moveAsk = null }) }

        folderInfo?.let { path ->
            AlertDialog(
                onDismissRequest = { folderInfo = null },
                title = { Text(stringResource(R.string.menu_config_folder)) },
                text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(path.ifEmpty { stringResource(R.string.folder_app_only) }) } },
                confirmButton = {
                    if (path.isNotEmpty()) TextButton({
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("launcher.json", path))
                        toast(res.getString(R.string.copied))
                    }) { Text(stringResource(R.string.copy)) }
                },
                dismissButton = { TextButton({ folderInfo = null }) { Text(stringResource(R.string.close)) } },
            )
        }

        openFolder?.let { folder ->
            AlertDialog(
                onDismissRequest = { openFolder = null },
                confirmButton = { TextButton({ openFolder = null }) { Text(stringResource(R.string.close)) } },
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
                var query by remember { mutableStateOf("") }
                val shown = remember(query, ui.layout.drawer) { DrawerSearch.filter(ui.layout.drawer, query) }
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                val ime = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                // ux.md §2: focus + keyboard on open; with TalkBack, focus only (no auto keyboard).
                val talkBack = remember { (ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled }
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                LaunchedEffect(Unit) {
                    if (DrawerKeyboard.focus(keyboardOnOpen)) focus.requestFocus()
                    if (DrawerKeyboard.showIme(keyboardOnOpen, talkBack, config)) ime?.show()
                }
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                // Scrolling the list hides the keyboard; tapping the field brings it back.
                LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress) ime?.hide() }
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { shown.firstOrNull()?.let { drawerOpen = false; launchApp(it) } }),
                )
                if (shown.isEmpty()) Text(stringResource(R.string.search_no_match, query.trim()), Modifier.padding(16.dp), color = Color.Gray)
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxHeight(0.85f).padding(8.dp), state = gridState) {
                    items(shown, key = { it.key }) { app ->
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
private fun MergeSnackbar(n: dev.nasawa.configlauncher.store.MergeNotice, onUndo: (() -> Unit)?, onDismiss: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val copy = remember(res) { dev.nasawa.configlauncher.store.MergeCopy(res) }
    val text = copy.text(n); val spoken = copy.spoken(n)
    LaunchedEffect(n) { kotlinx.coroutines.delay(dev.nasawa.configlauncher.store.MergeCopy.durationMs(n)); onDismiss() }
    Snackbar(
        // TalkBack reads the long form (§9.1); Undo stays a separate action button.
        Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        action = onUndo?.let { u -> { TextButton(u) { Text(androidx.compose.ui.res.stringResource(dev.nasawa.configlauncher.R.string.merge_undo)) } } },
        dismissAction = { TextButton(onDismiss) { Text("✕") } },
    ) { Text(text, Modifier.semantics { contentDescription = spoken }) }
}

@Composable
private fun ConflictBanner(count: Int, onUseFile: () -> Unit, onKeepMine: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF4A3B00), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.b6_conflict, count, count), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onUseFile) { Text(stringResource(R.string.action_use_file)) }
        TextButton(onKeepMine) { Text(stringResource(R.string.action_keep_mine)) }
    }
}

@Composable
private fun IssueBanner(issue: dev.nasawa.configlauncher.store.Issue, reconnectable: Boolean, locate: (List<String>) -> List<dev.nasawa.configlauncher.config.TextPos?>, onReconnect: () -> Unit, onCreate: () -> Unit, onUseAppStorage: () -> Unit, onRetry: () -> Unit) {
    val errors = issue.details
    var expanded by remember(issue) { mutableStateOf(false) }
    val title = when (issue) {
        is dev.nasawa.configlauncher.store.Issue.ParseError, is dev.nasawa.configlauncher.store.Issue.ReadFailed -> stringResource(R.string.b1_cant_read)
        is dev.nasawa.configlauncher.store.Issue.Invalid -> pluralStringResource(R.plurals.b2_invalid, errors.size, errors.size)
        is dev.nasawa.configlauncher.store.Issue.AccessLost -> stringResource(R.string.b3_access_lost)
        dev.nasawa.configlauncher.store.Issue.Missing -> stringResource(R.string.b4_missing)
        is dev.nasawa.configlauncher.store.Issue.SaveFailed -> issue.title.resolve(androidx.compose.ui.platform.LocalResources.current)
        is dev.nasawa.configlauncher.store.Issue.TooNew -> stringResource(R.string.b7_too_new)
    }
    Column(
        Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF5C1F1F), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }.padding(10.dp),
    ) {
        Text(title, color = Color.White)
        Row {
            BannerActions.forIssue(issue, reconnectable).forEach { a ->
                when (a) {
                    BannerAction.CREATE -> TextButton(onCreate) { Text(stringResource(R.string.action_create)) }
                    BannerAction.RECONNECT -> TextButton(onReconnect) { Text(stringResource(R.string.action_reconnect)) }
                    BannerAction.RETRY -> TextButton(onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        // Tokenizer pass runs only once the detail view is open (Q-S3), off the main thread.
        var where by remember(issue) { mutableStateOf<List<dev.nasawa.configlauncher.config.TextPos?>>(emptyList()) }
        if (expanded) androidx.compose.runtime.LaunchedEffect(issue) {
            where = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { locate(errors) }.getOrDefault(emptyList()) }
        }
        val res = androidx.compose.ui.platform.LocalResources.current
        val shown = if (expanded) errors.mapIndexed { i, e -> where.getOrNull(i)?.let { res.getString(R.string.error_at, it.line, it.column, e) } ?: e } else errors.take(1)
        shown.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCDD2)) }
        if (!expanded && errors.size > 1) Text(stringResource(R.string.tap_to_see_all), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}

@Composable
private fun PageGrid(page: ResolvedPage, grid: Grid, apps: InstalledApps, drag: DragController, onOpen: (ResolvedItem) -> Unit) {
    val byCell = page.items.associateBy { (it.item.x ?: 0) to (it.item.y ?: 0) }
    val rows = grid.rows
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        page.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Color.LightGray, modifier = Modifier.padding(4.dp)) }
        val body: @Composable ColumnScope.() -> Unit = {
            for (y in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (x in 0 until grid.columns) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            byCell[x to y]?.let { r -> with(drag) { Tile(r, apps, Modifier.draggable(r)) { onOpen(r) } } }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).onGloballyPositioned { drag.gridRect = it.boundsInRoot() }, content = body)
    }
}

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
        items.forEach { r -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { with(drag) { Tile(r, apps, Modifier.draggable(r), showLabel = false) { onOpen(r) } } } }
        if (items.isEmpty()) Text(stringResource(R.string.dock_empty), color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
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
            style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.widthIn(max = 76.dp).then(LabelBackdrop.modifier(LocalLabelBackdrop.current)),
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
