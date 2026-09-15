package com.autosend.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autosend.ui.theme.AutoSendColors

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
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onPageChange((currentPage - 1).coerceAtLeast(0)) },
            enabled = currentPage > 0
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一页")
        }
        var pageMenuExpanded by remember { mutableStateOf(false) }
        Box {
            Surface(
                modifier = Modifier.clickable { pageMenuExpanded = true },
                shape = RoundedCornerShape(12.dp),
                color = AutoSendColors.blueTint.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, AutoSendColors.line),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "第 ${currentPage + 1} / $pageCount 页",
                        style = MaterialTheme.typography.labelMedium,
                        color = AutoSendColors.blue
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "选择页码",
                        tint = AutoSendColors.blue
                    )
                }
            }
            DropdownMenu(
                expanded = pageMenuExpanded,
                onDismissRequest = { pageMenuExpanded = false },
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                repeat(pageCount) { page ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "第 ${page + 1} 页",
                                color = if (page == currentPage) {
                                    AutoSendColors.blue
                                } else {
                                    AutoSendColors.ink
                                }
                            )
                        },
                        leadingIcon = if (page == currentPage) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AutoSendColors.blue
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            onPageChange(page)
                            pageMenuExpanded = false
                        }
                    )
                }
            }
        }
        IconButton(
            onClick = { onPageChange((currentPage + 1).coerceAtMost(pageCount - 1)) },
            enabled = currentPage < pageCount - 1
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一页")
        }
    }
}
