package com.ppms.radar;

import java.util.Map;

/** One rule of a score: the points it gave out of its most, and the figures it used (shown as "Why this score"). */
public record ScoreReason(String code, int points, int maxPoints, Map<String, Object> params) {
}
