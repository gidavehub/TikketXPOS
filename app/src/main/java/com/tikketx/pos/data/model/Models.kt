package com.tikketx.pos.data.model

import java.io.Serializable

/** Permissions granted to a terminal account (set from Swipe → POS Devices). */
data class TerminalPermissions(
    val addInventory: Boolean = false,
    val viewReports: Boolean = false,
    val allowRefunds: Boolean = false,
) : Serializable {
    companion object {
        fun fromMap(map: Map<*, *>?): TerminalPermissions = TerminalPermissions(
            addInventory = map?.get("add_inventory") == true,
            viewReports = map?.get("view_reports") == true,
            allowRefunds = map?.get("allow_refunds") == true,
        )
    }
}

/**
 * A terminal account, mirrored from RTDB /pos_terminals/<id>.
 *
 * `posDomain` is the company's POS domain (e.g. `bakauhardware.swipe`); the
 * sign-in address is `<username>@<posDomain>`. It replaced the old company code
 * that had to be typed as a third field at login.
 */
data class Terminal(
    val id: String,
    val companyId: String,
    val companyName: String,
    val posDomain: String,
    val terminalName: String,
    val username: String,
    val authEmail: String,
    val authUid: String,
    val role: String,
    val permissions: TerminalPermissions,
    val active: Boolean,
) : Serializable {
    val isManager: Boolean get() = role == "manager"

    /**
     * True when this is a Swipe account signed in directly rather than a
     * provisioned terminal. Such a session has no `pos_terminals` record, so
     * anything that re-reads one has to take a different path.
     */
    val isAccountSession: Boolean get() = id.startsWith("account:")

    companion object {
        fun fromMap(id: String, map: Map<*, *>): Terminal {
            val username = map["username"] as? String ?: ""
            val domain = map["pos_domain"] as? String ?: ""
            return Terminal(
                id = id,
                companyId = map["company_id"] as? String ?: "",
                companyName = map["company_name"] as? String ?: "",
                posDomain = domain,
                terminalName = map["terminal_name"] as? String ?: "Terminal",
                username = username,
                authEmail = map["auth_email"] as? String
                    ?: if (domain.isNotEmpty()) "$username@$domain" else "",
                authUid = map["auth_uid"] as? String ?: "",
                role = map["role"] as? String ?: "cashier",
                permissions = TerminalPermissions.fromMap(map["permissions"] as? Map<*, *>),
                active = map["active"] != false,
            )
        }
    }
}

/**
 * A product from the shared Firestore `products` collection — the same rows the
 * Swipe Inventory page reads and writes. Field names match the platform's
 * snake_case schema exactly; anything renamed here would write a column the web
 * never reads.
 */
data class Product(
    val id: String,
    val companyId: String,
    val name: String,
    val sku: String?,
    val description: String?,
    val category: String?,
    val unitPrice: Double,
    val costPrice: Double,
    val currency: String,
    val currentStock: Double,
    val lowStockThreshold: Double,
    val unit: String,
    val imageUrl: String?,
    val isActive: Boolean,
) : Serializable {

    val isOutOfStock: Boolean get() = currentStock <= 0.0
    val isLowStock: Boolean get() = currentStock > 0.0 && currentStock <= lowStockThreshold

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): Product = Product(
            id = id,
            companyId = map["company_id"] as? String ?: "",
            name = map["name"] as? String ?: "Unnamed",
            sku = map["sku"] as? String,
            description = map["description"] as? String,
            category = map["category"] as? String,
            unitPrice = (map["unit_price"] as? Number)?.toDouble() ?: 0.0,
            costPrice = (map["cost_price"] as? Number)?.toDouble() ?: 0.0,
            currency = map["currency"] as? String ?: "GMD",
            currentStock = (map["current_stock"] as? Number)?.toDouble() ?: 0.0,
            lowStockThreshold = (map["low_stock_threshold"] as? Number)?.toDouble() ?: 0.0,
            unit = map["unit"] as? String ?: "pcs",
            imageUrl = map["image_url"] as? String,
            isActive = map["is_active"] != false,
        )
    }
}

/**
 * Direction each movement type pushes stock — the Kotlin twin of `stockDelta`
 * in the web's src/lib/inventoryLedger.ts. `quantity` is always stored as a
 * positive magnitude and the type carries the sign, so a "sale" of 5 reads as 5
 * on both ends and the ledger applies the direction when it replays.
 */
enum class StockMovementType(val wire: String) {
    STOCK_IN("stock_in"),
    SALE("sale"),
    ADJUSTMENT("adjustment"),
    RETURN("return");

    /** `adjustment` is the one signed type: a stock-take corrects either way. */
    fun delta(quantity: Double): Double = when (this) {
        STOCK_IN, RETURN -> kotlin.math.abs(quantity)
        SALE -> -kotlin.math.abs(quantity)
        ADJUSTMENT -> quantity
    }

    companion object {
        fun fromWire(value: String?): StockMovementType =
            entries.firstOrNull { it.wire == value } ?: ADJUSTMENT
    }
}

/** One row of the shared `stock_movements` audit trail. */
data class StockMovement(
    val id: String,
    val productId: String,
    val type: StockMovementType,
    val quantity: Double,
    val unitPrice: Double?,
    val totalAmount: Double?,
    val notes: String?,
    val reference: String?,
    val createdAt: String,
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): StockMovement = StockMovement(
            id = id,
            productId = map["product_id"] as? String ?: "",
            type = StockMovementType.fromWire(map["type"] as? String),
            quantity = (map["quantity"] as? Number)?.toDouble() ?: 0.0,
            unitPrice = (map["unit_price"] as? Number)?.toDouble(),
            totalAmount = (map["total_amount"] as? Number)?.toDouble(),
            notes = map["notes"] as? String,
            reference = map["reference"] as? String,
            createdAt = map["created_at"] as? String ?: "",
        )
    }
}

/** One requested change to what's on the shelf. */
data class StockChange(
    val productId: String,
    val type: StockMovementType,
    val quantity: Double,
    val unitPrice: Double? = null,
    val totalAmount: Double? = null,
    val notes: String? = null,
    /** Links the movement back to what caused it, e.g. a sale number. */
    val reference: String? = null,
)

/** A tax rate from the shared `tax_rates` catalog. */
data class TaxRate(
    val id: String,
    val name: String,
    val rate: Double,
    val isActive: Boolean,
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): TaxRate = TaxRate(
            id = id,
            name = map["name"] as? String ?: "Tax",
            rate = (map["rate"] as? Number)?.toDouble() ?: 0.0,
            isActive = map["is_active"] != false,
        )
    }
}

/** Payment methods, matching the options on the web register. */
enum class PaymentMethod(val wire: String, val label: String) {
    CASH("cash", "Cash"),
    CARD("card", "Card"),
    MOBILE_MONEY("mobile_money", "Mobile Money"),
    CREDIT("credit", "Credit");

    companion object {
        fun fromWire(value: String?): PaymentMethod =
            entries.firstOrNull { it.wire == value } ?: CASH
    }
}

/** One line in the cart. Discount is an amount off the line, as on the web. */
data class CartItem(
    val product: Product,
    var quantity: Double = 1.0,
    var discount: Double = 0.0,
) {
    val grossTotal: Double get() = quantity * product.unitPrice
    val lineTotal: Double get() = (grossTotal - discount).coerceAtLeast(0.0)
}

/** A completed sale — what we hand to the receipt renderer/printer. */
data class CompletedSale(
    val saleId: String,
    val saleNumber: String,
    val items: List<CartItem>,
    val subtotal: Double,
    val discountTotal: Double,
    val taxLabel: String?,
    val taxAmount: Double,
    val total: Double,
    val paymentMethod: PaymentMethod,
    val amountTendered: Double,
    val changeGiven: Double,
    val customerName: String?,
    val cashierName: String,
    val companyName: String,
    val createdAt: String,
)

/** A sale already on the books — the returns flow refunds against these. */
data class PosSale(
    val id: String,
    val saleNumber: String,
    val total: Double,
    val currency: String,
    val paymentMethod: String,
    val customerName: String?,
    val status: String,
    val createdAt: String,
    val items: List<PosSaleItem> = emptyList(),
) {
    val isReturned: Boolean get() = status == "returned"

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): PosSale = PosSale(
            id = id,
            saleNumber = map["sale_number"] as? String ?: "—",
            total = (map["total"] as? Number)?.toDouble() ?: 0.0,
            currency = map["currency"] as? String ?: "GMD",
            paymentMethod = map["payment_method"] as? String ?: "cash",
            customerName = map["customer_name"] as? String,
            status = map["status"] as? String ?: "completed",
            createdAt = map["created_at"] as? String ?: "",
        )
    }
}

/** One line of a sale already on the books. */
data class PosSaleItem(
    val id: String,
    val saleId: String,
    val productId: String?,
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val discount: Double,
    val total: Double,
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): PosSaleItem = PosSaleItem(
            id = id,
            saleId = map["sale_id"] as? String ?: "",
            productId = map["product_id"] as? String,
            productName = map["product_name"] as? String ?: "Product",
            quantity = (map["quantity"] as? Number)?.toDouble() ?: 0.0,
            unitPrice = (map["unit_price"] as? Number)?.toDouble() ?: 0.0,
            discount = (map["discount"] as? Number)?.toDouble() ?: 0.0,
            total = (map["total"] as? Number)?.toDouble() ?: 0.0,
        )
    }
}

/** The business details printed on receipts, from Firestore `companies`. */
data class CompanyProfile(
    val id: String,
    val name: String,
    val address: String?,
    val phone: String?,
    val email: String?,
    val logoUrl: String?,
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): CompanyProfile = CompanyProfile(
            id = id,
            name = map["name"] as? String ?: "My Business",
            address = map["address"] as? String,
            phone = map["phone"] as? String,
            email = map["email"] as? String,
            logoUrl = map["logo_url"] as? String,
        )
    }
}

/** Today's numbers for the Reports screen + live heartbeat. */
data class DayStats(
    val salesTotal: Double = 0.0,
    val transactions: Int = 0,
    val itemsSold: Double = 0.0,
    val cashTotal: Double = 0.0,
    val nonCashTotal: Double = 0.0,
)

/**
 * A remote scan request from the Swipe dashboard (Inventory → Add Product →
 * Scan). Mirrored from RTDB pos_scan_requests/<companyId>/<id>; this terminal
 * answers by scanning a barcode and writing the code back.
 */
data class ScanRequest(
    val id: String,
    val status: String,
    val targetTerminalId: String?,
    val purpose: String,
    val requestedAt: String?,
    val claimedBy: String?,
) {
    companion object {
        fun fromMap(id: String, map: Map<*, *>): ScanRequest = ScanRequest(
            id = id,
            status = map["status"] as? String ?: "pending",
            targetTerminalId = map["target_terminal_id"] as? String,
            purpose = map["purpose"] as? String ?: "barcode",
            requestedAt = map["requested_at"] as? String,
            claimedBy = map["claimed_by"] as? String,
        )
    }
}
