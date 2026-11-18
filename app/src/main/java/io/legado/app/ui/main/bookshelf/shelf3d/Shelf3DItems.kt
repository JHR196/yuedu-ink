package io.legado.app.ui.main.bookshelf.shelf3d

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import android.content.Context
import splitties.init.appCtx

/**
 * 书架数据到立体书架展示项的单向映射，以及按分组记住的焦点书
 */
object Shelf3DItems {

    private val lastKeys = HashMap<Long, String>()

    /** 独立的小文件，只存「分组 -> 焦点 bookUrl」，不进入原有配置与备份 */
    private val prefs by lazy { appCtx.getSharedPreferences("shelf3d_focus", Context.MODE_PRIVATE) }

    fun lastKey(groupId: Long): String? =
        lastKeys[groupId] ?: prefs.getString(groupId.toString(), null)?.also { lastKeys[groupId] = it }

    fun remember(groupId: Long, key: String?) {
        if (key == null || lastKeys[groupId] == key) return
        lastKeys[groupId] = key
        prefs.edit().putString(groupId.toString(), key).apply()
    }

    fun of(any: Any): Shelf3DView.Item? = when (any) {
        is Book -> Shelf3DView.Item(
            key = any.bookUrl,
            name = any.name,
            author = any.author,
            cover = any.getDisplayCover(),
            origin = any.origin,
            info = any.durChapterTitle?.takeIf { it.isNotBlank() }?.let { "读至 $it" }
                ?: if (any.totalChapterNum > 0) "共 ${any.totalChapterNum} 章" else "",
            data = any,
            source = if (any.isLocal) "本地" else any.originName,
            progress = if (any.totalChapterNum > 0)
                ((any.durChapterIndex + 1f) / any.totalChapterNum).coerceIn(0f, 1f) else -1f
        )

        is BookGroup -> Shelf3DView.Item(
            key = "group:" + any.groupId,
            name = any.groupName,
            author = "",
            cover = any.cover,
            origin = null,
            info = "分组",
            data = any
        )

        else -> null
    }

    fun of(list: List<Any>): List<Shelf3DView.Item> = list.mapNotNull { of(it) }
}
