package com.tikketx.pos.ui.inventory

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tikketx.pos.R
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.databinding.ItemInventoryBinding

class InventoryAdapter(
    private val onClick: (Product) -> Unit,
) : RecyclerView.Adapter<InventoryAdapter.Holder>() {

    private val items = mutableListOf<Product>()

    fun submit(list: List<Product>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemInventoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemInventoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            val ctx = binding.root.context
            binding.invName.text = product.name
            binding.invSku.text = product.sku ?: product.category ?: "—"
            binding.invPrice.text = SwipeBackend.fmtMoney(product.unitPrice)

            binding.invStock.text = if (product.isOutOfStock) {
                ctx.getString(R.string.out_of_stock)
            } else {
                ctx.getString(
                    R.string.stock_left,
                    SwipeBackend.fmtQty(product.currentStock),
                    product.unit,
                )
            }
            binding.invStock.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (product.isOutOfStock) R.color.tikketx_red else R.color.text_secondary,
                )
            )
            // "Low" only means something while there is still stock to sell;
            // an empty shelf already says so in the line above.
            binding.invLowBadge.visibility = if (product.isLowStock) View.VISIBLE else View.GONE

            if (!product.imageUrl.isNullOrBlank()) {
                Glide.with(binding.invImage)
                    .load(product.imageUrl)
                    .placeholder(R.drawable.bg_product_placeholder)
                    .centerCrop()
                    .into(binding.invImage)
            } else {
                binding.invImage.setImageResource(R.drawable.bg_product_placeholder)
            }

            binding.root.setOnClickListener { onClick(product) }
        }
    }
}
