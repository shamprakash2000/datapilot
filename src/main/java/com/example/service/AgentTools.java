package com.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    @Value("${openweather.api.key}")
    private String weatherApiKey;

    @Value("${openweather.api.url}")
    private String weatherApiUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Rough per-day cost estimates in INR for popular Indian destinations
    private static final Map<String, int[]> BUDGET_ESTIMATES = Map.of(
            "goa",        new int[]{3500, 800, 600, 500},   // hotel, food, transport, activities
            "manali",     new int[]{2500, 600, 800, 700},
            "kerala",     new int[]{4000, 900, 700, 600},
            "rajasthan",  new int[]{3000, 700, 900, 800},
            "mumbai",     new int[]{5000, 1200, 500, 400},
            "delhi",      new int[]{4500, 1000, 600, 500},
            "ooty",       new int[]{2000, 500, 400, 300},
            "shimla",     new int[]{2500, 600, 500, 400}
    );

    // Seasonal context for Indian destinations
    private static final Map<String, String> MONTH_SEASON = Map.ofEntries(
            Map.entry("january",   "Winter — cool and dry, ideal for most destinations"),
            Map.entry("february",  "Late winter — pleasant weather, great for sightseeing"),
            Map.entry("march",     "Spring — warm and clear, good time to travel"),
            Map.entry("april",     "Pre-summer — getting hot, avoid hills during day"),
            Map.entry("may",       "Summer — very hot in plains, great for hill stations"),
            Map.entry("june",      "Monsoon begins — lush greenery, some areas inaccessible"),
            Map.entry("july",      "Peak monsoon — heavy rains, avoid coastal areas"),
            Map.entry("august",    "Monsoon — green landscapes, fewer crowds"),
            Map.entry("september", "Late monsoon — rains easing, nature at its best"),
            Map.entry("october",   "Post-monsoon — perfect weather, peak travel season begins"),
            Map.entry("november",  "Winter begins — excellent weather across India"),
            Map.entry("december",  "Winter peak — best time to visit most of India")
    );

    @Tool(description = "Get current weather conditions for a city along with seasonal travel context for the specified month. Use this when the user mentions a destination and travel month.")
    public String getWeather(String city, String month) {
        log.info("Tool called: getWeather({}, {})", city, month);
        try {
            String url = weatherApiUrl + "?q=" + city + "&appid=" + weatherApiKey + "&units=metric";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Response from weather api : {}", response.body());
            JsonNode root = objectMapper.readTree(response.body());

            if (root.has("cod") && root.get("cod").asInt() != 200) {
                return "Weather data unavailable for " + city + ". " + seasonContext(month);
            }

            double temp = root.path("main").path("temp").asDouble();
            double feelsLike = root.path("main").path("feels_like").asDouble();
            int humidity = root.path("main").path("humidity").asInt();
            String description = root.path("weather").get(0).path("description").asText();
            double windSpeed = root.path("wind").path("speed").asDouble();

            return String.format(
                    "Current weather in %s: %.1f°C (feels like %.1f°C), %s, humidity %d%%, wind %.1f m/s. " +
                    "Seasonal context for %s: %s",
                    city, temp, feelsLike, description, humidity, windSpeed,
                    month, seasonContext(month)
            );
        } catch (Exception e) {
            log.warn("Weather API call failed for {}: {}", city, e.getMessage());
            return "Could not fetch live weather for " + city + ". " + seasonContext(month);
        }
    }

    @Tool(description = "Get top tourist attractions, must-try local food, and recommended activities for a city. Use this when planning what to do and see.")
    public String getAttractions(String city) {
        log.info("Tool called: getAttractions({})", city);
        // Returns structured attraction data — Gemini will weave this into the itinerary
        return switch (city.toLowerCase()) {
            case "goa" -> """
                    Top Attractions: Baga Beach, Calangute Beach, Anjuna Flea Market, Dudhsagar Waterfalls,
                    Old Goa Churches (Se Cathedral, Basilica of Bom Jesus), Fort Aguada, Palolem Beach.
                    Must-try Food: Fish curry rice, Prawn balchão, Bebinca (local dessert), Feni (local drink),
                    fresh seafood at Fisherman's Wharf.
                    Activities: Water sports at Baga, dolphin watching cruise, spice plantation tour,
                    casino night, sunset cruise on Mandovi River, flea markets at Anjuna and Mapusa.
                    Best areas to stay: North Goa (Calangute/Baga) for nightlife, South Goa (Palolem/Colva) for peace.
                    """;
            case "manali" -> """
                    Top Attractions: Rohtang Pass, Solang Valley, Hadimba Temple, Manu Temple,
                    Old Manali market, Beas River, Naggar Castle.
                    Must-try Food: Siddu (local bread), Trout fish, Thukpa (noodle soup), Chha Gosht.
                    Activities: Snow activities at Solang, trekking, river rafting, paragliding,
                    camping, bike trip to Spiti Valley.
                    Best areas to stay: Mall Road for convenience, Old Manali for vibe.
                    """;
            case "kerala" -> """
                    Top Attractions: Alleppey backwaters, Munnar tea gardens, Kovalam Beach,
                    Periyar Wildlife Sanctuary, Fort Kochi, Wayanad.
                    Must-try Food: Kerala sadya (banana leaf meal), Appam with stew, Karimeen pollichathu,
                    Kerala prawn curry, Puttu and kadala curry.
                    Activities: Houseboat stay in Alleppey, Ayurveda spa, tea estate walks,
                    elephant sanctuary visit, Chinese fishing net experience in Kochi.
                    Best areas to stay: Kochi for culture, Munnar for nature, Alleppey for backwaters.
                    """;
            default -> "Popular attractions, local cuisine, and activities in " + city +
                       " — a diverse destination with rich culture, local markets, historic sites, and natural beauty. " +
                       "Recommend exploring the old town, local food streets, and nearby nature spots.";
        };
    }

    @Tool(description = "Estimate total trip budget in Indian Rupees for a given city and number of days. Includes hotel, food, local transport and activities. Use this when the user asks about cost or budget.")
    public String estimateBudget(String city, int days) {
        log.info("Tool called: estimateBudget({}, {} days)", city, days);
        int[] costs = BUDGET_ESTIMATES.getOrDefault(city.toLowerCase(), new int[]{3000, 700, 600, 500});

        int hotelPerDay = costs[0];
        int foodPerDay = costs[1];
        int transportPerDay = costs[2];
        int activitiesPerDay = costs[3];
        int totalPerDay = hotelPerDay + foodPerDay + transportPerDay + activitiesPerDay;
        int total = totalPerDay * days;
        int flightEstimate = 8000; // rough round-trip from major Indian city

        return String.format(
                "Estimated budget for %d days in %s:\n" +
                "  Hotel: ₹%,d/night × %d nights = ₹%,d\n" +
                "  Food: ₹%,d/day × %d days = ₹%,d\n" +
                "  Local transport: ₹%,d/day × %d days = ₹%,d\n" +
                "  Activities: ₹%,d/day × %d days = ₹%,d\n" +
                "  Estimated flights (round trip): ₹%,d\n" +
                "  ─────────────────────────────────\n" +
                "  Total estimate: ₹%,d (excluding flights: ₹%,d)\n" +
                "  Note: Budget hotels available from ₹%,d/night. Luxury options from ₹%,d/night.",
                days, city,
                hotelPerDay, days, hotelPerDay * days,
                foodPerDay, days, foodPerDay * days,
                transportPerDay, days, transportPerDay * days,
                activitiesPerDay, days, activitiesPerDay * days,
                flightEstimate,
                total + flightEstimate, total,
                hotelPerDay / 2, hotelPerDay * 3
        );
    }

    @Tool(description = "Get the current date and time. Use this when the user asks about today's date or when calculating trip dates.")
    public String getCurrentDateTime() {
        log.info("Tool called: getCurrentDateTime()");
        return "Current date and time: " +
               LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy, hh:mm a"));
    }

    private String seasonContext(String month) {
        return MONTH_SEASON.getOrDefault(month.toLowerCase(),
                "Check local weather forecasts before travelling.");
    }
}
