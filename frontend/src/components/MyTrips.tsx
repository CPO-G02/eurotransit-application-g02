import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import api from '../api/client';
import type { OrderStatusResponse } from '../types/eurotransit';
import './MyTrips.css';

export const MyTrips = () => {
  const { keycloak, initialized } = useKeycloak();
  const [orders, setOrders] = useState<OrderStatusResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!initialized || !keycloak.authenticated) {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    api.get<OrderStatusResponse[]>('/orders')
      .then((response) => setOrders(response.data))
      .catch((err) => setError(err.message || 'An unknown error occurred'))
      .finally(() => setLoading(false));
  }, [initialized, keycloak.authenticated]);

  const formatDate = (dateString?: string) => {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleDateString('ca-ES', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  if (!initialized || loading) return <div className="trips-loading">Loading dashboard...</div>;

  return (
    <div className="trips-container">
      <div className="trips-header">
        <h1 className="trips-title">My Trips</h1>
        <p className="trips-subtitle">Welcome back, {keycloak.tokenParsed?.preferred_username}</p>
      </div>

      {error && (
        <div className="trips-error-banner" style={{ marginBottom: '2rem', color: '#ff4d4d', background: '#2d1a1a', padding: '1rem', borderRadius: '8px', border: '1px solid #ff4d4d' }}>
          {error}
        </div>
      )}

      <div className="trips-section">
        <h2 className="section-title">Your Orders</h2>
        {orders.length === 0 ? (
          <p className="no-trips">No orders found.</p>
        ) : (
          <div className="trips-grid">
            {orders.map(order => (
              <div key={order.order_id} className={`ticket-card ${order.status.toLowerCase()}`}>
                <div className="ticket-top">
                  <span className="ticket-id">{order.order_id}</span>
                  <span className="ticket-badge-class">{order.status}</span>
                </div>
                <div className="ticket-details">
                  <div className="detail-item">
                    <span className="detail-label">TRAIN</span>
                    <span className="detail-value">{order.train_id}</span>
                  </div>
                  <div className="detail-item">
                    <span className="detail-label">CLASS</span>
                    <span className="detail-value">{order.seat_class} x{order.quantity}</span>
                  </div>
                  <div className="detail-item">
                    <span className="detail-label">BOOKED</span>
                    <span className="detail-value">{formatDate(order.created_at)}</span>
                  </div>
                  <div className="detail-item alignment-right">
                    <span className="detail-label">PRICE</span>
                    <span className="detail-value-price">{order.amount} {order.currency}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
