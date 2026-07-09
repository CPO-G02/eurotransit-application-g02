package it.polito.eurotransit.catalog.repositories

import it.polito.eurotransit.catalog.entities.ProductEntity
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductRepository : CoroutineCrudRepository<ProductEntity, String> {

    fun findAllByOrderByTrainIdAsc(): Flow<ProductEntity>
}
