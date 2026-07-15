import axios from 'axios';
import keycloak from '../keycloak';

const api = axios.create({
  baseURL: 'https://g02.cpo2026.it/api/v1',
});

api.interceptors.request.use((config) => {
  if (keycloak.token) {
    config.headers.Authorization = `Bearer ${keycloak.token}`;
  }
  return config;
});

export default api;