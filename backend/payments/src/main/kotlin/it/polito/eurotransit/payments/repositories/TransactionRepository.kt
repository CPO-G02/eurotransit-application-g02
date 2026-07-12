package it.polito.eurotransit.payments.repositories

import it.polito.eurotransit.payments.entities.TransactionEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository : CoroutineCrudRepository<TransactionEntity, Long> {

    suspend fun findByTransactionId(transactionId: String): TransactionEntity?
}
