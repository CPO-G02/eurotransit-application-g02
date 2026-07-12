import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: 'https://g02.cpo2026.it/auth',
  realm: 'eurotransit-realm',
  clientId: 'eurotransit-frontend'
});

export default keycloak;