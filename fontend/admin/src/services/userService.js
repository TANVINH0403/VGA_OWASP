import axiosClient from '../api/axiosClient';

const userService = {
  getAll: (params) => axiosClient.get('/admin/users', { params }),
  getById: (id) => axiosClient.get(`/admin/users/${id}`),
  create: (data) => axiosClient.post('/admin/users', data),
  update: (id, data) => axiosClient.put(`/admin/users/${id}`, data),
  delete: (id) => axiosClient.delete(`/admin/users/${id}`),
  toggleStatus: (id) => axiosClient.put(`/admin/users/${id}/status`),

  // --- Vulnerable Endpoints (For Testing/Education) ---
  getInfoVulnerable: (username) => axiosClient.get('/users/info-vulnerable', { params: { username } }),
  getAdminListVulnerable: (role) => axiosClient.get('/users/admin-list-vulnerable', { params: { role } }),
  getStatsVulnerable: (status) => axiosClient.get('/users/stats-vulnerable', { params: { status } }),
};

export default userService;
