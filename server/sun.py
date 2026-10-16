"""Sunrise/sunset (UTC hours) for a date and location.

Algorithm: Sunrise/Sunset line equations (Almanac for Computers, USNO),
zenith = 90.833 (accounts for refraction + solar disc edge).
Accuracy ~+-2 min; drift through the year is inherent to the formula
(new date -> new declination), which also makes seasonal behaviour correct.
"""
import math


def _day_of_year(year, month, day):
    cumulative = (0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    n = cumulative[month - 1] + day
    if month > 2 and (year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)):
        n += 1
    return n


def sun_times_utc(year, month, day, lat, lon):
    """Returns (sunrise_utc_hours, sunset_utc_hours) as floats in [0,48)
    (values >24 belong to the following UTC day) or (None, None) in polar cases."""
    n = _day_of_year(year, month, day)
    lng_hour = lon / 15.0

    # approximate times
    t_rise = n + ((6.0 - lng_hour) / 24.0)
    t_set = n + ((18.0 - lng_hour) / 24.0)

    def anomaly(t):
        return 0.9856 * t - 3.289

    def true_long(m):
        L = m + (1.916 * math.sin(math.radians(m))) + (0.020 * math.sin(math.radians(2 * m))) + 282.634
        L = L % 360.0
        if L < 0:
            L += 360.0
        return L

    def ra(L):
        RA = math.degrees(math.atan(0.91764 * math.tan(math.radians(L))))
        RA = RA % 360.0
        if RA < 0:
            RA += 360.0
        L_quadrant = (math.floor(L / 90.0)) * 90.0
        RA_quadrant = (math.floor(RA / 90.0)) * 90.0
        RA = RA + (L_quadrant - RA_quadrant)
        return RA / 15.0

    def hour_angle(lat_deg, true_long_val, rise):
        sin_dec = 0.39782 * math.sin(math.radians(true_long_val))
        cos_dec = math.cos(math.asin(sin_dec))
        lat_rad = math.radians(lat_deg)
        zenith = math.radians(90.833)
        cos_h = (math.cos(zenith) - (sin_dec * math.sin(lat_rad))) / (cos_dec * math.cos(lat_rad))
        if cos_h > 1.0:
            return None  # never rises
        if cos_h < -1.0:
            return None  # never sets (splits)
        if rise:
            h = (360.0 - math.degrees(math.acos(cos_h))) / 15.0
        else:
            h = math.degrees(math.acos(cos_h)) / 15.0
        return h

    def t_for(rise):
        t = t_rise if rise else t_set
        M = anomaly(t)
        L = true_long(M)
        H = hour_angle(lat, L, rise)
        RA = ra(L)   # in hours
        if H is None or RA is None:
            return None
        UT = (H + RA - (0.06571 * t) - 6.622 - lng_hour) % 24.0
        if math.isnan(UT):
            return None
        return UT

    rise = t_for(True)
    set = t_for(False)
    return rise, set
