package com.platizio.wealthtech.dto;



import java.util.List;



public record PincodeLookupResponse(

        String code,

        String city,

        String district,

        String stateName,

        String countryAnsiCode,

        List<String> cities

) {}


