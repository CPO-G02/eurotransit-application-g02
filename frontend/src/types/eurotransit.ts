export interface ProductsResponse {
  products: ProductResponse[];
}

export interface ProductResponse {
  trainId: string;
  origin: string;
  destination: string;
  departure: string;
  seatClasses: SeatClassDto[];
}

export interface SeatClassDto {
  seatClass: string;
  price: number;
  currency: string;
  available: number;
}

export interface OrderRequest {
  idempotencyKey: string;
  userId: string;
  userEmail: string;
  trainId: string;
  seatClass: string;
  quantity: number;
  amount: number;
  currency: string;
}

export interface OrderResponse {
  orderId: string;
  status: string;
}

export interface OrderStatusResponse {
  orderId: string;
  status: string;
  trainId: string;
  seatClass: string;
  quantity: number;
  amount: number;
  currency: string;
  transactionId?: string;
  createdAt?: string;
  confirmedAt?: string;
}

export interface ErrorResponse {
  error: string;
}

export interface OrderConflictResponse {
  orderId: string;
  status: string;
  message: string;
}