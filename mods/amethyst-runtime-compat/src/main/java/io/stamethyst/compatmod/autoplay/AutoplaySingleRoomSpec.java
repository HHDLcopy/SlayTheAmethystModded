package io.stamethyst.compatmod.autoplay;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;

final class AutoplaySingleRoomSpec {
    final String characterId;
    final String monsterId;
    final ArrayList<String> cardIds;

    private AutoplaySingleRoomSpec(String characterId, String monsterId, ArrayList<String> cardIds) {
        this.characterId = characterId;
        this.monsterId = monsterId;
        this.cardIds = cardIds;
    }

    static AutoplaySingleRoomSpec load(String path) throws IOException {
        if (path == null || path.trim().length() == 0) {
            throw new IOException("single-room spec path is empty");
        }
        File file = new File(path.trim());
        Properties properties = new Properties();
        FileInputStream input = new FileInputStream(file);
        try {
            properties.load(input);
        } finally {
            input.close();
        }
        String character = readRequired(properties, "character");
        String monster = readRequired(properties, "monster");
        ArrayList<String> cards = splitCards(readRequired(properties, "cards"));
        if (cards.isEmpty()) {
            throw new IOException("single-room spec cards cannot be empty");
        }
        return new AutoplaySingleRoomSpec(character, monster, cards);
    }

    boolean matchesCharacter(String candidate) {
        return equalsToken(characterId, candidate);
    }

    String describeCards() {
        StringBuilder builder = new StringBuilder();
        for (String cardId : cardIds) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(cardId);
        }
        return builder.toString();
    }

    private static String readRequired(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().length() == 0) {
            throw new IOException("single-room spec missing " + key);
        }
        return value.trim();
    }

    private static ArrayList<String> splitCards(String value) {
        ArrayList<String> result = new ArrayList<>();
        String[] parts = value.split("[,\\r\\n]+");
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.length() > 0) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean equalsToken(String expected, String candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        String left = normalize(expected);
        String right = normalize(candidate);
        return left.equals(right);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
