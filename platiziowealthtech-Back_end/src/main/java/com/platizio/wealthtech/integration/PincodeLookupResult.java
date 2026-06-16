package com.platizio.wealthtech.integration;



import java.util.List;



public record PincodeLookupResult(

        String code,

        String city,

        String district,

        String stateName,

        String countryAnsiCode,

        List<String> cities

) {}


