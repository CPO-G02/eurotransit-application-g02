package it.polito.eurotransit.catalog.repositories

import it.polito.eurotransit.catalog.entities.SeatClassEntity
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SeatClassRepository : CoroutineCrudRepository<SeatClassEntity, Long> {

    // Ordered by price so the cheaper class (standard) comes before business,
    // giving the API response a deterministic seat-class ordering.
    fun findByTrainIdOrderByPriceAsc(trainId: String): Flow<SeatClassEntity>

    fun findAllByOrderByPriceAsc(): Flow<SeatClassEntity>
}
