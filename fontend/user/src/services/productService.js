import axios from 'axios';
import axiosClient from '../api/axiosClient.js';

const API_BASE_URL = 'http://localhost:8082/api';

const normalizeProductList = (payload) => {
  const data = payload?.data ?? payload;
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.content)) return data.content;
  return [];
};

const runSearchRequest = async ({ path, endpoint, keywordKey, keyword, params = {}, extraParams = {} }) => {
  const startedAt = performance.now();

  try {
    const response = await axios.get(`${API_BASE_URL}${path}`, {
      params: {
        [keywordKey]: keyword,
        page: params.page ?? 0,
        size: params.size ?? 100,
        ...extraParams,
      },
      timeout: 15000,
      validateStatus: () => true,
    });

    const durationMs = Math.round(performance.now() - startedAt);
    const body = response.data;

    return {
      items: normalizeProductList(body),
      evidence: {
        endpoint,
        status: response.status,
        durationMs,
        responseSize: JSON.stringify(body ?? '').length,
        preview: body,
      },
    };
  } catch (error) {
    return {
      items: [],
      evidence: {
        endpoint,
        status: 'NETWORK',
        durationMs: Math.round(performance.now() - startedAt),
        responseSize: 0,
        preview: error.message,
      },
    };
  }
};

export const productService = {
  getAll: async (params = {}) => {
    try {
      const apiResponse = await axiosClient.get('/products', { params });
      return normalizeProductList(apiResponse);
    } catch (error) {
      console.error('Loi goi API san pham:', error.message);
      return [];
    }
  },

  searchVulnerable: async (keyword, params = {}) => {
    return runSearchRequest({
      path: '/products/search-vulnerable',
      endpoint: '/api/products/search-vulnerable',
      keywordKey: 'keyword',
      keyword,
      params,
    });
  },

  searchByKeyword: async (keyword, params = {}) => {
    return runSearchRequest({
      path: '/products/search',
      endpoint: '/api/products/search',
      keywordKey: 'keyWord',
      keyword,
      params,
    });
  },

  filterByKeyword: async (keyword, params = {}) => {
    return runSearchRequest({
      path: '/products/filter',
      endpoint: '/api/products/filter',
      keywordKey: 'keyWord',
      keyword,
      params,
      extraParams: {
        sortBy: params.sortBy ?? 'id',
        direction: params.direction ?? 'asc',
      },
    });
  },

  getById: async (id) => {
    try {
      const apiResponse = await axiosClient.get(`/products/${id}`);
      const product = apiResponse?.data;
      if (product && product.id) return product;
      return null;
    } catch (error) {
      console.error('Loi lay chi tiet san pham:', error.message);
      return null;
    }
  },
};
