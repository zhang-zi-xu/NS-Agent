package com.nongxin.service.impl;

import static com.nongxin.service.WeatherService.weatherText;

import com.nongxin.domain.context.DailyWeather;
import com.nongxin.domain.context.WeatherBundle;
import com.nongxin.integration.context.OpenMeteoClient;
import com.nongxin.service.WeatherService;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WeatherServiceImpl implements WeatherService {
    private final OpenMeteoClient client;

    public WeatherServiceImpl(OpenMeteoClient client) {
        this.client = client;
    }

    @Override
    public WeatherBundle fetch(double lat, double lon, int days) {
        return client.fetch(lat, lon, days);
    }

    /** 生成 7 日预报文字（供 Agent 上下文） */
    public String describeDaily(List<DailyWeather> daily) {
        StringBuilder sb = new StringBuilder();
        for (DailyWeather d : daily) {
            if (d.date() == null || d.date().isEmpty()) continue;
            sb.append(d.date()).append(' ').append(weatherText(d.weatherCode()));
            if (d.tempMax() != null && d.tempMin() != null) {
                sb.append("，最高")
                        .append(d.tempMax().intValue())
                        .append("°C/最低")
                        .append(d.tempMin().intValue())
                        .append("°C");
            }
            if (d.precipitationProbability() != null) {
                sb.append("，降水概率").append(Math.round(d.precipitationProbability())).append('%');
            }
            if (d.precipitationSum() != null && d.precipitationSum() > 0) {
                sb.append("，降水").append(String.format("%.1f", d.precipitationSum())).append("mm");
            }
            if (d.windSpeedMax() != null) {
                sb.append("，最大风速").append(d.windSpeedMax().intValue()).append("km/h");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
