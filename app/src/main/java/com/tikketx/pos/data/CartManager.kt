package com.tikketx.pos.data

import com.tikketx.pos.data.model.CartItem
import com.tikketx.pos.data.model.Product
import com.tikketx.pos.data.model.TaxRate

/**
 * In-memory cart shared between the Sell screen and the checkout sheet.
 *
 * Quantities are capped at what's on the shelf, so the till can't ring up stock
 * that isn't there and leave the count to clamp at zero afterwards. Discounts
 * are an amount off the line, matching the web register.
 */
object CartManager {

    private val items = LinkedHashMap<String, CartItem>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Optional tax applied to the whole sale, chosen at checkout. */
    var taxRate: TaxRate? = null
        set(value) { field = value; notifyChanged() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun list(): List<CartItem> = items.values.toList()

    fun find(productId: String): CartItem? = items[productId]

    val itemCount: Int get() = items.values.sumOf { it.quantity }.toInt()
    val isEmpty: Boolean get() = items.isEmpty()

    val subtotal: Double get() = items.values.sumOf { it.grossTotal }
    val discountTotal: Double get() = items.values.sumOf { it.discount }
    val taxableAmount: Double get() = subtotal - discountTotal
    val taxAmount: Double get() = taxRate?.let { taxableAmount * (it.rate / 100.0) } ?: 0.0
    val total: Double get() = taxableAmount + taxAmount

    /**
     * Adds to the line, or creates it. Returns false when the shelf can't cover
     * the extra quantity, so the caller can say so rather than silently capping.
     */
    fun add(product: Product, quantity: Double = 1.0): Boolean {
        val existing = items[product.id]
        val wanted = (existing?.quantity ?: 0.0) + quantity
        if (wanted > product.currentStock) return false

        if (existing != null) {
            // A price or name edited on the dashboard mid-sale should be the one
            // that gets rung up; copy() carries the line's discount across.
            items[product.id] = existing.copy(product = product, quantity = wanted)
        } else {
            items[product.id] = CartItem(product, quantity)
        }
        notifyChanged()
        return true
    }

    fun setQuantity(productId: String, quantity: Double) {
        val item = items[productId]
        if (item == null) return
        if (quantity <= 0.0) {
            items.remove(productId)
        } else {
            item.quantity = minOf(quantity, item.product.currentStock)
            // Discounting more than the line is worth would make the sale
            // negative; hold it at the line total.
            item.discount = minOf(item.discount, item.grossTotal)
        }
        notifyChanged()
    }

    fun setDiscount(productId: String, discount: Double) {
        val item = items[productId] ?: return
        item.discount = discount.coerceIn(0.0, item.grossTotal)
        notifyChanged()
    }

    fun remove(productId: String) {
        items.remove(productId)
        notifyChanged()
    }

    fun clear() {
        items.clear()
        taxRate = null
        notifyChanged()
    }
}
