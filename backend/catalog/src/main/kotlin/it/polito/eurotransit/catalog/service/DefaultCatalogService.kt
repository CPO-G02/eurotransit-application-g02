package it.polito.eurotransit.catalog.service

import it.polito.eurotransit.catalog.dto.ProductResponse
import it.polito.eurotransit.catalog.dto.SeatClassDto
import it.polito.eurotransit.catalog.entities.ProductEntity
import it.polito.eurotransit.catalog.entities.SeatClassEntity
import it.polito.eurotransit.catalog.repositories.ProductRepository
import it.polito.eurotransit.catalog.repositories.SeatClassRepository
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

@Service
class DefaultCatalogService(
    private val productRepository: ProductRepository,
    private val seatClassRepository: SeatClassRepository,
) : CatalogService {

    override suspend fun listProducts(): List<ProductResponse> {
        val seatClassesByTrain = seatClassRepository.findAllByOrderByPriceAsc()
            .toList()
            .groupBy { it.trainId }

        return productRepository.findAllByOrderByTrainIdAsc()
            .toList()
            .map { product -> product.toResponse(seatClassesByTrain[product.trainId].orEmpty()) }
    }

    override suspend fun getProduct(trainId: String): ProductResponse? {
        val product = productRepository.findById(trainId) ?: return null
        val seatClasses = seatClassRepository.findByTrainIdOrderByPriceAsc(trainId).toList()
        return product.toResponse(seatClasses)
    }
}

private fun ProductEntity.toResponse(seatClasses: List<SeatClassEntity>): ProductResponse =
    ProductResponse(
        trainId = trainId,
        origin = origin,
        destination = destination,
        departure = departure,
        seatClasses = seatClasses.map { it.toDto() },
    )

private fun SeatClassEntity.toDto(): SeatClassDto =
    SeatClassDto(
        seatClass = seatClass,
        price = price,
        currency = currency,
        available = available,
    )
