package com.nongxin.service.impl;

import com.nongxin.domain.context.GeoEntry;
import com.nongxin.integration.context.PhotonGeocodingClient;
import com.nongxin.service.GeoService;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 地理编码服务：内置城市中心点优先，不代表设备或田块精确位置；Photon 作其他地名兜底，逆解析失败返回坐标文本。 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);

    private final PhotonGeocodingClient photon;
    private final List<GeoEntry> cities = new ArrayList<>();
    private final Map<String, GeoEntry> provinceCapital = new HashMap<>();

    public GeoServiceImpl(
            @Value("${nongxin.cities-resource}") Resource citiesResource,
            PhotonGeocodingClient photon) {
        this.photon = photon;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                citiesResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 4);
                if (parts.length < 4) continue;
                try {
                    cities.add(
                            new GeoEntry(
                                    parts[0],
                                    parts[1],
                                    Double.parseDouble(parts[2]),
                                    Double.parseDouble(parts[3])));
                } catch (NumberFormatException e) {
                    log.warn("cities.csv 无效行: {}", line);
                }
            }
        } catch (Exception e) {
            log.error("加载城市表失败", e);
        }
    }

    @PostConstruct
    void buildProvinceIndex() {
        for (GeoEntry entry : cities) {
            if (!entry.name().endsWith("市")) continue;
            provinceCapital.putIfAbsent(entry.prov(), entry);
        }
    }

    public int cityCount() {
        return cities.size();
    }

    /** 城市名解析（内置表优先） */
    public GeoEntry resolveCity(String query) {
        String raw = query == null ? "" : query.replaceAll("\\s+", "");
        if (raw.isEmpty()) return null;
        for (GeoEntry c : cities) {
            if (c.name().equals(raw)) return c;
        }
        for (GeoEntry c : cities) {
            if (raw.startsWith(c.name())) return c;
        }
        for (GeoEntry c : cities) {
            if (raw.contains(c.name())) return c;
        }
        // Accept common city names without the 市 suffix, including province prefixes.
        // Never turn 江苏宿迁 into the provincial capital just because 江苏 matched first.
        for (GeoEntry c : cities) {
            if (!c.name().endsWith("市")) continue;
            String shortCity = c.name().substring(0, c.name().length() - 1);
            String shortProv = shortProvince(c.prov());
            if (raw.equals(shortCity)
                    || raw.equals(c.prov() + shortCity)
                    || raw.equals(shortProv + shortCity)) return c;
        }
        for (Map.Entry<String, GeoEntry> e : provinceCapital.entrySet()) {
            String shortProv = shortProvince(e.getKey());
            // A province-only query retains the previous capital fallback. More specific,
            // unknown place names must go to geocoding, not silently become another city.
            if (raw.equals(e.getKey()) || shortProv.length() >= 2 && raw.equals(shortProv))
                return e.getValue();
        }
        return null;
    }

    private static String shortProvince(String province) {
        return province.replace("省", "").replace("市", "").replace("自治区", "");
    }

    @Override
    public GeoEntry photonSearch(String query) {
        return photon.search(query);
    }

    @Override
    public String reverseGeocode(double lat, double lon) {
        return photon.reverse(lat, lon);
    }
}
