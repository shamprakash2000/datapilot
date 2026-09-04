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

    @Tool(description = "Get current weather conditions for an Indian city along with seasonal travel context for the specified month. Use this when the user mentions an Indian destination and travel month.")
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

    @Tool(description = "Get top tourist attractions, must-try local food, and recommended activities for an Indian city. Use this when planning what to do and see.")
    public String getAttractions(String city) {
        log.info("Tool called: getAttractions({})", city);
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

    @Tool(description = "Get all available modes of transport between two Indian cities including flights, trains, buses, and road options with duration, cost, and frequency. Use this when the user asks how to travel between two places.")
    public String getModeOfTransport(String fromCity, String toCity) {
        log.info("Tool called: getModeOfTransport({}, {})", fromCity, toCity);
        String route = fromCity.toLowerCase() + "-" + toCity.toLowerCase();
        String reverseRoute = toCity.toLowerCase() + "-" + fromCity.toLowerCase();

        String routeData = TRANSPORT_ROUTES.getOrDefault(route,
                           TRANSPORT_ROUTES.getOrDefault(reverseRoute, null));

        if (routeData != null) return routeData;

        // Generic fallback for routes not in our data
        return String.format(
                "Transport options from %s to %s:\n" +
                "  ✈ Flight — Check IndiGo, Air India, SpiceJet on MakeMyTrip/Ixigo for availability\n" +
                "  🚂 Train — Search on IRCTC (irctc.co.in) for trains between these cities\n" +
                "  🚌 Bus — KSRTC/state buses and private Volvo/sleeper options available; check RedBus\n" +
                "  🚗 Drive — Use Google Maps for exact distance, route, and toll estimates\n" +
                "  💡 Tip: For distances under 300km, train or bus is usually most economical. " +
                "For over 500km, compare flight vs overnight train.",
                fromCity, toCity);
    }

    @Tool(description = "Search for flight options between two Indian cities on a given date. Returns estimated prices, duration, and airlines. Use when the user asks about flights or air travel.")
    public String searchFlights(String fromCity, String toCity, String date) {
        log.info("Tool called: searchFlights({}, {}, {})", fromCity, toCity, date);
        String route = fromCity.toLowerCase() + "-" + toCity.toLowerCase();
        String reverseRoute = toCity.toLowerCase() + "-" + fromCity.toLowerCase();

        String flightData = FLIGHT_ROUTES.getOrDefault(route,
                            FLIGHT_ROUTES.getOrDefault(reverseRoute, null));

        if (flightData != null) {
            return "Flights from " + fromCity + " to " + toCity + " on " + date + ":\n" + flightData +
                   "\n💡 Book on MakeMyTrip, Ixigo, or directly on airline websites for best prices. " +
                   "Prices shown are estimates — actual fares vary by date and booking time.";
        }

        return String.format(
                "Flights from %s to %s on %s:\n" +
                "  No direct flight data available for this route.\n" +
                "  💡 Check MakeMyTrip, Ixigo, or Cleartrip for real-time availability.\n" +
                "  Consider connecting via Mumbai, Delhi, Bangalore, or Hyderabad hub airports.",
                fromCity, toCity, date);
    }

    @Tool(description = "Find hotel options in an Indian city with price ranges for different budgets. Use when the user asks about accommodation or where to stay.")
    public String findHotels(String city, String checkIn, int nights) {
        log.info("Tool called: findHotels({}, checkIn={}, nights={})", city, checkIn, nights);
        int[] costs = BUDGET_ESTIMATES.getOrDefault(city.toLowerCase(), new int[]{3000, 700, 600, 500});
        int midRange = costs[0];
        int budget = midRange / 2;
        int luxury = midRange * 3;

        String specificHotels = HOTEL_RECOMMENDATIONS.getOrDefault(city.toLowerCase(), "");

        return String.format(
                "Hotel options in %s (check-in: %s, %d night%s):\n\n" +
                "  🏨 Budget (₹%,d–₹%,d/night)\n" +
                "     Hostels, guesthouses, OYO properties\n" +
                "     Total for %d nights: ₹%,d–₹%,d\n\n" +
                "  🏩 Mid-range (₹%,d–₹%,d/night)\n" +
                "     3-star hotels, boutique stays\n" +
                "     Total for %d nights: ₹%,d–₹%,d\n\n" +
                "  🏰 Luxury (₹%,d+/night)\n" +
                "     5-star resorts, heritage hotels\n" +
                "     Total for %d nights: ₹%,d+\n\n" +
                "%s" +
                "  💡 Book on MakeMyTrip, Booking.com, or Goibibo. " +
                "Book 2-3 weeks in advance for peak season.",
                city, checkIn, nights, nights > 1 ? "s" : "",
                budget, midRange - 500,
                nights, budget * nights, (midRange - 500) * nights,
                midRange, midRange + 1500,
                nights, midRange * nights, (midRange + 1500) * nights,
                luxury,
                nights, luxury * nights,
                specificHotels
        );
    }

    // Popular transport routes with detailed info
    private static final Map<String, String> TRANSPORT_ROUTES = Map.ofEntries(
            Map.entry("mangalore-bangalore", """
                    Transport options from Mangalore to Bangalore:
                      ✈ Flight — ~1hr | ₹2,500–5,000 | IndiGo, Air India | 3-4 flights/day from MNG airport
                      🚂 Train — ~7-9hrs | ₹300–1,200 | Matsyagandha Exp, Rajya Rani Exp | overnight options
                      🚌 Bus — ~7-8hrs | ₹400–900 | KSRTC Airavat, private Volvo/sleeper | very frequent
                      🚗 Drive — ~6-7hrs | 360km via NH75 | toll ~₹400 | scenic Western Ghats route
                      💡 Best option: Overnight bus (departs 9-10pm, arrives early morning) saves hotel cost."""),
            Map.entry("mumbai-goa", """
                    Transport options from Mumbai to Goa:
                      ✈ Flight — ~1hr | ₹3,000–7,000 | IndiGo, GoAir, Air India | 8-10 flights/day
                      🚂 Train — ~8-12hrs | ₹400–1,800 | Konkan Railway (scenic) | Mandovi/Tejas Express
                      🚌 Bus — ~12-14hrs | ₹800–1,500 | private Volvo sleeper | overnight options
                      🚗 Drive — ~10-11hrs | 590km via NH66 | scenic coastal route
                      💡 Best option: Konkan Railway — scenic coastal route through Western Ghats."""),
            Map.entry("delhi-agra", """
                    Transport options from Delhi to Agra:
                      🚂 Train — ~2hrs | ₹700–1,500 | Gatimaan Express (fastest, 160km/h) | Shatabdi
                      🚌 Bus — ~4hrs | ₹200–500 | AC buses from ISBT Kashmere Gate | frequent
                      🚗 Drive — ~3-4hrs | 230km via Yamuna Expressway | toll ~₹600
                      ✈ No direct flights — distance too short
                      💡 Best option: Gatimaan Express — fastest train in India on this route."""),
            Map.entry("bangalore-mysore", """
                    Transport options from Bangalore to Mysore:
                      🚂 Train — ~2.5-3hrs | ₹100–500 | Shatabdi Express | 4-5 trains/day
                      🚌 Bus — ~3hrs | ₹150–350 | KSRTC frequent service | every 30 mins
                      🚗 Drive — ~3hrs | 150km via NH275 | 6-lane expressway | toll ~₹150
                      ✈ No flights — too close
                      💡 Best option: KSRTC bus — most frequent, affordable, drops at city centre."""),
            Map.entry("chennai-pondicherry", """
                    Transport options from Chennai to Pondicherry:
                      🚌 Bus — ~3hrs | ₹100–250 | TNSTC/private | very frequent from CMBT
                      🚗 Drive — ~2.5-3hrs | 160km via ECR (scenic coastal road) | no major tolls
                      🚂 Train — ~4hrs | ₹80–300 | limited direct trains | Villupuram change
                      ✈ No flights
                      💡 Best option: Drive via ECR — beautiful coastal highway, best road trip route."""),
            Map.entry("delhi-jaipur", """
                    Transport options from Delhi to Jaipur:
                      🚂 Train — ~4-5hrs | ₹300–1,200 | Shatabdi, Duronto | 8-10 trains/day
                      🚌 Bus — ~5-6hrs | ₹300–700 | RSRTC Volvo | very frequent from ISBT
                      🚗 Drive — ~5hrs | 280km via NH48 | 6-lane expressway | toll ~₹500
                      ✈ Flight — ~1hr | ₹3,500–6,000 | limited flights
                      💡 Best option: Train — comfortable, city-centre to city-centre."""),
            Map.entry("bangalore-goa", """
                    Transport options from Bangalore to Goa:
                      ✈ Flight — ~1hr | ₹3,000–6,000 | IndiGo, SpiceJet | 4-5 flights/day
                      🚌 Bus — ~9-10hrs | ₹700–1,400 | private Volvo sleeper | overnight popular
                      🚗 Drive — ~8-9hrs | 560km via NH748 | scenic Western Ghats
                      🚂 Train — ~10-12hrs | ₹400–1,500 | limited options via Vasco/Margao
                      💡 Best option: Overnight bus — saves hotel night, arrives early morning.""")
    );

    // Flight route data
    private static final Map<String, String> FLIGHT_ROUTES = Map.ofEntries(
            Map.entry("mangalore-bangalore",
                    "  ✈ IndiGo 6E-xxx — Departs 06:00, Arrives 07:05 | ₹2,800–4,500\n" +
                    "  ✈ Air India AI-xxx — Departs 14:30, Arrives 15:35 | ₹3,200–5,500\n" +
                    "  ✈ IndiGo 6E-xxx — Departs 19:00, Arrives 20:05 | ₹2,500–4,000\n" +
                    "  Duration: ~1hr | Airport: Mangaluru International (MNG) → Kempegowda (BLR)"),
            Map.entry("mumbai-goa",
                    "  ✈ IndiGo 6E-xxx — Departs 06:15, Arrives 07:20 | ₹3,500–6,000\n" +
                    "  ✈ GoAir G8-xxx — Departs 10:30, Arrives 11:35 | ₹3,000–5,500\n" +
                    "  ✈ Air India AI-xxx — Departs 18:00, Arrives 19:10 | ₹4,000–7,000\n" +
                    "  Duration: ~1hr 10min | Airport: CSIA (BOM) → Dabolim/Manohar (GOI)"),
            Map.entry("delhi-goa",
                    "  ✈ IndiGo 6E-xxx — Departs 05:30, Arrives 08:00 | ₹4,500–8,000\n" +
                    "  ✈ SpiceJet SG-xxx — Departs 11:00, Arrives 13:30 | ₹4,000–7,500\n" +
                    "  ✈ Air India AI-xxx — Departs 18:30, Arrives 21:00 | ₹5,000–9,000\n" +
                    "  Duration: ~2hr 30min | Airport: IGI (DEL) → Dabolim/Manohar (GOI)"),
            Map.entry("bangalore-goa",
                    "  ✈ IndiGo 6E-xxx — Departs 07:00, Arrives 08:05 | ₹3,000–5,500\n" +
                    "  ✈ SpiceJet SG-xxx — Departs 14:00, Arrives 15:10 | ₹2,800–5,000\n" +
                    "  Duration: ~1hr 10min | Airport: Kempegowda (BLR) → Dabolim/Manohar (GOI)")
    );

    // Specific hotel recommendations for popular destinations
    private static final Map<String, String> HOTEL_RECOMMENDATIONS = Map.of(
            "goa",
                    "  🌟 Popular picks:\n" +
                    "     Budget: Zostel Goa (Anjuna), Jungle by Stuhrling\n" +
                    "     Mid-range: The Byke Pebble Bay, Resort Terra Paraiso\n" +
                    "     Luxury: Taj Exotica Resort, W Goa, The Leela Goa\n\n",
            "manali",
                    "  🌟 Popular picks:\n" +
                    "     Budget: Zostel Manali, Snow Valley Resorts\n" +
                    "     Mid-range: Hotel Rohtang Heights, Apple Country Resort\n" +
                    "     Luxury: Span Resort & Spa, Solang Valley Resort\n\n",
            "kerala",
                    "  🌟 Popular picks:\n" +
                    "     Budget: Zostel Kochi, Mango Shade Alleppey\n" +
                    "     Mid-range: Fragrant Nature Backwater Resort, Coconut Lagoon\n" +
                    "     Luxury: Kumarakom Lake Resort, Taj Malabar Kochi\n\n",
            "mumbai",
                    "  🌟 Popular picks:\n" +
                    "     Budget: Zostel Mumbai, Hotel Residency Fort\n" +
                    "     Mid-range: ITC Maratha, Trident Nariman Point\n" +
                    "     Luxury: The Taj Mahal Palace, Four Seasons Mumbai\n\n"
    );

    private String seasonContext(String month) {
        return MONTH_SEASON.getOrDefault(month.toLowerCase(),
                "Check local weather forecasts before travelling.");
    }
}
