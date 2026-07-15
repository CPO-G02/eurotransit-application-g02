// Field names here mirror the backend's actual wire contract (snake_case,
// see backend/catalog/.../dto/CatalogDtos.kt and
// backend/orders/.../dto/OrderRequest.kt's @JsonProperty annotations) -
// not the Kotlin property names.

export interface ProductsResponse {
  products: ProductResponse[];
}

export interface ProductResponse {
  train_id: string;
  origin: string;
  destination: string;
  departure: string;
  seat_classes: SeatClassDto[];
}

export interface SeatClassDto {
  class: string;
  price: number;
  currency: string;
  available: number;
}

export interface OrderRequest {
  idempotency_key: string;
  user_id: string;
  user_email: string;
  train_id: string;
  seat_class: string;
  quantity: number;
  amount: number;
  currency: string;
}

export interface OrderResponse {
  order_id: string;
  status: string;
}

export interface OrderStatusResponse {
  order_id: string;
  status: string;
  train_id: string;
  seat_class: string;
  quantity: number;
  amount: number;
  currency: string;
  transaction_id?: string;
  created_at?: string;
  confirmed_at?: string;
}

export interface ErrorResponse {
  error: string;
}

export interface OrderConflictResponse {
  order_id: string;
  status: string;
  message: string;
}
