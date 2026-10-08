package dev.lucaargolo.charta.common.game.api.card;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("ClassCanBeRecord")
public class Rank implements Comparable<Rank> {

    private final int ordinal;

    public Rank(int ordinal) {
        this.ordinal = ordinal;
    }

    public int ordinal() {
        return this.ordinal;
    }

    @Override
    public int compareTo(@NotNull Rank other) {
        return this.ordinal - other.ordinal;
    }

}
