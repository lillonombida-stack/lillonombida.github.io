import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeatherApp - A simple command-line weather lookup tool.
 *
 * Uses the free Open-Meteo API (https://open-meteo.com), which requires
 * NO API key or signup. Two calls are made:
 *   1. Geocoding API  - turns a city name into latitude/longitude
 *   2. Forecast API   - turns lat/lon into current weather conditions
 *
 * Usage:
 *   javac WeatherApp.java
 *   java WeatherApp
 *   (then type a city name when prompted, or pass it as an argument)
 */
public class WeatherApp {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String city;
        Scanner scanner = new Scanner(System.in);

        if (args.length > 0) {
            city = String.join(" ", args);
        } else {
            System.out.println("=====================================");
            System.out.println("        Java Console Weather App     ");
            System.out.println("=====================================");
            System.out.print("Enter a city name: ");
            city = scanner.nextLine().trim();
        }

        if (city.isEmpty()) {
            System.out.println("No city entered. Exiting.");
            return;
        }

        try {
            GeoResult location = geocode(city);
            if (location == null) {
                System.out.println("Could not find a location matching \"" + city + "\". Try a different spelling or add a country, e.g. \"Paris, FR\".");
                return;
            }

            WeatherResult weather = getWeather(location.latitude, location.longitude);
            printReport(location, weather);

        } catch (Exception e) {
            System.out.println("Something went wrong fetching weather data: " + e.getMessage());
        }

        // Optional loop: let the user check another city without restarting.
        if (args.length == 0) {
            System.out.print("\nCheck another city? (y/n): ");
            String again = scanner.nextLine().trim().toLowerCase();
            if (again.startsWith("y")) {
                main(new String[0]);
            } else {
                System.out.println("Goodbye!");
            }
        }
    }

    // ---------- API calls ----------

    private static GeoResult geocode(String city) throws Exception {
        String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json";

        String body = get(url);

        Double lat = extractDouble(body, "\"latitude\":");
        Double lon = extractDouble(body, "\"longitude\":");
        String name = extractString(body, "\"name\":");
        String country = extractString(body, "\"country\":");

        if (lat == null || lon == null) {
            return null;
        }
        return new GeoResult(name != null ? name : city, country, lat, lon);
    }

    private static WeatherResult getWeather(double lat, double lon) throws Exception {
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,weather_code&temperature_unit=celsius&wind_speed_unit=kmh",
                lat, lon);

        String body = get(url);

        Double temp = extractDouble(body, "\"temperature_2m\":");
        Double feelsLike = extractDouble(body, "\"apparent_temperature\":");
        Double humidity = extractDouble(body, "\"relative_humidity_2m\":");
        Double wind = extractDouble(body, "\"wind_speed_10m\":");
        Integer code = extractInt(body, "\"weather_code\":");

        return new WeatherResult(temp, feelsLike, humidity, wind, code);
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "JavaConsoleWeatherApp/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned status " + response.statusCode());
        }
        return response.body();
    }

    // ---------- Output ----------

    private static void printReport(GeoResult loc, WeatherResult w) {
        System.out.println();
        System.out.println("Weather for " + loc.name + (loc.country != null ? ", " + loc.country : ""));
        System.out.println("-------------------------------------");
        if (w.temperature != null) {
            System.out.printf("Temperature:   %.1f°C%n", w.temperature);
        }
        if (w.feelsLike != null) {
            System.out.printf("Feels like:    %.1f°C%n", w.feelsLike);
        }
        if (w.humidity != null) {
            System.out.printf("Humidity:      %.0f%%%n", w.humidity);
        }
        if (w.windSpeed != null) {
            System.out.printf("Wind speed:    %.1f km/h%n", w.windSpeed);
        }
        if (w.weatherCode != null) {
            System.out.println("Conditions:    " + describeCode(w.weatherCode));
        }
        System.out.println("-------------------------------------");
    }

    // WMO weather codes -> human-readable description
    private static String describeCode(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2, 3 -> "Partly cloudy";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snow fall";
            case 77 -> "Snow grains";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown (code " + code + ")";
        };
    }

    // ---------- Tiny JSON field extractors (no external library needed) ----------

    private static Double extractDouble(String json, String key) {
        Pattern p = Pattern.compile(Pattern.quote(key) + "\\s*(-?\\d+(\\.\\d+)?)");
        Matcher m = p.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static Integer extractInt(String json, String key) {
        Pattern p = Pattern.compile(Pattern.quote(key) + "\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile(Pattern.quote(key) + "\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ---------- Simple data holders ----------

    private record GeoResult(String name, String country, double latitude, double longitude) {}

    private record WeatherResult(Double temperature, Double feelsLike, Double humidity, Double windSpeed, Integer weatherCode) {}
}
