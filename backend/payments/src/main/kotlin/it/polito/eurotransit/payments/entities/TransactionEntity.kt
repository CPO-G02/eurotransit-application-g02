package it.polito.eurotransit.payments.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal

@Table("transactions")
data class TransactionEntity(
    @Id val id: Long? = null,
    @Column("transaction_id") val transactionId: String,
    @Column("order_id") val orderId: String,
    @Column("user_id") val userId: String,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val reason: String? = null,
)
