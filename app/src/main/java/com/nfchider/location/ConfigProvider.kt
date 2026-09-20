package com.nfchider.location

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Fallback config channel for hooked processes when the Xposed framework does
 * not provide remote preferences. Serves the JSON config file written by the
 * module app. Read-only; only reacts to the "get" call method.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "get") return null
        val file = File(context?.filesDir, SimConfigRepository.FILE_NAME)
        val json = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        return Bundle().apply { putString("config", json) }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
