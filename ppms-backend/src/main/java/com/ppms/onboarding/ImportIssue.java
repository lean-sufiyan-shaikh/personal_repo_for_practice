package com.ppms.onboarding;

import java.util.Map;

/**
 * One problem or note on an imported row. {@code column} is the template header the problem is in, or null for the
 * whole row; {@code code} is an ErrorCodes value the frontend shows from errors.json, filled with {@code params}.
 */
public record ImportIssue(String column, String code, Map<String, Object> params) {
}
