// USB-накопитель, подключённый к телефону через OTG.
//
// ПОЧЕМУ ОТДЕЛЬНЫЙ СЛОЙ. На телевизоре флешка — обычная папка (`/storage/<UUID>/…`), и с ней
// работает весь остальной код. На телефоне (проверено на Galaxy S10+, Android 11) прошивка
// Samsung отдаёт OTG-накопитель ТОЛЬКО системному файловому менеджеру: тома `/storage/<UUID>`
// не существует вовсе, а `/mnt/media_rw/<UUID>` закрыт правами (группа media_rw) — туда не может
// ни приложение, ни adb. Единственный путь — системный выбор папки: владелец один раз указывает
// накопитель и подтверждает доступ, после чего разрешение сохраняется навсегда, а запись идёт
// через поставщика документов.
//
// ОГРАНИЧЕНИЕ, О КОТОРОМ НАДО ПОМНИТЬ: у такого накопителя нет пути в файловой системе, поэтому
// он годится как ЦЕЛЬ КОПИРОВАНИЯ, но не как медиатека ноды — ни сканирование, ни торрент писать
// туда не могут (нативной библиотеке нужен настоящий путь).
package com.mediacenter.tv.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

object SafStore {
    private const val FILE = "usb-saf.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun treeUri(ctx: Context): Uri? = try {
        val o = JSONObject(file(ctx).readText())
        val u = Uri.parse(o.getString("uri"))
        // Разрешение могли отозвать (или накопитель сменили) — проверяем, что оно ещё наше.
        if (ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }) u else null
    } catch (_: Exception) { null }

    fun label(ctx: Context): String = try {
        JSONObject(file(ctx).readText()).optString("label", "USB-накопитель")
    } catch (_: Exception) { "USB-накопитель" }

    fun save(ctx: Context, uri: Uri, label: String) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { Log.e("saf: не удалось закрепить доступ: ${e.message}") }
        file(ctx).writeText(JSONObject().put("uri", uri.toString()).put("label", label).toString())
        Log.i("saf: накопитель подключён — $label")
    }

    fun forget(ctx: Context) {
        treeUri(ctx)?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        }
        file(ctx).delete()
    }

    // Свободное место: у документа нет пути, поэтому спрашиваем файловую систему через
    // файловый дескриптор самого дерева.
    fun freeBytes(ctx: Context): Long = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri(ctx)!!, DocumentsContract.getTreeDocumentId(treeUri(ctx)!!))
        ctx.contentResolver.openFileDescriptor(root, "r").use { pfd ->
            val st = Os.fstatvfs(pfd!!.fileDescriptor)
            st.f_bavail * st.f_frsize
        }
    } catch (_: Exception) { 0 }

    fun totalBytes(ctx: Context): Long = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri(ctx)!!, DocumentsContract.getTreeDocumentId(treeUri(ctx)!!))
        ctx.contentResolver.openFileDescriptor(root, "r").use { pfd ->
            val st = Os.fstatvfs(pfd!!.fileDescriptor)
            st.f_blocks * st.f_frsize
        }
    } catch (_: Exception) { 0 }

    // --- работа с деревом ---------------------------------------------------------------------
    // Ищем документ по имени среди детей; null — если нет.
    private fun childId(ctx: Context, parentId: String, name: String): String? {
        val tree = treeUri(ctx) ?: return null
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun ensureDir(ctx: Context, parentId: String, name: String): String? {
        childId(ctx, parentId, name)?.let { return it }
        val tree = treeUri(ctx) ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return try {
            DocumentsContract.createDocument(ctx.contentResolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
        } catch (e: Exception) { Log.e("saf: не создать папку $name: ${e.message}"); null }
    }

    // Готовим файл по относительному пути ("Movies/Кино (2020).mkv") и отдаём поток на запись.
    // Существующий файл с тем же именем удаляем: половинчатый остаток от прошлой попытки
    // хуже, чем перезапись.
    fun openForWrite(ctx: Context, relPath: String): OutputStream? {
        val tree = treeUri(ctx) ?: return null
        var dirId = DocumentsContract.getTreeDocumentId(tree)
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) {
            dirId = ensureDir(ctx, dirId, parts[i]) ?: return null
        }
        val name = parts.last()
        childId(ctx, dirId, name)?.let { existing ->
            try { DocumentsContract.deleteDocument(ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, existing)) } catch (_: Exception) {}
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val doc = try {
            DocumentsContract.createDocument(ctx.contentResolver, parent, "video/*", name)
        } catch (e: Exception) { Log.e("saf: не создать файл $name: ${e.message}"); null } ?: return null
        return try { ctx.contentResolver.openOutputStream(doc) } catch (e: Exception) {
            Log.e("saf: не открыть на запись $name: ${e.message}"); null
        }
    }

    // Размер уже лежащего файла (0 — если его нет): по нему пропускаем то, что скопировано.
    fun sizeOf(ctx: Context, relPath: String): Long {
        val tree = treeUri(ctx) ?: return 0
        var dirId = DocumentsContract.getTreeDocumentId(tree)
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) dirId = childId(ctx, dirId, parts[i]) ?: return 0
        val id = childId(ctx, dirId, parts.last()) ?: return 0
        return try {
            ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0
            } ?: 0
        } catch (_: Exception) { 0 }
    }

    fun view(ctx: Context): JSONObject? {
        val u = treeUri(ctx) ?: return null
        return JSONObject()
            .put("id", "usb-saf").put("label", label(ctx))
            .put("removable", true).put("saf", true)
            .put("totalBytes", totalBytes(ctx)).put("freeBytes", freeBytes(ctx))
            .put("uri", u.toString())
    }
}
