package com.tikketx.pos.ui.sell

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tikketx.pos.R
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.databinding.ItemProductBinding

class ProductAdapter(
    private val onClick: (Product) -> Unit,
) : RecyclerView.Adapter<ProductAdapter.Holder>() {

    private val items = mutableListOf<Product>()

    fun submit(list: List<Product>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemProductBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            val ctx = binding.root.context
            binding.productName.text = product.name
            binding.productPrice.text = SwipeBackend.fmtMoney(product.unitPrice)
            binding.productStock.text = if (product.isOutOfStock) {
                ctx.getString(R.string.out_of_stock)
            } else {
                ctx.getString(
                    R.string.stock_left,
                    SwipeBackend.fmtQty(product.currentStock),
                    product.unit,
                )
            }
            binding.root.alpha = if (product.isOutOfStock) 0.45f else 1f

            if (!product.imageUrl.isNullOrBlank()) {
                Glide.with(binding.productImage)
                    .load(product.imageUrl)
                    .placeholder(R.drawable.bg_product_placeholder)
                    .centerCrop()
                    .into(binding.productImage)
            } else {
                binding.productImage.setImageResource(R.drawable.bg_product_placeholder)
            }

            binding.root.setOnClickListener { onClick(product) }
        }
    }
}
