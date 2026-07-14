import { useEffect, useState, useMemo } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import { useNavigate, useLocation } from 'react-router-dom';
import api from '../api/client';
import type { ProductResponse, ProductsResponse } from '../types/eurotransit';
import './Catalog.css';

export const Catalog = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();
  const location = useLocation();
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [departureDate, setDepartureDate] = useState('');
  const [passengers, setPassengers] = useState('1');

  const isLanding = location.pathname === '/';

  useEffect(() => {
    api.get<ProductsResponse>('/catalog/products')
      .then((response) => {
        setProducts(response.data?.products || []);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const availableOrigins = useMemo(() => {
    const origins = products?.map(p => p.origin).filter(Boolean) || [];
    const unique = Array.from(new Set(origins));
    return unique.length > 0 ? unique : ['Milano', 'Roma', 'Torino', 'Parigi', 'Lyon', 'Zurich'];
  }, [products]);

  const availableDestinations = useMemo(() => {
    const dests = products?.map(p => p.destination).filter(Boolean) || [];
    const unique = Array.from(new Set(dests));
    return unique.length > 0 ? unique : ['Roma', 'Milano', 'Torino', 'Parigi', 'Lyon', 'Zurich'];
  }, [products]);

  const filteredProducts = products?.filter(p => {
    const matchesOrigin = p.origin?.toLowerCase().includes(origin.toLowerCase());
    const matchesDestination = p.destination?.toLowerCase().includes(destination.toLowerCase());
    
    let matchesDate = true;
    if (departureDate) {
      const productDate = p.departure?.split('T')[0];
      matchesDate = productDate === departureDate;
    }

    return matchesOrigin && matchesDestination && matchesDate;
  }) || [];

  const formatDate = (dateString: string) => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  };

  const handleBookClick = (train: ProductResponse, standardPrice: number) => {
    if (!initialized || !keycloak.authenticated) {
      keycloak.login();
      return;
    }
    const params = new URLSearchParams({
      origin: train.origin || '',
      destination: train.destination || '',
      departure: train.departure || '',
      passengers: passengers,
      price: standardPrice.toString()
    });
    navigate(`/checkout/${train.train_id}?${params.toString()}`);
  };

  const handleSearchClick = (e: React.FormEvent) => {
    e.preventDefault();
    if (isLanding) {
      navigate('/catalog');
    }
  };

  return (
    <div className={`catalog-viewport ${isLanding ? 'landing-mode' : 'catalog-mode'}`}>
      {isLanding && (
        <div className="catalog-hero">
          <div className="train-track-bg">
            <svg viewBox="0 0 1200 200" className="static-train-svg" xmlns="http://www.w3.org/2000/svg">
              <line x1="0" y1="160" x2="1200" y2="160" stroke="#30363d" strokeWidth="4" />
              <path d="M 0,60 L 850,60 C 1020,60 1130,100 1180,150 L 0,150 Z" fill="#161b22" stroke="#30363d" strokeWidth="2" />
              <path d="M 0,85 L 830,85 C 950,85 1040,105 1100,130 L 0,130 Z" fill="#0d1117" />
              <line x1="150" y1="85" x2="150" y2="130" stroke="#161b22" strokeWidth="8" />
              <line x1="300" y1="85" x2="300" y2="130" stroke="#161b22" strokeWidth="8" />
              <line x1="450" y1="85" x2="450" y2="130" stroke="#161b22" strokeWidth="8" />
              <line x1="600" y1="85" x2="600" y2="130" stroke="#161b22" strokeWidth="8" />
              <line x1="750" y1="85" x2="750" y2="130" stroke="#161b22" strokeWidth="8" />
              <line x1="900" y1="90" x2="900" y2="130" stroke="#161b22" strokeWidth="8" />
              <path d="M 0,136 L 1110,136 C 1130,136 1150,142 1165,150 L 0,150 Z" fill="#ea580c" />
            </svg>
          </div>
          <div className="hero-text">
            <span className="hero-tag">EUROTRANSIT HIGH-SPEED NETWORK</span>
            <h1>NEXT STOP, WHEREVER.</h1>
            <p>High-speed comfort between Europe's great cities, at fares that make sense. Pick your train, choose your seats, and you're ready to go.</p>
          </div>
        </div>
      )}

      <main className="catalog-content">
        <form className="search-widget-card" onSubmit={handleSearchClick}>
          <div className="search-form-grid">
            <div className="input-group">
              <label htmlFor="origin-input">From</label>
              <input 
                id="origin-input"
                type="text" 
                list="origins-list"
                placeholder="Any city" 
                value={origin} 
                onChange={(e) => setOrigin(e.target.value)} 
              />
              <datalist id="origins-list">
                {availableOrigins.map((city, idx) => (
                  <option key={idx} value={city} />
                ))}
              </datalist>
            </div>

            <div className="input-group">
              <label htmlFor="destination-input">To</label>
              <input 
                id="destination-input"
                type="text" 
                list="destinations-list"
                placeholder="Any city" 
                value={destination} 
                onChange={(e) => setDestination(e.target.value)} 
              />
              <datalist id="destinations-list">
                {availableDestinations.map((city, idx) => (
                  <option key={idx} value={city} />
                ))}
              </datalist>
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

            <div className="input-group search-btn-group">
              <label className="spacer-label">&nbsp;</label>
              <button type="submit" className="btn-search-trains">
                🔍 Search trains
              </button>
            </div>
          </div>
        </form>

        <div className="results-section-header">
          <span className="section-pre-title">{isLanding ? "DEPARTURES" : "TRAINS"}</span>
          <h2>{isLanding ? "TODAY ON THE NETWORK" : "ALL CONNECTIONS"} ({filteredProducts.length})</h2>
        </div>

        {loading ? <div className="loading-state">Searching available trains...</div> : (
          <div className="trains-grid-container">
            <div className="trains-grid">
              {filteredProducts.map((train) => {
                const standardPrice = train.seat_classes.find(c => c.class === "standard")?.price || 0;
                const totalPrice = standardPrice * parseInt(passengers);

                return (
                  <div key={train.train_id} className="train-card">
                    <div className="train-card-header">
                      <span className="train-id">{train.train_id}</span>
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
                      {initialized && keycloak.authenticated ? "Select Seat" : "Login to Book"}
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </main>
    </div>
  );
};
