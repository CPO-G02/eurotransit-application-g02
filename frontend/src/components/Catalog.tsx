import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import api from '../api/client';
import type { ProductResponse, ProductsResponse } from '../types/eurotransit';
import './Catalog.css';

export const Catalog = () => {
  const { keycloak, initialized } = useKeycloak();
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<ProductsResponse>('/catalog/products')
      .then((response) => {
        setProducts(response.data.products);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Backend Error:", err);
        setError(`Connection failed: ${err.message}`);
        setLoading(false);
      });
  }, []);

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="catalog-viewport">
      <main className="catalog-content">
        <div className="catalog-title-bar">
          <h1>Available Connections</h1>
          <p>Select your high-speed route across Europe.</p>
          {error && <div className="demo-banner" style={{ color: '#ef4444' }}>{error}</div>}
        </div>

        {loading ? (
          <div className="loading-state">Searching available trains...</div>
        ) : (
          <div className="trains-grid">
            {products.map((train) => {
              const standardPrice = train.seatClasses.find(c => c.seatClass === "Standard")?.price || 0;
              return (
                <div key={train.trainId} className="train-card">
                  <div className="train-card-header">
                    <span className="train-id">{train.trainId}</span>
                    <span className="train-status">ON TIME</span>
                  </div>
                  <div className="train-route">
                    <span className="station-name">{train.origin}</span>
                    <span className="route-arrow">➔</span>
                    <span className="station-name">{train.destination}</span>
                  </div>
                  <div className="train-details">
                    <div className="detail-item">
                      <span className="detail-label">DEPARTURE</span>
                      <span className="detail-value">{formatDate(train.departure)}</span>
                    </div>
                    <div className="detail-item">
                      <span className="detail-label">FROM</span>
                      <span className="price-value">€{standardPrice.toFixed(2)}</span>
                    </div>
                  </div>
                  <button className="btn-book" onClick={() => initialized && keycloak.authenticated ? alert('Booking...') : keycloak.login()}>
                    {initialized && keycloak.authenticated ? "Book Seat" : "Login to Book"}
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
};