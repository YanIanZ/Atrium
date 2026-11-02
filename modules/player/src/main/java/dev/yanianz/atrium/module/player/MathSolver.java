package dev.yanianz.atrium.module.player;

import java.util.Locale;

/**
 * Small recursive-descent arithmetic evaluator for {@code /solve}. Supports + - * / % ^, parentheses,
 * unary signs and decimal/exponent numbers. Nothing outside the expression grammar is accepted.
 */
final class MathSolver {
    private static final int MAX_LENGTH = UtilityValues.MAX_EXPRESSION_LENGTH;

    private MathSolver() { }

    static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be empty");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Expression is too long");
        }
        Parser parser = new Parser(expression);
        double value = parser.expression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected character at position " + parser.position());
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Result is not finite");
        }
        return value;
    }

    private static final class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
        }

        boolean atEnd() {
            return pos >= input.length();
        }

        int position() {
            return pos;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        double expression() {
            double value = term();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    value += term();
                } else if (consume('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = unary();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    value *= unary();
                } else if (consume('/')) {
                    value /= unary();
                } else if (consume('%')) {
                    value %= unary();
                } else {
                    return value;
                }
            }
        }

        private double unary() {
            skipWhitespace();
            if (consume('+')) {
                return unary();
            }
            if (consume('-')) {
                return -unary();
            }
            return power();
        }

        private double power() {
            double base = primary();
            skipWhitespace();
            if (consume('^')) {
                // Right-associative: 2^3^2 is 2^(3^2).
                return Math.pow(base, unary());
            }
            return base;
        }

        private double primary() {
            skipWhitespace();
            if (consume('(')) {
                double value = expression();
                skipWhitespace();
                if (!consume(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                return value;
            }
            return number();
        }

        private double number() {
            skipWhitespace();
            int start = pos;
            boolean digits = false;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
                digits = true;
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                    digits = true;
                }
            }
            if (!digits) {
                throw new IllegalArgumentException("Expected a number at position " + start);
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                int exponent = pos;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                int exponentDigits = pos;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
                if (exponentDigits == pos) {
                    pos = exponent;
                    throw new IllegalArgumentException("Invalid exponent at position " + exponent);
                }
            }
            String text = input.substring(start, pos);
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number '" + text + "'", e);
            }
        }

        private boolean consume(char expected) {
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }
    }

    /** Locale-independent result text used by the command. */
    static String format(double value) {
        return UtilityValues.number(value);
    }

    /** True when the expression only uses supported characters; used for early command feedback. */
    static boolean looksValid(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (!(Character.isDigit(c) || Character.isWhitespace(c) || c == '.' || c == '+'
                    || c == '-' || c == '*' || c == '/' || c == '%' || c == '^'
                    || c == '(' || c == ')' || c == 'e' || c == 'E')) {
                return false;
            }
        }
        return true;
    }

    /** Normalises the expression for logs and tests. */
    static String normalize(String expression) {
        return expression == null ? "" : expression.strip().toLowerCase(Locale.ROOT);
    }
}
