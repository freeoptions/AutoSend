package com.autosend.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PaginationControls(
    currentPage: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onPageChange((currentPage - 1).coerceAtLeast(0)) },
            enabled = currentPage > 0
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一页")
        }
        Text(
            text = "第 ${currentPage + 1} / $pageCount 页",
            style = MaterialTheme.typography.labelMedium
        )
        IconButton(
            onClick = { onPageChange((currentPage + 1).coerceAtMost(pageCount - 1)) },
            enabled = currentPage < pageCount - 1
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一页")
        }
    }
}
