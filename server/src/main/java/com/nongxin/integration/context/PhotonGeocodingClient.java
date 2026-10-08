package com.nongxin.integration.context;

import com.nongxin.domain.context.GeoEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Remote geocoding adapter; local city lookup remains in the business service. */
@Component
public class PhotonGeocodingClient {
    private static final Logger log = LoggerFactory.getLogger(PhotonGeocodingClient.class);
    private static final String PHOTON_SEARCH = "https://photon.komoot.io/api";
    private static final String PHOTON_REVERSE = "https://photon.komoot.io/reverse";
    private final RestClient restClient = RestClient.builder().build();

    /** Photon 兜底搜索（返回 null 表示失败） */
    public GeoEntry search(String query) {
        try {
            String url =
                    PHOTON_SEARCH
                            + "?q="
                            + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&limit=1";
            Map<?, ?> data =
                    restClient
                            .get()
                            .uri(url)
                            .header("Accept", "application/json")
                            .header("User-Agent", "NongxinAgent/1.0")
                            .retrieve()
                            .body(Map.class);
            if (data == null) return null;
            Object featuresObj = data.get("features");
            if (!(featuresObj instanceof List<?> features) || features.isEmpty()) return null;
            if (!(features.get(0) instanceof Map<?, ?> feature)) return null;
            Object geometry = feature.get("geometry");
            if (!(geometry instanceof Map<?, ?> geom)) return null;
            Object coords = geom.get("coordinates");
            if (!(coords instanceof List<?> c) || c.size() < 2) return null;
            double lon = ((Number) c.get(0)).doubleValue();
            double lat = ((Number) c.get(1)).doubleValue();
            String name = photonName(feature.get("properties"));
            return new GeoEntry(name != null ? name : query, "", lat, lon);
        } catch (Exception e) {
            log.warn("Photon 搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /** 坐标 → 地名（失败返回 null，调用方降级为坐标文本） */
    public String reverse(double lat, double lon) {
        try {
            String url =
                    PHOTON_REVERSE
                            + "?lat="
                            + String.format("%.5f", lat)
                            + "&lon="
                            + String.format("%.5f", lon);
            Map<?, ?> data =
                    restClient
                            .get()
                            .uri(url)
                            .header("Accept", "application/json")
                            .header("User-Agent", "NongxinAgent/1.0")
                            .retrieve()
                            .body(Map.class);
            if (data == null) return null;
            Object featuresObj = data.get("features");
            if (!(featuresObj instanceof List<?> features) || features.isEmpty()) return null;
            if (!(features.get(0) instanceof Map<?, ?> feature)) return null;
            return photonName(feature.get("properties"));
        } catch (Exception e) {
            log.warn("Photon 逆地理失败: {}", e.getMessage());
            return null;
        }
    }

    private String photonName(Object propertiesObj) {
        if (!(propertiesObj instanceof Map<?, ?> props)) return null;
        List<String> parts = new ArrayList<>();
        for (String key : List.of("state", "city", "county", "district", "locality")) {
            Object v = props.get(key);
            if (v instanceof String s && StringUtils.hasText(s)) {
                if (!parts.contains(s)) parts.add(s);
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
