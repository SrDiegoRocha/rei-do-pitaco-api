package com.example.futbet.util;

import com.example.futbet.repository.TournamentRepository;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.function.Predicate;

@Component
public class InviteCodeGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();
    private final TournamentRepository tournamentRepository;

    public InviteCodeGenerator(TournamentRepository tournamentRepository) {
        this.tournamentRepository = tournamentRepository;
    }

    public String generateUnique() {
        return generateUnique(code -> !tournamentRepository.existsByInviteCode(code));
    }

    String generateUnique(Predicate<String> isAvailable) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generateOne();
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique invite code after " + MAX_ATTEMPTS + " attempts");
    }

    private String generateOne() {
        char[] buf = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
