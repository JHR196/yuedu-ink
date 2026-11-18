package io.legado.app.ui.book.searchContent

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemSearchListBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.hexString


class SearchContentAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<SearchResult, ItemSearchListBinding>(context) {

    val textColor = context.getCompatColor(R.color.ink_text).hexString.substring(2)
    val accentColor = context.accentColor.hexString.substring(2)

    override fun getViewBinding(parent: ViewGroup): ItemSearchListBinding {
        return ItemSearchListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchListBinding,
        item: SearchResult,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = callback.durChapterIndex() == item.chapterIndex
            if (payloads.isEmpty()) {
                val spanned = SpannableString(item.getHtmlCompat(textColor, accentColor))
                val titleEnd = spanned.indexOf('\n').let { if (it < 0) spanned.length else it }
                if (titleEnd > 0 && item.query.isNotBlank()) {
                    spanned.setSpan(TypefaceSpan("serif"), 0, titleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spanned.setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tvSearchResult.text = spanned
                tvSearchResult.paint.isFakeBoldText = isDur
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchListBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.query.isNotBlank()) callback.openSearchResult(it, holder.layoutPosition)
            }
        }
    }

    interface Callback {
        fun openSearchResult(searchResult: SearchResult, index: Int)
        fun durChapterIndex(): Int
    }
}