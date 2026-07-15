import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: 'https://auth.g02.cpo2026.it',
  realm: 'eurotransit',
  clientId: 'eurotransit-frontend'
});

export default keycloak;