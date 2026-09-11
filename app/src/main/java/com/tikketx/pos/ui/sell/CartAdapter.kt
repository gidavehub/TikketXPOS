package com.tikketx.pos.ui.sell

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tikketx.pos.data.CartManager
import com.tikketx.pos.data.SwipeBackend
import com.tikketx.pos.data.model.CartItem
import com.tikketx.pos.databinding.ItemCartBinding

class CartAdapter : RecyclerView.Adapter<CartAdapter.Holder>() {

    private var items: List<CartItem> = CartManager.list()

    fun refresh() {
        items = CartManager.list()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemCartBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * Held so the watcher can be detached before the row is rebound.
         * Without that, setText() during a rebind fires the previous row's
         * watcher and writes one line's discount onto another.
         */
        private var discountWatcher: TextWatcher? = null

        fun bind(item: CartItem) {
            binding.cartItemName.text = item.product.name
            binding.cartItemPrice.text = SwipeBackend.fmtMoney(item.lineTotal)
            binding.cartItemQty.text = SwipeBackend.fmtQty(item.quantity)

            binding.btnPlus.setOnClickListener {
                CartManager.setQuantity(item.product.id, item.quantity + 1)
            }
            binding.btnMinus.setOnClickListener {
                CartManager.setQuantity(item.product.id, item.quantity - 1)
            }
            binding.btnRemoveLine.setOnClickListener {
                CartManager.remove(item.product.id)
            }

            discountWatcher?.let { binding.inputLineDiscount.removeTextChangedListener(it) }
            val shown = if (item.discount > 0.0) SwipeBackend.trimNumber(item.discount) else ""
            if (binding.inputLineDiscount.text.toString() != shown) {
                binding.inputLineDiscount.setText(shown)
            }
            discountWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val entered = s?.toString()?.toDoubleOrNull() ?: 0.0
                    if (entered != item.discount) CartManager.setDiscount(item.product.id, entered)
                }
            }
            binding.inputLineDiscount.addTextChangedListener(discountWatcher)
        }
    }
}
