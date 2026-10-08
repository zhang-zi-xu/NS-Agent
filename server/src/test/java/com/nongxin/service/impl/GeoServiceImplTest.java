package com.nongxin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nongxin.integration.context.PhotonGeocodingClient;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GeoServiceImplTest {
    private final GeoServiceImpl service =
            new GeoServiceImpl(
                    new ClassPathResource("cities.csv"), mock(PhotonGeocodingClient.class));

    @Test
    void suqianIsResolvedLocallyWithOrWithoutCitySuffixAndProvincePrefix() {
        service.buildProvinceIndex();
        for (String query : new String[] {"宿迁", "宿迁市", "江苏宿迁", "江苏省宿迁", "江苏省宿迁市", " 江苏 宿迁 "}) {
            var city = service.resolveCity(query);
            assertThat(city).as(query).isNotNull();
            assertThat(city.name()).as(query).isEqualTo("宿迁市");
            assertThat(city.lat()).isEqualTo(33.96);
            assertThat(city.lon()).isEqualTo(118.28);
        }
    }

    @Test
    void unknownSpecificPlaceDoesNotBecomeProvincialCapital() {
        service.buildProvinceIndex();
        assertThat(service.resolveCity("江苏未知镇")).isNull();
        assertThat(service.resolveCity("江苏宿迁镇江")).isNull();
        assertThat(service.resolveCity("江苏南京路")).isNull();
        assertThat(service.resolveCity("江苏").name()).isEqualTo("南京市");
        assertThat(service.resolveCity("镇江").name()).isEqualTo("镇江市");
    }
}
