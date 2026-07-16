import { useEffect, useState, useMemo } from 'react';
import { useKeycloak } from '@react-keycloak/web';
import { useNavigate, useLocation } from 'react-router-dom';
import api from '../api/client';
import type { ProductResponse, ProductsResponse } from '../types/eurotransit';
import './Catalog.css';

const PAGE_SIZE = 12;
const MAX_DAY_TABS = 30;

export const Catalog = () => {
  const { keycloak, initialized } = useKeycloak();
  const navigate = useNavigate();
  const location = useLocation();
  const [products, setProducts] = useState<ProductResponse[]>([]);
  const [loading, setLoading] = useState<boolean>(true);

  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [departureDate, setDepartureDate] = useState('');
  const [passengers, setPassengers] = useState(1);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);

  const isLanding = location.pathname === '/';
  const todayIso = new Date().toISOString().split('T')[0];
  const nowMs = Date.now();

  useEffect(() => {
    api.get<ProductsResponse>('/catalog/products')
      .then((response) => {
        setProducts(response.data.products || []);
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

  const availableDates = useMemo(() => {
    const dates = products.map(p => p.departure?.split('T')[0]).filter(Boolean) as string[];
    return Array.from(new Set(dates)).sort().slice(0, MAX_DAY_TABS);
  }, [products]);

  useEffect(() => {
    if (!isLanding && departureDate === '') {
      setDepartureDate(todayIso);
    }
  }, [isLanding]);

  useEffect(() => {
    setVisibleCount(PAGE_SIZE);
  }, [origin, destination, departureDate]);

  const filteredProducts = products.filter(p => {
    const matchesOrigin = p.origin?.toLowerCase().includes(origin.toLowerCase());
    const matchesDestination = p.destination?.toLowerCase().includes(destination.toLowerCase());

    let matchesDate = true;
    if (departureDate) {
      const productDate = p.departure?.split('T')[0];
      matchesDate = productDate === departureDate;
    }

    return matchesOrigin && matchesDestination && matchesDate;
  }).sort((a, b) => {
    const aDeparted = a.departure ? new Date(a.departure).getTime() < nowMs : false;
    const bDeparted = b.departure ? new Date(b.departure).getTime() < nowMs : false;
    if (aDeparted !== bDeparted) return aDeparted ? 1 : -1;
    return new Date(a.departure).getTime() - new Date(b.departure).getTime();
  });

  const visibleProducts = filteredProducts.slice(0, visibleCount);

  const formatTime = (dateString: string) => {
    if (!dateString) return '--:--';
    return new Date(dateString).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  };

  const formatDateShort = (dateString: string) => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short' });
  };

  const formatTabLabel = (isoDate: string) => {
    if (isoDate === todayIso) return { top: 'Today', bottom: new Date(`${isoDate}T00:00:00`).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' }) };
    const d = new Date(`${isoDate}T00:00:00`);
    return {
      top: d.toLocaleDateString('en-GB', { weekday: 'short' }),
      bottom: d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })
    };
  };

  const handleSwap = () => {
    setOrigin(destination);
    setDestination(origin);
  };

  const adjustPassengers = (delta: number) => {
    setPassengers((prev) => Math.min(6, Math.max(1, prev + delta)));
  };

  const handleBookClick = (train: ProductResponse, standardPrice: number) => {
    const params = new URLSearchParams({
      origin: train.origin,
      destination: train.destination,
      departure: train.departure,
      passengers: passengers.toString(),
      price: standardPrice.toString()
    });
    const targetUrl = `/checkout/${train.train_id}?${params.toString()}`;

    if (!initialized || !keycloak.authenticated) {
      sessionStorage.setItem('post_login_redirect', targetUrl);
      keycloak.login();
      return;
    }

    navigate(targetUrl);
  };

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isLanding) {
      navigate('/catalog');
    }
  };

  return (
    <div className={`catalog-viewport ${isLanding ? 'landing-mode' : 'catalog-mode'}`}>
      {isLanding && (
        <section className="catalog-hero">
          <div className="hero-content">
            <span className="hero-tag">EUROTRANSIT HIGH-SPEED NETWORK</span>
            <h1 className="hero-title">Engineered<br />to arrive.</h1>
            <p className="hero-subtitle">
              Live schedules across Europe's high-speed lines, booked in the time
              it takes to read this. Pick your train, lock your seat, done.
            </p>
          </div>
        </section>
      )}

      <main className="catalog-content">
        <form className="search-widget" onSubmit={handleSearchSubmit}>
          <div className="search-grid">
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

            <button type="button" className="btn-swap" onClick={handleSwap} aria-label="Swap origin and destination">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M7 7h13l-4-4M17 17H4l4 4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>

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
              <label htmlFor="date-input">Departure Date</label>
              <input
                id="date-input"
                type="date"
                min={todayIso}
                value={departureDate}
                onChange={(e) => {
                  const value = e.target.value;
                  setDepartureDate(value && value < todayIso ? todayIso : value);
                }}
              />
            </div>

            <div className="input-group">
              <label>Passengers</label>
              <div className="stepper">
                <button type="button" onClick={() => adjustPassengers(-1)} disabled={passengers <= 1} aria-label="Remove passenger">&minus;</button>
                <span>{passengers}</span>
                <button type="button" onClick={() => adjustPassengers(1)} disabled={passengers >= 6} aria-label="Add passenger">+</button>
              </div>
            </div>

            <div className="input-group search-btn-group">
              <label className="spacer-label">&nbsp;</label>
              <button type="submit" className="btn-search">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="2" />
                  <path d="M21 21l-4.3-4.3" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
                Search trains
              </button>
            </div>
          </div>
        </form>

        {!isLanding && (
          <>
            {availableDates.length > 0 && (
              <div className="day-tabs" role="tablist" aria-label="Departure day">
                {availableDates.map((iso) => {
                  const label = formatTabLabel(iso);
                  const isToday = iso === todayIso;
                  const isPast = iso < todayIso;
                  return (
                    <button
                      type="button"
                      key={iso}
                      className={`day-tab ${departureDate === iso ? 'active' : ''} ${isToday ? 'is-today' : ''} ${isPast ? 'is-past' : ''}`}
                      onClick={() => !isPast && setDepartureDate(iso)}
                      disabled={isPast}
                      aria-disabled={isPast}
                    >
                      <span className="day-tab-top">{label.top}</span>
                      <span className="day-tab-bottom">{label.bottom}</span>
                    </button>
                  );
                })}
              </div>
            )}

            <header className="results-header">
              <div>
                <span className="section-pre-title">TRAINS</span>
                <h2>All connections</h2>
              </div>
              <span className="results-count">{filteredProducts.length} of {products.length} connections</span>
            </header>

            {loading ? <div className="loading-state">Synchronizing with rail network...</div> : (
              <>
                <div className="trains-list">
                  {visibleProducts.map((train) => {
                    const standardPrice = train.seat_classes?.find(c => c.class === "standard")?.price || 0;
                    const totalPrice = standardPrice * passengers;
                    const isDeparted = train.departure ? new Date(train.departure).getTime() < nowMs : false;

                    return (
                      <div key={train.train_id} className={`train-row ${isDeparted ? 'departed' : ''}`}>
                        <div className="row-time">
                          <span className="row-time-value">{formatTime(train.departure)}</span>
                          <span className="row-time-date">{formatDateShort(train.departure)}</span>
                        </div>

                        <div className="row-route">
                          <div className="row-route-line">
                            <span className="row-station">{train.origin}</span>
                            <span className="row-arrow">&rarr;</span>
                            <span className="row-station">{train.destination}</span>
                          </div>
                          <div className="row-meta">
                            <span className="mono row-id">{train.train_id}</span>
                            <span className={`row-status-pill ${isDeparted ? 'departed' : ''}`}>{isDeparted ? 'Departed' : 'On schedule'}</span>
                          </div>
                        </div>

                        <div className="row-price-block">
                          <span className="row-price">€{totalPrice.toFixed(2)}</span>
                          <span className="row-price-sub">total &middot; {passengers}x €{standardPrice.toFixed(2)}</span>
                          <button
                            className="btn-book-row"
                            onClick={() => handleBookClick(train, standardPrice)}
                            disabled={isDeparted}
                          >
                            {isDeparted ? "Departed" : (initialized && keycloak.authenticated ? "Select" : "Sign in to book")}
                          </button>
                        </div>
                      </div>
                    );
                  })}

                  {filteredProducts.length === 0 && (
                    <div className="empty-results">No connections match these filters.</div>
                  )}
                </div>

                {visibleCount < filteredProducts.length && (
                  <button className="btn-load-more" onClick={() => setVisibleCount((c) => c + PAGE_SIZE)}>
                    Load more &middot; showing {visibleProducts.length} of {filteredProducts.length}
                  </button>
                )}
              </>
            )}
          </>
        )}
      </main>
    </div>
  );
};