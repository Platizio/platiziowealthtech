package com.platizio.wealthtech.validation;

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Indian mobile-number structure (BUG-033). A valid number is exactly ten
 * digits starting 6-9. Mirrors {@link PanFormat}: a constant regex + message for
 * use in DTO {@code @Pattern} annotations, plus normalize/validate helpers.
 */
public final class MobileFormat {

    /** Ten digits, first digit 6-9 (TRAI mobile series). */
    public static final String INDIAN_MOBILE_REGEX = "^[6-9][0-9]{9}$";

    public static final String INDIAN_MOBILE_MESSAGE =
            "Invalid mobile number. Expected a 10-digit Indian mobile starting with 6-9.";

    public static final Pattern INDIAN_MOBILE = Pattern.compile(INDIAN_MOBILE_REGEX);

    private MobileFormat() {
    }

    /**
     * Strips spaces/punctuation and a leading +91 / 91 / 0, returning the bare
     * 10-digit number (or null when blank). Does not validate.
     */
    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String digits = raw.trim().replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    public static boolean isIndianMobile(String mobile) {
        return StringUtils.hasText(mobile) && INDIAN_MOBILE.matcher(mobile).matches();
    }

    /** Returns the number in E.164 form (+91XXXXXXXXXX) for Supabase SMS OTP. */
    public static String toE164India(String raw) {
        String digits = normalize(raw);
        if (!isIndianMobile(digits)) {
            throw new IllegalArgumentException(INDIAN_MOBILE_MESSAGE);
        }
        return "+91" + digits;
    }
}
