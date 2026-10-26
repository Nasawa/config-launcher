package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * #179 (ux.md §2.1 "Full height, one motion"): the drawer is a primary destination, so it opens straight to full
 * height. `skipPartiallyExpanded` removes the half-height stop altogether, so a swipe up or `Apps` never settles
 * halfway and the rows below the fold aren't hidden until a second drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberAppDrawerState(): SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberAppDrawerState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismiss, sheetState = sheetState, containerColor = containerColor, content = content)
}
