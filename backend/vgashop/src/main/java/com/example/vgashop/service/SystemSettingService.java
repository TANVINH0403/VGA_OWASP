package com.example.vgashop.service;

import com.example.vgashop.entity.SystemSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    @Autowired
    private final EntityManager entityManager;

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, String> getAllSettings() {
        List<SystemSetting> settings = entityManager.createNativeQuery("SELECT * FROM system_settings", SystemSetting.class).getResultList();
        return settings.stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, SystemSetting::getSettingValue));
    }

    @Transactional(readOnly = true)
    public String getSettingValue(String key, String defaultValue) {
        try {
            SystemSetting setting = (SystemSetting) entityManager.createNativeQuery("SELECT * FROM system_settings WHERE setting_key = :key", SystemSetting.class)
                    .setParameter("key", key).getSingleResult();
            return setting.getSettingValue();
        } catch (NoResultException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void updateSettings(Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            try {
                SystemSetting setting = (SystemSetting) entityManager.createNativeQuery("SELECT * FROM system_settings WHERE setting_key = :key", SystemSetting.class)
                        .setParameter("key", entry.getKey()).getSingleResult();
                entityManager.createNativeQuery("UPDATE system_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", entry.getValue())
                        .setParameter("key", entry.getKey())
                        .executeUpdate();
            } catch (NoResultException e) {
                entityManager.createNativeQuery("INSERT INTO system_settings (setting_key, setting_value, description) VALUES (:key, :value, 'Auto-created setting')")
                        .setParameter("key", entry.getKey())
                        .setParameter("value", entry.getValue())
                        .executeUpdate();
            }
        }
    }
}
