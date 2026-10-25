package com.codemind.retrieval;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

@Component
public class LocalEmbeddingService {

    private static final int DIMENSIONS = 384;

    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String token : tokenize(text)) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(hash(token), DIMENSIONS);
            vector[index] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    public double cosine(float[] left, float[] right) {
        double score = 0.0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            score += left[i] * right[i];
        }
        return score;
    }

    private String[] tokenize(String text) {
        return text.toLowerCase(Locale.ROOT).split("[^a-z0-9_.$/@-]+");
    }

    private int hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return ((bytes[0] & 0xff) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            return value.hashCode();
        }
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        double magnitude = Math.sqrt(sum);
        if (magnitude == 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / magnitude);
        }
    }
}
