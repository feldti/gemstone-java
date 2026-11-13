package net.gs64.gci;

/**
 * A Smalltalk Character: one Unicode scalar value (Java's {@code char} only covers the BMP).
 *
 * @param value the code point, 0 … 0x10FFFF without surrogates
 */
public record CodePoint(int value) {
    public CodePoint {
        if (!Character.isValidCodePoint(value) || (value >= 0xD800 && value <= 0xDFFF))
            throw new IllegalArgumentException(String.format("0x%X is not a Unicode scalar value.", value));
    }

    @Override
    public String toString() {
        return Character.toString(value);
    }
}
