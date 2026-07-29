package com.autotg.ui.components

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberReadableAvatarUri(
    context: Context,
    avatarUri: String?
): State<String?> = produceState<String?>(initialValue = null, context, avatarUri) {
    value = getReadableAvatarUri(context, avatarUri)
}

suspend fun isReadableAvatarUri(
    context: Context,
    avatarUri: String?
): Boolean = getReadableAvatarUri(context, avatarUri) != null

suspend fun isMarkableAvatarUri(
    context: Context,
    avatarUri: String?,
    moveTargetUri: String?
): Boolean {
    if (getReadableAvatarUri(context, avatarUri) == null) return false
    return !isUriInTree(avatarUri, moveTargetUri)
}

fun isUriInTree(
    childUriString: String?,
    treeUriString: String?
): Boolean {
    val childUri = childUriString?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val treeUri = treeUriString?.trim()?.takeIf { it.isNotEmpty() } ?: return false

    return runCatching {
        val childDocumentId = DocumentsContract.getDocumentId(Uri.parse(childUri))
        val treeDocumentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        childDocumentId == treeDocumentId || childDocumentId.startsWith("$treeDocumentId/")
    }.getOrDefault(false)
}

private suspend fun getReadableAvatarUri(
    context: Context,
    avatarUri: String?
): String? {
    val normalizedUri = avatarUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(normalizedUri)
            context.contentResolver.openInputStream(uri)?.use { normalizedUri }
        }.getOrNull()
    }
}
