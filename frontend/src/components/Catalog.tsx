import { useEffect, useState } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { ProductResponse, ProductsResponse } from '../types/eurotransit';
import './Catalog.css';

export const Catalog = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [departureDate, setDepartureDate] = useState('');
  const [passengers, setPassengers] = useState('1');

  useEffect(() => {
    api.get<ProductsResponse>('/catalog/products')
      .then((response) => {
        setProducts(response.data.products);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const filteredProducts = products.filter(p => {
    const matchesOrigin = p.origin.toLowerCase().includes(origin.toLowerCase());
    const matchesDestination = p.destination.toLowerCase().includes(destination.toLowerCase());
    
    let matchesDate = true;
    if (departureDate) {
      const productDate = p.departure.split('T')[0];
      matchesDate = productDate === departureDate;
    }

    return matchesOrigin && matchesDestination && matchesDate;
  });

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  const handleBookClick = (train: ProductResponse, standardPrice: number) => {
    if (!initialized || !keycloak.authenticated) {
      keycloak.login();
      return;
    }
    const params = new URLSearchParams({
      origin: train.origin,
      destination: train.destination,
      departure: train.departure,
      passengers: passengers,
      price: standardPrice.toString()
    });
    navigate(`/checkout/${train.trainId}?${params.toString()}`);
  };

  return (
    <div className="catalog-viewport">
      <main className="catalog-content">
        
        <div className="search-widget-card">
          <div className="search-widget-header">
            <h1>Where would you like to go?</h1>
            <p>Book high-speed trains across Europe instantly.</p>
          </div>
          
          <div className="search-form-grid">
            <div className="input-group">
              <label>Origin</label>
              <input type="text" placeholder="e.g. Milano" value={origin} onChange={(e) => setOrigin(e.target.value)} />
            </div>

            <div className="input-group">
              <label>Destination</label>
              <input type="text" placeholder="e.g. Roma" value={destination} onChange={(e) => setDestination(e.target.value)} />
            </div>

            <div className="input-group">
              <label>Departure Date</label>
              <input type="date" value={departureDate} onChange={(e) => setDepartureDate(e.target.value)} />
            </div>

            <div className="input-group">
              <label>Passengers</label>
              <select value={passengers} onChange={(e) => setPassengers(e.target.value)}>
                <option value="1">1 Passenger</option>
                <option value="2">2 Passengers</option>
                <option value="3">3 Passengers</option>
                <option value="4">4 Passengers</option>
              </select>
            </div>
          </div>
        </div>

        <div className="results-section-header">
          <h2>Available Connections ({filteredProducts.length})</h2>
        </div>

        {loading ? <div className="loading-state">Searching available trains...</div> : (
          <div className="trains-grid">
            {filteredProducts.map((train) => {
              const standardPrice = train.seatClasses.find(c => c.seatClass === "Standard")?.price || 0;
              const totalPrice = standardPrice * parseInt(passengers);

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
                      <span className="detail-label">TOTAL PRICE</span>
                      <span className="price-value">€{totalPrice.toFixed(2)}</span>
                    </div>
                  </div>
                  <button className="btn-book" onClick={() => handleBookClick(train, standardPrice)}>
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