package it.polito.eurotransit.catalog.repositories

import it.polito.eurotransit.catalog.entities.SeatClassEntity
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SeatClassRepository : CoroutineCrudRepository<SeatClassEntity, Long> {

    fun findByTrainIdOrderByPriceAsc(trainId: String): Flow<SeatClassEntity>

    fun findAllByOrderByPriceAsc(): Flow<SeatClassEntity>
}
