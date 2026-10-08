package dev.lucaargolo.charta.common.game;

import dev.lucaargolo.charta.common.game.api.card.Rank;

import java.util.List;

/** Ranks. The ordinals match the real registry, because the point count switches on them. */
public final class Ranks {

    public static final Rank ACE = new Rank("ace", 1);
    public static final Rank TWO = new Rank("two", 2);
    public static final Rank THREE = new Rank("three", 3);
    public static final Rank FOUR = new Rank("four", 4);
    public static final Rank FIVE = new Rank("five", 5);
    public static final Rank SIX = new Rank("six", 6);
    public static final Rank SEVEN = new Rank("seven", 7);
    public static final Rank EIGHT = new Rank("eight", 8);
    public static final Rank NINE = new Rank("nine", 9);
    public static final Rank TEN = new Rank("ten", 10);
    public static final Rank JACK = new Rank("jack", 11);
    public static final Rank QUEEN = new Rank("queen", 12);
    public static final Rank KING = new Rank("king", 13);

    public static final List<Rank> STANDARD = List.of(ACE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
            TEN, JACK, QUEEN, KING);

    private Ranks() {
    }
}
