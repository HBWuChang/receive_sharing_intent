package com.kasem.receive_sharing_intent

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.*
import android.webkit.MimeTypeMap
import android.util.Log


object FileDirectory {

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @author paulburke
     */
    fun getAbsolutePath(context: Context, uri: Uri): String? {

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                return if ("primary".equals(type, ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                } else {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isDownloadsDocument(uri)) {
                return try {
                    val id = DocumentsContract.getDocumentId(uri)
                    val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))

                    getDataColumn(context, contentUri, null, null)
                } catch (exception: Exception) {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                var contentUri: Uri? = null
                when (type) {
                    "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                if (contentUri == null) return null

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }// MediaProvider
            // DownloadsProvider
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        }

        return uri.path
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private fun getDataColumn(context: Context, uri: Uri, selection: String?,
                              selectionArgs: Array<String>?): String? {

        if (uri.authority != null) {
            var cursor: Cursor? = null
            val column = "_display_name"
            val projection = arrayOf(column)
            var targetFile: File? = null
            try {
                cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    val fileName = cursor.getString(columnIndex)
                    Log.i("FileDirectory", "File name: $fileName")
                    targetFile = File(context.cacheDir, fileName)
                }
            } finally {
                cursor?.close()
            }

            if (targetFile == null) {
                val mimeType = context.contentResolver.getType(uri)
                val prefix = with(mimeType ?: "") {
                    when {
                        startsWith("image") -> "IMG"
                        startsWith("video") -> "VID"
                        else -> "FILE"
                    }
                }
                val type = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                targetFile = File(context.cacheDir, "${prefix}_${Date().time}.$type")
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { fileOut ->
                        input.copyTo(fileOut)
                    }
                }
            } catch (e: Exception) {
                Log.e("FileDirectory", "Failed to open input stream for uri: $uri", e)
                // Fallback: many apps use private FileProviders whose content URI
                // embeds the real file path but cannot be resolved externally.
                // Try multiple strategies to reconstruct the path.
                try {
                    val resolvedFile = resolveFileFromUriPath(uri)
                    if (resolvedFile != null) {
                        Log.i("FileDirectory", "Fallback: resolved real path ${resolvedFile.absolutePath}")
                        resolvedFile.inputStream().use { input ->
                            FileOutputStream(targetFile).use { fileOut ->
                                input.copyTo(fileOut)
                            }
                        }
                        return targetFile.path
                    }
                } catch (e2: Exception) {
                    Log.e("FileDirectory", "Fallback also failed for uri: $uri", e2)
                }
                return null
            }
            return targetFile.path
        }

        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } finally {
            cursor?.close()
        }
        return null
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * Resolve a real file path from a content URI's path segment.
     *
     * Many apps (QQ, MT Manager, MIUI gallery) use private FileProviders;
     * when [openInputStream] on such URIs fails, the real file path is often
     * embedded in [uri.path] but prefixed with a virtual directory name
     * like `/external_files/`, `/root/`, `/external/`, etc.
     *
     * Strategies tried (in order):
     *  1. Use [uri.path] as-is (works for e.g. MT Manager).
     *  2. Strip the first path segment (e.g. `/external_files/rest` → `/rest`).
     *  3. Strip first segment + prepend external storage directory.
     */
    private fun resolveFileFromUriPath(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null

        // Strategy 1: raw path
        val rawFile = File(uri.path ?: "")
        if (rawFile.exists()) return rawFile

        if (segments.size < 2) return null

        // Path without the first virtual segment (e.g. remove /external_files/)
        val restPath = segments.subList(1, segments.size).joinToString("/")

        // Strategy 2: treat rest as absolute (root-based) path
        val absFile = File("/$restPath")
        if (absFile.exists()) return absFile

        // Strategy 3: prepend external storage root
        val extFile = File(Environment.getExternalStorageDirectory(), restPath)
        if (extFile.exists()) return extFile

        return null
    }
}