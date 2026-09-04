package com.example.model;

import java.util.List;

public record ItineraryResponse(
        String destination,
        String duration,
        String month,
        WeatherInfo weather,
        List<DayPlan> days,
        BudgetBreakdown budget,
        Accommodation accommodation,
        Transport transport,
        List<String> packingList,
        List<String> travelTips
) {
    public record WeatherInfo(
            String condition,
            String temperature,
            String humidity,
            String seasonNote,
            String packingAdvice
    ) {}

    public record DayPlan(
            int day,
            String theme,
            String morning,
            String afternoon,
            String evening,
            String recommendedFood,
            String estimatedDayCost
    ) {}

    public record BudgetBreakdown(
            String totalEstimate,
            String hotelCost,
            String foodCost,
            String transportCost,
            String activitiesCost,
            String flightEstimate,
            String budgetTip
    ) {}

    public record Accommodation(
            String budgetOption,
            String midRangeOption,
            String luxuryOption,
            String priceRange,
            String recommendation
    ) {}

    public record Transport(
            String flightOption,
            String trainOption,
            String busOption,
            String roadOption,
            String bestOption
    ) {}
}
