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

    // Накопителей может быть несколько (флешку меняют, или их две через хаб), поэтому храним
    // список выданных разрешений. Ключ — UUID тома из идентификатора дерева: он же стоит на
    // самой флешке, так что после перевтыкания разрешение находится само.
    fun list(ctx: Context): List<JSONObject> {
        val raw = try { file(ctx).readText() } catch (_: Exception) { return emptyList() }
        val arr = try {
            org.json.JSONArray(raw)
        } catch (_: Exception) {
            // Раньше хранили один накопитель объектом — переводим в список, не теряя выданный
            // доступ (само разрешение живёт в системе и переживает обновление приложения).
            try {
                val o = JSONObject(raw)
                val u = Uri.parse(o.optString("uri"))
                org.json.JSONArray().put(JSONObject()
                    .put("id", idOf(u)).put("uri", o.optString("uri"))
                    .put("label", o.optString("label", "USB-накопитель")))
            } catch (_: Exception) { return emptyList() }
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { o ->
            val u = Uri.parse(o.optString("uri"))
            ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
        }
    }

    fun idOf(uri: Uri): String = try {
        "usb-" + DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
    } catch (_: Exception) { "usb" }

    fun uriById(ctx: Context, id: String): Uri? =
        list(ctx).firstOrNull { it.optString("id") == id }?.let { Uri.parse(it.optString("uri")) }

    fun labelById(ctx: Context, id: String): String =
        list(ctx).firstOrNull { it.optString("id") == id }?.optString("label") ?: "USB-накопитель"

    fun save(ctx: Context, uri: Uri, label: String) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { Log.e("saf: не удалось закрепить доступ: ${e.message}") }
        val id = idOf(uri)
        val kept = list(ctx).filter { it.optString("id") != id }
        val arr = org.json.JSONArray()
        kept.forEach { arr.put(it) }
        arr.put(JSONObject().put("id", id).put("uri", uri.toString()).put("label", label))
        file(ctx).writeText(arr.toString())
        Log.i("saf: накопитель подключён — $label ($id)")
    }

    fun forget(ctx: Context, id: String) {
        uriById(ctx, id)?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        }
        val arr = org.json.JSONArray()
        list(ctx).filter { it.optString("id") != id }.forEach { arr.put(it) }
        file(ctx).writeText(arr.toString())
    }

    // Свободное место: у документа нет пути, поэтому спрашиваем файловую систему через
    // файловый дескриптор самого дерева.
    fun freeBytes(ctx: Context, tree: Uri): Long = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.openFileDescriptor(root, "r").use { pfd ->
            val st = Os.fstatvfs(pfd!!.fileDescriptor)
            st.f_bavail * st.f_frsize
        }
    } catch (_: Exception) { 0 }

    fun totalBytes(ctx: Context, tree: Uri): Long = try {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.openFileDescriptor(root, "r").use { pfd ->
            val st = Os.fstatvfs(pfd!!.fileDescriptor)
            st.f_blocks * st.f_frsize
        }
    } catch (_: Exception) { 0 }

    // --- работа с деревом ---------------------------------------------------------------------
    // Ищем документ по имени среди детей; null — если нет.
    private fun childId(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun ensureDir(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        childId(ctx, tree, parentId, name)?.let { return it }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return try {
            DocumentsContract.createDocument(ctx.contentResolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
        } catch (e: Exception) { Log.e("saf: не создать папку $name: ${e.message}"); null }
    }

    // Готовим файл по относительному пути ("Movies/Кино (2020).mkv") и отдаём поток на запись.
    // Существующий файл с тем же именем удаляем: половинчатый остаток от прошлой попытки
    // хуже, чем перезапись.
    fun openForWrite(ctx: Context, tree: Uri, relPath: String): OutputStream? {
        var dirId = DocumentsContract.getTreeDocumentId(tree)
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) {
            dirId = ensureDir(ctx, tree, dirId, parts[i]) ?: return null
        }
        val name = parts.last()
        childId(ctx, tree, dirId, name)?.let { existing ->
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

    // Чтение уже лежащего на носителе файла — нужно, чтобы сверить записанное с исходником.
    fun openForRead(ctx: Context, tree: Uri, relPath: String): java.io.InputStream? {
        var dirId = DocumentsContract.getTreeDocumentId(tree)
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) dirId = childId(ctx, tree, dirId, parts[i]) ?: return null
        val id = childId(ctx, tree, dirId, parts.last()) ?: return null
        return try {
            ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
        } catch (_: Exception) { null }
    }

    // Размер уже лежащего файла (0 — если его нет): по нему пропускаем то, что скопировано.
    fun sizeOf(ctx: Context, tree: Uri, relPath: String): Long {
        var dirId = DocumentsContract.getTreeDocumentId(tree)
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 1) dirId = childId(ctx, tree, dirId, parts[i]) ?: return 0
        val id = childId(ctx, tree, dirId, parts.last()) ?: return 0
        return try {
            ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0
            } ?: 0
        } catch (_: Exception) { 0 }
    }

    // Все подключённые накопители — для списка носителей в интерфейсе.
    fun views(ctx: Context): List<JSONObject> = list(ctx).map { o ->
        val u = Uri.parse(o.optString("uri"))
        JSONObject()
            .put("id", o.optString("id")).put("label", o.optString("label"))
            .put("removable", true).put("saf", true)
            .put("totalBytes", totalBytes(ctx, u)).put("freeBytes", freeBytes(ctx, u))
    }
}
