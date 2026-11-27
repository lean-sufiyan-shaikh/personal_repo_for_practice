package com.ppms.onboarding;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Random first-sign-in passwords that meet the password policy; ambiguous characters such as 0/O and 1/l are left out. */
@Component
public class TemporaryPasswordGenerator {

    private static final String UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#$%&*!?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        List<Character> characters = new ArrayList<>(LENGTH);
        characters.add(pick(UPPER));
        characters.add(pick(LOWER));
        characters.add(pick(DIGITS));
        characters.add(pick(SYMBOLS));
        while (characters.size() < LENGTH) {
            characters.add(pick(ALL));
        }
        Collections.shuffle(characters, random);
        StringBuilder password = new StringBuilder(LENGTH);
        characters.forEach(password::append);
        return password.toString();
    }

    private char pick(String from) {
        return from.charAt(random.nextInt(from.length()));
    }
}
