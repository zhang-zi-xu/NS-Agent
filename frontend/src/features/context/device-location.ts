/** High accuracy is a request, not a guarantee that a desktop has GPS. */
export const DEVICE_LOCATION_OPTIONS: PositionOptions = {
  enableHighAccuracy: true,
  timeout: 20000,
  maximumAge: 0,
};

export function deviceLocationIssue(
  position: GeolocationPosition,
  now = Date.now(),
): string | null {
  const { latitude, longitude, accuracy } = position.coords;
  if (
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    Math.abs(latitude) > 90 ||
    Math.abs(longitude) > 180 ||
    !Number.isFinite(accuracy) ||
    accuracy < 0
  ) {
    return '设备返回的位置数据无效，请重新定位或手动选择城市。';
  }
  if (
    !Number.isFinite(position.timestamp) ||
    now - position.timestamp > 60000 ||
    position.timestamp > now + 5000
  ) {
    return '设备返回的定位时间异常或已过期，请重新定位或手动选择城市。';
  }
  return null;
}

export function locationAccuracyText(accuracy: number): string {
  if (!Number.isFinite(accuracy) || accuracy <= 0) return '估计精度未提供';
  return accuracy >= 1000
    ? `设备估计精度约 ${(accuracy / 1000).toFixed(1)} 公里`
    : `设备估计精度约 ${Math.round(accuracy)} 米`;
}
