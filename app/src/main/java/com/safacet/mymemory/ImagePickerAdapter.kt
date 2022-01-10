package com.safacet.mymemory

import android.app.ActionBar
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.recyclerview.widget.RecyclerView
import com.safacet.mymemory.models.BoardSize
import kotlin.math.min

class ImagePickerAdapter(private val context: Context,
                         private val imageUris: List<Uri>,
                         private val boardSize: BoardSize,
                         private val imageClickListener: ImageClickListener
)
    : RecyclerView.Adapter<ImagePickerAdapter.ViewHolder>() {

    companion object {
        const val MARGIN_SIZE = 4
    }

    interface ImageClickListener {
        fun onPlaceHolderClicked()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewWidth = (parent.width / boardSize.getWidth()) - 2* MARGIN_SIZE
        val viewHeight = parent.height / boardSize.getHeight() - 2* MARGIN_SIZE
        val viewSideLength = min(viewWidth, viewHeight)

        val view = LayoutInflater.from(context).inflate(R.layout.card_image, parent, false)

        val layoutParams = view.findViewById<ImageView>(R.id.ivCustomImage).layoutParams
        layoutParams.width = viewSideLength
        layoutParams.height = viewSideLength

        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < imageUris.size) {
            holder.bind(imageUris[position])
        } else {
            holder.bind()
        }
    }

    override fun getItemCount(): Int = boardSize.getNumPairs()

    inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        private val ivCustomImage = itemView.findViewById<ImageView>(R.id.ivCustomImage)

        fun bind(uri:Uri) {
            ivCustomImage.setImageURI(uri)
            ivCustomImage.setOnClickListener(null)
        }

        fun bind() {
            ivCustomImage.setOnClickListener{
                imageClickListener.onPlaceHolderClicked()
            }
        }

    }
}