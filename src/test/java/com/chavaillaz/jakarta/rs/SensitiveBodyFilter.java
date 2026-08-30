package com.chavaillaz.jakarta.rs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example of body filter removing the secret code in JSON bodies.
 */
public class SensitiveBodyFilter implements LoggedBodyFilter {

    protected static final Pattern SECRET = Pattern.compile("\"secret-code\": \"([a-zA-Z0-9-]*)\"");

    @Override
    public void filter(StringBuilder body) {
        // Collects match boundaries first instead of mutating body while the matcher is still
        // iterating over it: replacing "masked" for a value of a different length shifts the
        // positions of any subsequent match, which can make the matcher skip it entirely.
        List<int[]> matches = new ArrayList<>();
        Matcher matcher = SECRET.matcher(body);
        while (matcher.find()) {
            matches.add(new int[]{matcher.start(1), matcher.end(1)});
        }

        // Applies replacements from the last match to the first, so earlier boundaries stay valid
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] match = matches.get(i);
            body.replace(match[0], match[1], "masked");
        }
    }

}
