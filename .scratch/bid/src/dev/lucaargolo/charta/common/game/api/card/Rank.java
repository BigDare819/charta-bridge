package dev.lucaargolo.charta.common.game.api.card;

/** Stand-in for a rank. The ordinal has to match the real registry: ace 1, jack 11, queen 12, king 13. */
public final class Rank {

    private final String name;
    private final int ordinal;

    public Rank(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public int ordinal() {
        return ordinal;
    }

    @Override
    public String toString() {
        return name;
    }
}
