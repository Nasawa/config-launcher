package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.ResolvedItem

/**
 * §1.5 end-edge dock rail: `Apps`/`Done` and `⋮` at the head (TalkBack first), a 1 dp divider, then
 * `dock[]` top → bottom. Sits inside the caller's safeDrawing padding; Row place puts it on the end
 * edge in both LTR and RTL. Width is at least 80 dp, or wider when [head] needs it for the label.
 */
@Composable
internal fun DockRail(
    items: List<ResolvedItem>,
    apps: InstalledApps,
    drag: DragController,
    onOpen: (ResolvedItem) -> Unit,
    onLong: (ResolvedItem) -> Unit,
    a11y: (ResolvedItem, TilePlace) -> List<CustomAccessibilityAction>,
    /** Measured width of the Apps/Done label at the current font; rail grows past 80 dp when needed. */
    headLabelWidthDp: Float = 0f,
    head: @Composable () -> Unit,
) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val widthDp = HomeGeometry.railWidthDp(headLabelWidthDp)
    val scroll = rememberScrollState()
    Column(
        Modifier
            .testTag("dockRail")
            .width(widthDp.dp)
            .widthIn(min = HomeGeometry.RAIL_WIDTH_DP.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // TalkBack: rail head first (§1.5), then the page column (1), then dock items (2).
        Box(Modifier.semantics { traversalIndex = 0f }) { head() }
        HorizontalDivider(
            Modifier.padding(vertical = 6.dp).fillMaxWidth(),
            thickness = 1.dp,
            color = Color.White.copy(alpha = 0.24f),
        )
        BoxWithRailSlots(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { traversalIndex = 2f }
                .onGloballyPositioned { drag.dockRect = it.boundsInRoot() },
            itemCount = items.size,
        ) { slotDp, iconDp, scrollable ->
            val colMod = Modifier.fillMaxWidth().then(
                if (scrollable) Modifier.verticalScroll(scroll) else Modifier,
            )
            Column(colMod, horizontalAlignment = Alignment.CenterHorizontally) {
                items.forEach { r ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(slotDp.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        with(drag) {
                            Tile(
                                r, apps,
                                Modifier
                                    .size(slotDp.dp)
                                    .tile(
                                        r, canDrag = true,
                                        onClick = { onOpen(r) },
                                        onLongClick = { onLong(r) },
                                        actions = a11y(r, TilePlace.DOCK),
                                    )
                                    .semantics {
                                        contentDescription = res.getString(R.string.a11y_dock_item, Labels.clean(r.title))
                                    },
                                showLabel = false,
                                iconDp = iconDp,
                            )
                        }
                    }
                }
                if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.dock_empty),
                        color = Color.Gray,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Measures the slot column and picks 64 → 48 → scroll per §1.5.
 */
@Composable
private fun BoxWithRailSlots(
    modifier: Modifier,
    itemCount: Int,
    content: @Composable (slotDp: Float, iconDp: Int, scrollable: Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val avail = maxHeight.value
        val slot = HomeGeometry.railSlotDp(itemCount, avail)
        val scrollable = HomeGeometry.needsRailScroll(itemCount, avail)
        content(slot, HomeGeometry.railIconDp(slot), scrollable)
    }
}

/**
 * Centres [content] at [contentWidthDp] inside the parent on Medium/Expanded (§1.5).
 * Pass the page-column width into [HomeGeometry.contentWidthDp] (window minus the rail), not the
 * raw window width. Compact callers pass null and get a full-width child.
 */
@Composable
internal fun ContentWidthBox(
    contentWidthDp: Float?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (contentWidthDp != null && contentWidthDp > 0f) {
        Box(modifier.fillMaxWidth().testTag("contentWidth"), contentAlignment = Alignment.Center) {
            Box(Modifier.width(contentWidthDp.dp).testTag("contentWidthInner")) { content() }
        }
    } else {
        Box(modifier.fillMaxWidth()) { content() }
    }
}

/**
 * Bottom dock at the content width on Medium/Expanded (§1.5); full width on Compact.
 * Keeps the existing [Dock] look; centres when [contentWidthDp] is below the parent width.
 */
@Composable
internal fun BottomDock(
    items: List<ResolvedItem>,
    apps: InstalledApps,
    drag: DragController,
    onOpen: (ResolvedItem) -> Unit,
    onLong: (ResolvedItem) -> Unit,
    a11y: (ResolvedItem, TilePlace) -> List<CustomAccessibilityAction>,
    contentWidthDp: Float? = null,
) {
    ContentWidthBox(contentWidthDp) {
        Dock(items, apps, drag, onOpen, onLong, a11y)
    }
}
