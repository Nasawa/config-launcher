package dev.nasawa.configlauncher.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.TitleBarModel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.nasawa.configlauncher.store.AgentDocs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // #79: load warnings for the file that's showing (none while a problem banner is up: that's the last-good layout).
    val warnings = remember(ui.load.config, ui.load.issue) { if (ui.load.issue == null) dev.nasawa.configlauncher.config.Elements.warnings(ui.load.config) else emptyList() }
    val ctx = LocalContext.current
    var openFolder by remember { mutableStateOf<ResolvedItem?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf(vm.store.prefs.labelBackdrop) }
    var askMerge by remember { mutableStateOf(vm.store.prefs.askBeforeMerging) }
    var keyboardOnOpen by remember { mutableStateOf(vm.store.prefs.drawerKeyboard) }
    var agentDocs by remember { mutableStateOf(vm.store.prefs.agentDocs) }
    var includeHidden by remember { mutableStateOf(vm.store.prefs.includeHiddenApps) }
    // ux.md §7.1: Add vs Update label (null = app-only, hidden), read when the ⋮ menu opens; Replace confirm for an edited file.
    var agentAction by remember { mutableStateOf<AgentDocs.MenuAction?>(null) }
    var agentConfirm by remember { mutableStateOf(false) }
    var agentSnack by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var folderInfo by remember { mutableStateOf<String?>(null) }   // "" = app-only
    var warningsOpen by remember { mutableStateOf(false) }
    // ux.md §4.1 edit mode: entered from `Edit layout` or a drag; left on Done, Back, Home or 30 s idle.
    var editing by remember { mutableStateOf(false) }
    var pageList by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<ResolvedItem?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    var drawerSheet by remember { mutableStateOf<InstalledApp?>(null) }
    var hiddenSheet by remember { mutableStateOf(false) }
    // ux.md §14 R3/R8: one Undo snackbar at a time (folder removed, app hidden).
    var undoSnack by remember { mutableStateOf<UndoSnack?>(null) }
    var moveFor by remember { mutableStateOf<ResolvedItem?>(null) }
    val drag = remember { DragController({ vm.store.engine }, onCancel = { vm.store.pollNow() }, onStart = { editing = true }) { vm.store.edit(it) } }
    var cloudUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var onboarded by remember { mutableStateOf(vm.store.prefs.onboarded) }
    // #61: default home app. Re-checked on every resume (the user may change it in system settings).
    var isHome by remember { mutableStateOf(dev.nasawa.configlauncher.apps.HomeRole.held(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { isHome = dev.nasawa.configlauncher.apps.HomeRole.held(ctx); onPauseOrDispose { } }
    var homePrompt by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        isHome = dev.nasawa.configlauncher.apps.HomeRole.held(ctx)
        if (res.resultCode != android.app.Activity.RESULT_OK && !isHome) vm.store.prefs.homeRoleDenials += 1
    }
    val becomeHome = {
        when (dev.nasawa.configlauncher.apps.HomeRole.route(dev.nasawa.configlauncher.apps.HomeRole.available(ctx), vm.store.prefs.homeRoleDenials)) {
            dev.nasawa.configlauncher.apps.HomeRole.Route.ROLE_REQUEST -> runCatching { roleLauncher.launch(dev.nasawa.configlauncher.apps.HomeRole.requestIntent(ctx)) }
                .onFailure { runCatching { ctx.startActivity(dev.nasawa.configlauncher.apps.HomeRole.settingsIntent()) } }
            dev.nasawa.configlauncher.apps.HomeRole.Route.HOME_SETTINGS -> runCatching { ctx.startActivity(dev.nasawa.configlauncher.apps.HomeRole.settingsIntent()) }
        }
        Unit
    }
    LaunchedEffect(onboarded) {
        if (dev.nasawa.configlauncher.apps.HomeRole.shouldPrompt(onboarded, isHome, vm.store.prefs.homePromptShown)) {
            homePrompt = true; vm.store.prefs.homePromptShown = true
        }
    }
    // true: the pick moves the layout (first run, menu); false: Reconnect just re-grants the folder.
    var moving by remember { mutableStateOf(false) }
    var folderProblem by remember { mutableStateOf<dev.nasawa.configlauncher.store.ConfigEngine.Inspection.Unreadable?>(null) }
    var moveAsk by remember { mutableStateOf<Pair<android.net.Uri, dev.nasawa.configlauncher.store.ConfigEngine.Inspection>?>(null) }
    val scope = rememberCoroutineScope()
    val proceed = { uri: android.net.Uri ->
        if (!moving) vm.store.useFolder(uri)
        else scope.launch {
            val ins = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.store.inspectFolder(uri) }
            when (FolderPick.next(ins)) {
                // #74: never useMine here; if a file showed up since the inspect, moveTo adopts it.
                FolderPick.Next.CREATE -> { vm.store.moveToFolder(uri, useMine = false); onboarded = true }
                FolderPick.Next.ASK -> moveAsk = uri to ins
                FolderPick.Next.PROBLEM -> folderProblem = ins as dev.nasawa.configlauncher.store.ConfigEngine.Inspection.Unreadable
            }
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
        moveAsk?.let { (uri, ins) -> MoveDialog(ins, { moveAsk = null; vm.store.moveToFolder(uri, it); onboarded = true }, { moveAsk = null; vm.store.forgetFolder(uri) }) }
        folderProblem?.let { p -> FolderProblemDialog(p, { folderProblem = null; pickFolder.launch(null) }, { folderProblem = null }) }
        return
    }
    // Merge results use the in-app snackbar (ux.md §9.1); other one-shot notices are toasts.
    val res = androidx.compose.ui.platform.LocalResources.current
    // §1.4: the part of the system-gesture inset not already covered by systemBarsPadding.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gestureBottomPx = (WindowInsets.systemGestures.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0).toFloat()
    LaunchedEffect(ui.load.notice) { ui.load.notice?.let { Toast.makeText(ctx, it.resolve(res), Toast.LENGTH_LONG).show(); vm.store.engine.consumeNotice() } }
    val toast = { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    LaunchedEffect(menuOpen) { if (menuOpen) agentAction = withContext(Dispatchers.IO) { vm.store.agentDocsMenuAction() } }
    val runAgentUpdate = { confirmed: Boolean ->
        scope.launch {
            when (withContext(Dispatchers.IO) { vm.store.updateAgentDocs(confirmed) }) {
                AgentDocs.Update.UPDATED -> agentSnack = true
                AgentDocs.Update.NEEDS_CONFIRM -> agentConfirm = true
                else -> Unit
            }
        }
        Unit
    }
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

    val onLong: (ResolvedItem) -> Unit = { sheetFor = it }
    // §14 R4/R5: every way out of edit mode saves pending edits and drops empty pages in that write.
    val leaveEdit = { if (editing) { editing = false; vm.store.leaveEditMode() } }
    LaunchedEffect(editing, ui.load.config) { if (editing) { kotlinx.coroutines.delay(30_000); if (drag.dragged == null) leaveEdit() } }
    androidx.activity.compose.BackHandler(editing) { leaveEdit() }
    // §14 R3: a folder goes with one write and an Undo snackbar, no confirm dialog (sheet and TalkBack action alike).
    val removeItem = { r: ResolvedItem ->
        val undo = (r.item as? FolderItem)?.let { Edits.undoRemove(vm.store.engine.state.value.config, it.id) }
        vm.store.edit(Edit.Remove(r.item.id))
        if (undo != null) undoSnack = UndoSnack(res.getString(R.string.removed_folder, Labels.clean(r.title)), undo, Labels.clean(r.title))
    }
    // §14 R8: Hide gets an Undo snackbar.
    val hideApp = { pkg: String, label: String ->
        vm.store.edit(Edit.Hide(pkg))
        undoSnack = UndoSnack(res.getString(R.string.app_hidden, label), Edit.Unhide(pkg), label)
    }
    // ux.md §12: drag-free editing through TalkBack custom actions, one write each.
    val a11y: (ResolvedItem, TilePlace) -> List<CustomAccessibilityAction> = { r, place ->
        val pkg = (r.item as? AppItem)?.packageName
        TileActions.of(place, dockFull = ui.layout.dock.size >= Edits.MAX_DOCK, canHide = pkg != null).map { a ->
            when (a) {
                TileAction.MOVE_TO -> CustomAccessibilityAction(res.getString(R.string.a11y_move_to)) { moveFor = r; true }
                TileAction.MOVE_TO_DOCK -> CustomAccessibilityAction(res.getString(R.string.a11y_move_to_dock)) { vm.store.edit(Edit.MoveToDock(r.item.id, Int.MAX_VALUE)); true }
                TileAction.REMOVE -> CustomAccessibilityAction(res.getString(R.string.action_remove_home)) { removeItem(r); true }
                TileAction.HIDE -> CustomAccessibilityAction(res.getString(R.string.action_hide)) { hideApp(pkg!!, Labels.clean(r.app?.label ?: r.title)); true }
            }
        }
    }
    LaunchedEffect(editing, ui.load.config) { if (editing) { kotlinx.coroutines.delay(30_000); if (drag.dragged == null) editing = false } }
    androidx.activity.compose.BackHandler(editing) { editing = false }

    MaterialTheme(colorScheme = darkColorScheme()) {
        // Transparent over the system wallpaper (theme windowShowWallpaper, #58).
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Column(Modifier.fillMaxSize().systemBarsPadding().swipeUpToOpen(
                // §1.4: off in edit mode and while dragging; the bottom system-gesture strip belongs to the system.
                { !editing && drag.dragged == null && !drawerOpen }, { gestureBottomPx }) { drawerOpen = true }) {
                // #75: the edit bar takes the top row's place at the same height, so the grid doesn't jump under the finger.
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                if (editing) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editing_layout), color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(leaveEdit) { Text(stringResource(R.string.done)) }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ui.load.reconnectable) stringResource(R.string.source_folder, ui.load.sourceName.removePrefix("Folder: ")) else stringResource(R.string.source_app_storage), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.weight(1f))
                    // §1.4: the Apps button is the tap path (hint "open app list"); hidden in edit mode.
                    val hint = stringResource(R.string.a11y_open_drawer_hint)
                    TextButton({ drawerOpen = true }, Modifier.semantics { onClick(label = hint) { drawerOpen = true; true } }) { Text(stringResource(R.string.apps)) }
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
                            // #72: apps.json exists only in folder mode; the toggle rewrites it silently.
                            if (ui.load.reconnectable) {
                                val label = stringResource(R.string.menu_include_hidden_apps)
                                val spoken = label + ". " + stringResource(R.string.menu_include_hidden_apps_desc)
                                DropdownMenuItem({ Text(label) }, {
                                    includeHidden = !includeHidden; vm.store.setIncludeHiddenApps(includeHidden)
                                }, Modifier.semantics {
                                    // TalkBack: label, Pragma's description, and the checked state.
                                    contentDescription = spoken
                                    toggleableState = androidx.compose.ui.state.ToggleableState(includeHidden)
                                }, trailingIcon = { Checkbox(includeHidden, null) })
                            }
                            // ux.md §7.1: setting + action, folder mode only (no Settings screen yet, §14 R10).
                            if (agentAction != null) {
                                DropdownMenuItem({ Text(stringResource(R.string.menu_agent_docs)) }, {
                                    agentDocs = !agentDocs; vm.store.prefs.agentDocs = agentDocs
                                }, trailingIcon = { Checkbox(agentDocs, null) })
                                DropdownMenuItem({ Text(stringResource(if (agentAction == AgentDocs.MenuAction.ADD) R.string.agent_docs_add else R.string.agent_docs_update)) },
                                    { menuOpen = false; runAgentUpdate(false) })
                            }
                            DropdownMenuItem({ Text(stringResource(R.string.menu_reload)) }, { menuOpen = false; vm.store.pollNow() })
                            if (!isHome) DropdownMenuItem({ Text(stringResource(R.string.home_role_button)) }, { menuOpen = false; becomeHome() })
                        }
                    }
                }
                }
                if (ui.load.conflict) ConflictBanner(ui.load.pendingEdits, onUseFile = vm.store::useFile, onKeepMine = vm.store::keepMine)
                ui.load.issue?.let { IssueBanner(it, ui.load.reconnectable, locate = vm.store.engine::locateErrors, onReconnect = { moving = false; pickFolder.launch(null) }, onCreate = vm.store::createFromCurrentLayout, onUseAppStorage = { vm.store.useAppStorage() }, onRetry = vm.store::retrySave) }
                val pages = ui.layout.pages
                val pager = rememberPagerState { pages.size }
                // ux.md §1 / §14 R4: Home on home leaves edit mode (saving first), closes drawer and folder, goes to page 1.
                LaunchedEffect(Unit) {
                    vm.homePresses.collect {
                        leaveEdit(); drawerOpen = false; openFolder = null; hiddenSheet = false
                        sheetFor = null; homeMenu = false; drawerSheet = null
                        if (pager.pageCount > 0) pager.animateScrollToPage(0)
                    }
                }
                pages.getOrNull(pager.currentPage).let { p ->
                    drag.currentPageId = p?.id
                }
                drag.grid = ui.layout.grid
                drag.dockCount = ui.layout.dock.size
                CompositionLocalProvider(LocalLabelBackdrop provides backdrop) {
                // #79: a title bar in the file replaces the §1.3 page label on pages where it's enabled.
                val titleBar = TitleBarModel.forPage(ui.load.config, pages.getOrNull(pager.settledPage)?.id)
                if (titleBar != null) TitleBarRow(titleBar, pages, pager.settledPage, enabled = drag.dragged == null, labelBackdrop = backdrop) { pageList = true }
                else PageLabel(pages, pager.settledPage, enabled = drag.dragged == null, backdrop = backdrop) { pageList = true }
                val listPages = stringResource(R.string.pages_action)
                val openAppList = stringResource(R.string.a11y_open_drawer)
                HorizontalPager(
                    pager, Modifier.weight(1f).fillMaxWidth()
                        // §1.4 "Open app list" and §1.3 "List pages" (≥ 2 pages) custom actions on the home grid.
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(openAppList) { drawerOpen = true; true },
                                if (pages.size >= 2) CustomAccessibilityAction(listPages) { pageList = true; true } else null,
                            )
                        }, key = { pages.getOrNull(it)?.id ?: it },
                    userScrollEnabled = drag.dragged == null,
                ) { index ->
                    pages.getOrNull(index)?.let { PageGrid(it, ui.layout.grid, vm.apps, drag, editing, onOpen, onLong, a11y, onEmptyLong = { homeMenu = true }) }
                }
                if (pages.size > 1) PageDots(pages.size, pager.settledPage)
                // ux.md §1.3: page label → page list → jump. Nothing is written.
                if (pageList) PageListSheet(PageList.rows(pages, pager.settledPage), onPick = { i ->
                    pageList = false
                    if (i != pager.settledPage) scope.launch { if (ReducedMotion.on(ctx)) pager.scrollToPage(i) else pager.animateScrollToPage(i) }
                }, onDismiss = { pageList = false })
                }
                Dock(ui.layout.dock, vm.apps, drag, onOpen, onLong, a11y)
                ui.load.merge?.let { m ->
                    MergeSnackbar(m, onUndo = { vm.store.undoMerge() }.takeIf { m.undo && ui.load.mergeUndo },
                        onDismiss = { vm.store.engine.dismissMergeUndo() })
                }
                if (agentSnack) AgentDocsSnackbar { agentSnack = false }
                undoSnack?.let { u ->
                    UndoSnackbar(u, onUndo = {
                        undoSnack = null
                        // Restore only fails when home has no room left; say so instead of doing nothing (Grace, #70).
                        val cfg = vm.store.engine.state.value.config
                        val undo = u.undo
                        when {
                            undo !is Edit.Restore || Edits.apply(cfg, undo).applied -> vm.store.edit(undo)
                            cfg.allItems().none { it.id == undo.item.id } -> toast(res.getString(R.string.undo_no_room, u.label))
                        }
                    }, onDismiss = { if (undoSnack == u) undoSnack = null })
                }
            }
        }

        cloudUri?.let { uri -> CloudDialog({ cloudUri = null; proceed(uri) }, { cloudUri = null }, { cloudUri = null; pickFolder.launch(null) }) }
        moveAsk?.let { (uri, ins) -> MoveDialog(ins, { moveAsk = null; vm.store.moveToFolder(uri, it) }, { moveAsk = null; vm.store.forgetFolder(uri) }) }
        folderProblem?.let { p -> FolderProblemDialog(p, { folderProblem = null; moving = true; pickFolder.launch(null) }, { folderProblem = null }) }
        if (homePrompt && !isHome) AlertDialog(
            onDismissRequest = { homePrompt = false },
            title = { Text(stringResource(R.string.home_role_title)) },
            text = { Text(stringResource(R.string.home_role_body)) },
            confirmButton = { TextButton({ homePrompt = false; becomeHome() }) { Text(stringResource(R.string.home_role_button)) } },
            dismissButton = { TextButton({ homePrompt = false }) { Text(stringResource(R.string.not_now)) } },
        )

        drawerSheet?.let { app ->
            DrawerAppSheet(app, includeWork = false, onAction = { a ->
                drawerSheet = null
                when (a) {
                    DrawerAction.ADD_TO_HOME -> {
                        val cfg = vm.store.engine.state.value.config
                        val item = AppItem(Edits.newId(cfg, app.packageName), app.packageName, app.activityName,
                            profile = app.profile.takeIf { it == Profile.WORK })
                        if (Edits.apply(cfg, Edit.AddToHome(item)).applied) { drawerOpen = false; vm.store.edit(Edit.AddToHome(item)) }
                        else toast(res.getString(R.string.home_full))
                    }
                    DrawerAction.HIDE -> hideApp(app.packageName, Labels.clean(app.label))
                    DrawerAction.APP_INFO -> if (!vm.apps.appInfo(app.packageName, app.activityName, app.profile)) toast(res.getString(R.string.open_failed, Labels.clean(app.label)))
                }
            }, onDismiss = { drawerSheet = null })
        }

        if (homeMenu) HomeMenuSheet(ui.layout.pages.size, onAction = { a ->
            homeMenu = false
            when (a) {
                HomeAction.EDIT_LAYOUT -> editing = true
                HomeAction.PAGES -> pageList = true
                HomeAction.RELOAD -> vm.store.pollNow()
                // No Settings screen yet: the settings live in the ⋮ menu, so open that (gap, noted).
                HomeAction.SETTINGS -> menuOpen = true
                // #58: system wallpaper chooser; ACTION_SET_WALLPAPER needs no permission.
                HomeAction.WALLPAPER -> runCatching {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER), res.getString(R.string.action_wallpaper)))
                }.onFailure { toast(res.getString(R.string.open_failed, res.getString(R.string.action_wallpaper))) }
            }
        }, onDismiss = { homeMenu = false })

        if (agentConfirm) AgentDocsReplaceDialog(onReplace = { agentConfirm = false; runAgentUpdate(true) }, onCancel = { agentConfirm = false })

        moveFor?.let { r ->
            MoveToDialog(r, ui.layout.pages.map { it.id }, ui.layout.grid, onMove = { pageId, x, y -> moveFor = null; vm.store.edit(Edit.MoveToCell(r.item.id, pageId, x, y)) }, onDismiss = { moveFor = null })
        }

        sheetFor?.let { r ->
            ItemSheet(r, onAction = { a ->
                sheetFor = null
                when (a) {
                    ItemAction.EDIT_LAYOUT -> editing = true
                    ItemAction.APP_INFO -> {
                        val (pkg, act) = when (val i = r.item) { is AppItem -> i.packageName to (r.app?.activityName ?: i.activity); is ShortcutItem -> i.packageName to null; else -> null to null }
                        if (pkg == null || !vm.apps.appInfo(pkg, act, r.app?.profile ?: Profile.PERSONAL)) toast(res.getString(R.string.open_failed, Labels.clean(r.title)))
                    }
                    ItemAction.REMOVE -> removeItem(r)
                }
            }, onDismiss = { sheetFor = null })
        }

        folderInfo?.let { path ->
            AlertDialog(
                onDismissRequest = { folderInfo = null },
                title = { Text(stringResource(R.string.menu_config_folder)) },
                text = {
                    Column {
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(path.ifEmpty { stringResource(R.string.folder_app_only) }) }
                        // ux.md §15: a valid file with warnings says so here; tap → the Warnings list.
                        if (warnings.isNotEmpty()) Text(
                            pluralStringResource(R.plurals.status_loaded_warnings, warnings.size, warnings.size),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { warningsOpen = true }.wrapContentHeight(),
                        )
                    }
                },
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

        if (warningsOpen && warnings.isNotEmpty()) WarningsDialog(warnings, onCopy = { text ->
            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("launcher.json", text))
            toast(res.getString(R.string.copied))
        }, onDismiss = { warningsOpen = false })

        openFolder?.let { folder ->
            AlertDialog(
                onDismissRequest = { openFolder = null },
                confirmButton = { TextButton({ openFolder = null }) { Text(stringResource(R.string.close)) } },
                title = { Text(folder.title) },
                text = {
                    LazyVerticalGrid(GridCells.Fixed(4)) {
                        items(folder.children, key = { it.item.id }) { c ->
                            with(drag) { Tile(c, vm.apps, Modifier.tile(c, canDrag = false, onClick = { openFolder = null; onOpen(c) }, onLongClick = { openFolder = null; onLong(c) }, actions = a11y(c, TilePlace.FOLDER))) }
                        }
                    }
                },
            )
        }

        if (hiddenSheet) {
            val installed by vm.installedApps.collectAsStateWithLifecycle()
            HiddenAppsSheet(HiddenApps.rows(ui.load.config, installed), vm.apps,
                onUnhide = { vm.store.edit(Edit.Unhide(it)) }, onDismiss = { hiddenSheet = false })
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f).padding(start = 12.dp).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { shown.firstOrNull()?.let { drawerOpen = false; launchApp(it) } }),
                )
                // §14 R8 interim: drawer ⋮ → Hidden apps (moves to Settings once that screen exists).
                var drawerMenu by remember { mutableStateOf(false) }
                Box {
                    val more = stringResource(R.string.drawer_menu)
                    TextButton({ drawerMenu = true }, Modifier.semantics { contentDescription = more }) { Text("⋮") }
                    DropdownMenu(drawerMenu, { drawerMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.hidden_apps)) }, { drawerMenu = false; hiddenSheet = true })
                    }
                }
                }
                if (shown.isEmpty()) Text(stringResource(R.string.search_no_match, query.trim()), Modifier.padding(16.dp), color = Color.Gray)
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxHeight(0.85f).padding(8.dp), state = gridState) {
                    items(shown, key = { it.key }) { app ->
                        val ri = ResolvedItem(AppItem("drawer", app.packageName, app.activityName), app)
                        with(drag) { Tile(ri, vm.apps, Modifier.tile(ri, canDrag = false, onClick = { drawerOpen = false; launchApp(app) }, onLongClick = { drawerSheet = app })) }
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
private fun PageGrid(page: ResolvedPage, grid: Grid, apps: InstalledApps, drag: DragController, editing: Boolean, onOpen: (ResolvedItem) -> Unit, onLong: (ResolvedItem) -> Unit, a11y: (ResolvedItem, TilePlace) -> List<CustomAccessibilityAction>, onEmptyLong: () -> Unit) {
    val byCell = page.items.associateBy { (it.item.x ?: 0) to (it.item.y ?: 0) }
    val rows = grid.rows
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        val body: @Composable ColumnScope.() -> Unit = {
            for (y in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (x in 0 until grid.columns) {
                        // §4.1: in edit mode every cell shows a faint outline (white 16 %).
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).then(
                            if (editing) Modifier.border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(8.dp)) else Modifier),
                            contentAlignment = Alignment.Center) {
                            byCell[x to y]?.let { r -> with(drag) { Tile(r, apps, Modifier.tile(r, canDrag = true, onClick = { onOpen(r) }, onLongClick = { onLong(r) }, actions = a11y(r, TilePlace.PAGE))) } }
                        }
                    }
                }
            }
        }
        // §1.1: long-press on empty space opens the home menu. Detected on the whole grid (#75: the gutters between
        // cells count too); a press that lands on an occupied cell is the tile's own long-press.
        val occupied by rememberUpdatedState(byCell.keys)
        val onEmpty by rememberUpdatedState(onEmptyLong)
        Column(Modifier.weight(1f).onGloballyPositioned { drag.gridRect = it.boundsInRoot() }.pointerInput(grid) {
            detectTapGestures(onLongPress = { pos ->
                val cell = GridHit.cell(pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), grid.columns, grid.rows)
                if (cell !in occupied) onEmpty()
            })
        }, content = body)
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    // §1.3: not tappable. §12 "Page 2 of 3", announced politely after a jump.
    val desc = stringResource(R.string.page_of, current + 1, count)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc; liveRegion = LiveRegionMode.Polite }.padding(4.dp),
        horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.padding(3.dp).size(6.dp).background(if (i == current) Color.White else Color.DarkGray, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun Dock(items: List<ResolvedItem>, apps: InstalledApps, drag: DragController, onOpen: (ResolvedItem) -> Unit, onLong: (ResolvedItem) -> Unit, a11y: (ResolvedItem, TilePlace) -> List<CustomAccessibilityAction>) {
    val res = androidx.compose.ui.platform.LocalResources.current
    Row(
        Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 64.dp).onGloballyPositioned { drag.dockRect = it.boundsInRoot() }.background(Color(0x22FFFFFF), RoundedCornerShape(20.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { r -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { with(drag) { Tile(r, apps, Modifier.tile(r, canDrag = true, onClick = { onOpen(r) }, onLongClick = { onLong(r) }, actions = a11y(r, TilePlace.DOCK))
                    .semantics { contentDescription = res.getString(R.string.a11y_dock_item, Labels.clean(r.title)) }, showLabel = false) } } }
        if (items.isEmpty()) Text(stringResource(R.string.dock_empty), color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
fun Tile(r: ResolvedItem, apps: InstalledApps, modifier: Modifier = Modifier, showLabel: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(4.dp).alpha(if (r.missing || r.app?.paused == true) 0.4f else 1f),
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
internal fun AppIconImage(app: InstalledApp?, apps: InstalledApps, sizeDp: Int) {
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
