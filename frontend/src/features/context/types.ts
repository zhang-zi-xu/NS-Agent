export type LiveContext = {
  method: 'device' | 'manual' | null;
  location: string | null;
  latitude: number;
  longitude: number;
  accuracy: number;
  locatedAt: number;
  timezone: string | null;
  timezoneAbbreviation: string | null;
  observedAt: string | null;
  weather: {
    temperature: number | null;
    apparentTemperature: number | null;
    humidity: number | null;
    precipitation: number | null;
    weatherCode: number | null;
    windSpeed: number | null;
    windDirection: number | null;
  } | null;
  daily: Array<{
    date: string;
    weatherCode: number | null;
    tempMax: number | null;
    tempMin: number | null;
    precipitationSum: number | null;
    precipitationProbability: number | null;
    windSpeedMax: number | null;
  }>;
  dailyText: string;
  weatherError: string | null;
  sources: { weather: string | null; location: string | null };
};

export type WeatherCodeText = (code: number | null) => string;
